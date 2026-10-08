package noobanidus.mods.lootr.common.itemban;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import noobanidus.mods.lootr.common.api.LootrAPI;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Central item ban service: a banned item must never appear in a Lootr container or in a loot roll.
 * <br />
 * Thread-safety: loot can be rolled off the server thread, so everything read on the hot path lives in
 * one immutable {@link State} behind a single volatile field (copy-on-write). Changes are serialised
 * by {@link #LOCK}. With no source registered every check is one volatile read.
 */
public final class ItemBans {
  private static final Object LOCK = new Object();
  private static final ItemBanSource[] NO_SOURCES = new ItemBanSource[0];

  private static final ConfigBanSource CONFIG_SOURCE = new ConfigBanSource();
  // Names of the sources whose failure has been reported since the last data reload.
  private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
  private static final AtomicInteger WARNINGS = new AtomicInteger();

  private static volatile State state = new State(NO_SOURCES, ItemBanSettings.DEFAULT);
  // Resolved at most once; guarded by LOCK. null means "not looked up yet".
  private static Optional<ItemBanSource> obliterator = null;

  private ItemBans() {
  }

  // ----- sources -----

  /**
   * @return false if this source instance is already registered.
   */
  public static boolean register(ItemBanSource source) {
    if (source == null) {
      throw new IllegalArgumentException("source must not be null");
    }
    synchronized (LOCK) {
      State current = state;
      if (indexOf(current.sources, source) != -1) {
        return false;
      }
      ItemBanSource[] sources = Arrays.copyOf(current.sources, current.sources.length + 1);
      sources[sources.length - 1] = source;
      state = new State(sources, current.settings);
      return true;
    }
  }

  /**
   * @return false if this source instance was not registered.
   */
  public static boolean unregister(ItemBanSource source) {
    synchronized (LOCK) {
      State current = state;
      int index = indexOf(current.sources, source);
      if (index == -1) {
        return false;
      }
      ItemBanSource[] sources = new ItemBanSource[current.sources.length - 1];
      System.arraycopy(current.sources, 0, sources, 0, index);
      System.arraycopy(current.sources, index + 1, sources, index, sources.length - index);
      state = new State(sources.length == 0 ? NO_SOURCES : sources, current.settings);
      return true;
    }
  }

  public static List<ItemBanSource> getSources() {
    return List.of(state.sources);
  }

  private static int indexOf(ItemBanSource[] sources, ItemBanSource source) {
    for (int i = 0; i < sources.length; i++) {
      if (sources[i] == source) {
        return i;
      }
    }
    return -1;
  }

  // ----- state queries -----

  /**
   * @return true if at least one ban source is registered. There is no separate master switch: the
   * feature is off exactly when there is no source, which is the case with the default configuration
   * unless Item Obliterator is installed. Nothing in this class does any work then.
   */
  public static boolean isActive() {
    return state.active;
  }

  /**
   * @return true if every loot table roll is filtered ({@link #isActive()}, the option `filter_all_loot`, and the
   * hook in the loot table class is in place, see {@link LootRollHook}).
   */
  public static boolean isFilteringAllLoot() {
    return state.filterAllLoot && !LootRollHook.isMissing();
  }

  /**
   * @return true if saved per-player inventories are scrubbed on access ({@link #isActive()} and the
   * option `scrub_saved_inventories`).
   */
  public static boolean isScrubbingSavedInventories() {
    return state.scrubSaved;
  }

  // ----- checks -----

  /**
   * Never throws. A source that fails is treated as "not banned" for this call and reported with one
   * warning per source name until the next data reload.
   */
  public static boolean isBanned(ItemStack stack) {
    if (stack == null || stack.isEmpty()) {
      return false;
    }
    for (ItemBanSource source : state.sources) {
      try {
        if (source.isBanned(stack)) {
          return true;
        }
      } catch (Throwable t) {
        reportFailure(source, t);
      }
    }
    return false;
  }

  /**
   * Removes banned stacks from the list.
   *
   * @return the number of stacks that were removed.
   */
  public static int removeBanned(List<ItemStack> stacks) {
    if (!state.active || stacks.isEmpty()) {
      return 0;
    }
    int before = stacks.size();
    stacks.removeIf(ItemBans::isBanned);
    return before - stacks.size();
  }

  /**
   * Empties every slot that holds a banned stack, in one pass over the slots. Nothing is re-rolled.
   *
   * @return the number of stacks that were removed.
   */
  public static int scrub(Container container) {
    if (!state.active) {
      return 0;
    }
    int removed = 0;
    for (int i = 0, size = container.getContainerSize(); i < size; i++) {
      if (isBanned(container.getItem(i))) {
        // Permanent by design: the banned stack is deleted, not stored anywhere, and does not come back
        // if the ban is lifted later.
        container.setItem(i, ItemStack.EMPTY);
        removed++;
      }
    }
    return removed;
  }

  /**
   * Used by the loot table mixin: every loot roll hands its output consumer through here.
   *
   * @return the same instance when all-loot filtering is off (no allocation), otherwise a consumer that
   * drops banned stacks before they reach the given one.
   */
  public static Consumer<ItemStack> wrapLootConsumer(Consumer<ItemStack> consumer) {
    if (!state.filterAllLoot || consumer instanceof FilteringConsumer) {
      return consumer;
    }
    return new FilteringConsumer(consumer);
  }

  private static void reportFailure(ItemBanSource source, Throwable failure) {
    String name;
    try {
      name = String.valueOf(source.name());
    } catch (Throwable t) {
      name = source.getClass().getName();
    }
    if (WARNED.add(name)) {
      WARNINGS.incrementAndGet();
      LootrAPI.LOG.warn("Item ban source '{}' failed and is treated as \"not banned\" whenever it fails. This is reported only once until the next data reload.", name, failure);
    }
  }

  /**
   * @return true if a failure of the named source has been reported since the last data reload.
   */
  public static boolean hasWarned(String sourceName) {
    return WARNED.contains(sourceName);
  }

  /**
   * @return how many source failures have been reported (logged) since startup.
   */
  public static int warningCount() {
    return WARNINGS.get();
  }

  // ----- configuration and reloads -----

  public static ItemBanSettings getSettings() {
    return state.settings;
  }

  /**
   * Applies a configuration snapshot and registers or unregisters the built-in sources to match it.
   * Called by the platform at start-up and whenever its configuration is loaded or saved. Sources
   * registered by other code are left alone.
   */
  public static void applySettings(ItemBanSettings settings) {
    if (settings == null) {
      settings = ItemBanSettings.DEFAULT;
    }
    synchronized (LOCK) {
      if (settings.respectItemObliterator()) {
        if (obliterator == null) {
          obliterator = ObliteratorBanSource.create();
        }
        if (obliterator.isPresent() && register(obliterator.get())) {
          LootrAPI.LOG.info("Item Obliterator found: items it disables are removed from Lootr containers and loot rolls.");
        }
      } else if (obliterator != null && obliterator.isPresent()) {
        unregister(obliterator.get());
      }

      CONFIG_SOURCE.setEntries(settings.extraBannedItems());
      if (CONFIG_SOURCE.hasEntries()) {
        register(CONFIG_SOURCE);
      } else {
        unregister(CONFIG_SOURCE);
      }

      state = new State(state.sources, settings);
    }
  }

  /**
   * Called by the platform after data packs (and therefore tags) were loaded or reloaded.
   */
  public static void onDataReload() {
    WARNED.clear();
    CONFIG_SOURCE.invalidate();
  }

  private record State(ItemBanSource[] sources, ItemBanSettings settings, boolean active, boolean filterAllLoot, boolean scrubSaved) {
    State(ItemBanSource[] sources, ItemBanSettings settings) {
      this(sources, settings, sources.length > 0, sources.length > 0 && settings.filterAllLoot(), sources.length > 0 && settings.scrubSavedInventories());
    }
  }

  private static final class FilteringConsumer implements Consumer<ItemStack> {
    private final Consumer<ItemStack> delegate;

    private FilteringConsumer(Consumer<ItemStack> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void accept(ItemStack stack) {
      if (!isBanned(stack)) {
        delegate.accept(stack);
      }
    }
  }
}
