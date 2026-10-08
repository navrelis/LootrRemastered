package noobanidus.mods.lootr.common.integratedloot;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.PlatformAPI;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.Rolls;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Decision;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Target;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Integrated loot: selected container loot tables additionally yield a few stacks sampled from other loot
 * tables. Which table is extended from which pool comes from data packs (see {@link IntegratedLootDefinitions})
 * and is resolved once per data load into an {@link IntegratedLootSnapshot}.
 * <br />
 * Thread-safety: loot can be rolled off the server thread. Everything a roll reads lives in one immutable
 * {@link Active} behind a single volatile field; it is null while the feature is switched off or no loot table
 * is extended, so that a roll then costs one field read. Changes are serialised by {@link #LOCK}.
 */
public final class IntegratedLoot {
  /**
   * Items in this tag are never added by an extension.
   */
  public static final TagKey<Item> EXCLUDED_ITEMS = TagKey.create(Registries.ITEM, LootrAPI.rl("integrated_loot/excluded"));

  private static final String VANILLA_NAMESPACE = ResourceLocation.DEFAULT_NAMESPACE;
  private static final int MAX_SOURCE_ROLLS = 2;

  private static final Object LOCK = new Object();
  private static final ThreadLocal<ThreadState> THREAD_STATE = ThreadLocal.withInitial(ThreadState::new);
  // Targets whose failure has been reported since the last data load.
  private static final Set<ResourceKey<LootTable>> FAILED = ConcurrentHashMap.newKeySet();

  private static final AtomicLong EXTENSIONS = new AtomicLong();
  private static final AtomicLong SOURCE_ROLLS = new AtomicLong();
  private static final AtomicLong STACKS_ADDED = new AtomicLong();
  private static final AtomicLong FAILURES = new AtomicLong();
  private static final AtomicLong SUPPRESSED_MAPS = new AtomicLong();

  @Nullable
  private static volatile Active active = null;
  private static volatile IntegratedLootSnapshot snapshot = IntegratedLootSnapshot.EMPTY;
  private static volatile IntegratedLootSettings settings = IntegratedLootSettings.DEFAULT;

  // Guarded by LOCK. The definitions are what the reload listener read last; the other two say what the current
  // snapshot was resolved from, so that one data load is resolved only once.
  private static IntegratedLootDefinitions definitions = IntegratedLootDefinitions.EMPTY;
  @Nullable
  private static IntegratedLootDefinitions resolvedDefinitions = null;
  @Nullable
  private static RegistryAccess resolvedRegistries = null;

  private IntegratedLoot() {
  }

  // ----- configuration -----

  public static IntegratedLootSettings getSettings() {
    return settings;
  }

  /**
   * Applies a configuration snapshot. Called by the platform at start-up and whenever its configuration is
   * loaded or saved.
   */
  public static void applySettings(@Nullable IntegratedLootSettings newSettings) {
    synchronized (LOCK) {
      settings = newSettings == null ? IntegratedLootSettings.DEFAULT : newSettings;
      publish();
    }
  }

  // ----- data lifecycle -----

  /**
   * @return the result of the last resolution: decisions and diagnostics. Empty before the first data load was
   * resolved and after the server stopped.
   */
  public static IntegratedLootSnapshot snapshot() {
    return snapshot;
  }

  /**
   * Called by the platform's reload listener with the pool and rule files it read. They take effect with the
   * next {@link #onDataLoaded(RegistryAccess)}, once the loot tables of the same load are in place.
   */
  public static void setDefinitions(@Nullable IntegratedLootDefinitions newDefinitions) {
    synchronized (LOCK) {
      definitions = newDefinitions == null ? IntegratedLootDefinitions.EMPTY : newDefinitions;
    }
  }

  /**
   * Called by the platform after data packs were loaded or reloaded, with the registries that now hold the
   * loot tables. Resolves the rules against them; a second call for the same load does nothing.
   */
  public static void onDataLoaded(RegistryAccess registries) {
    synchronized (LOCK) {
      if (registries == resolvedRegistries && definitions == resolvedDefinitions) {
        return;
      }
      IntegratedLootDefinitions current = definitions;
      IntegratedLootSnapshot resolved;
      try {
        Optional<Registry<LootTable>> tables = registries.registry(Registries.LOOT_TABLE);
        resolved = tables.isEmpty() ? IntegratedLootSnapshot.EMPTY : IntegratedLootSnapshot.resolve(current, tables.get(), PlatformAPI::isModLoaded);
      } catch (RuntimeException e) {
        LootrAPI.LOG.error("Integrated loot: resolving the rules failed; no loot table is extended until the next data reload.", e);
        resolved = IntegratedLootSnapshot.EMPTY;
      }
      resolvedRegistries = registries;
      resolvedDefinitions = current;
      snapshot = resolved;
      FAILED.clear();
      publish();
      if (!resolved.isEmpty()) {
        LootrAPI.LOG.info("Integrated loot: {} loot tables extended, {} excluded ({} pools, {} of {} rule files active, {} malformed files).", resolved.extensionCount(), resolved.exclusionCount(), resolved.pools().size(), resolved.activeRuleFiles().size(), resolved.ruleFiles().size(), resolved.malformedFiles().size());
      }
    }
  }

  /**
   * Safety net for the case that the server starts without the data load having been announced.
   */
  public static void onServerStarting(MinecraftServer server) {
    onDataLoaded(server.reloadableRegistries().get());
  }

  public static void onServerStopped() {
    synchronized (LOCK) {
      definitions = IntegratedLootDefinitions.EMPTY;
      resolvedDefinitions = null;
      resolvedRegistries = null;
      snapshot = IntegratedLootSnapshot.EMPTY;
      FAILED.clear();
      publish();
    }
    EXTENSIONS.set(0);
    SOURCE_ROLLS.set(0);
    STACKS_ADDED.set(0);
    FAILURES.set(0);
    SUPPRESSED_MAPS.set(0);
  }

  // Caller holds LOCK.
  private static void publish() {
    IntegratedLootSnapshot current = snapshot;
    IntegratedLootSettings currentSettings = settings;
    active = currentSettings.enabled() && !current.targets.isEmpty() ? new Active(current.targets, currentSettings) : null;
  }

  // ----- runtime counters -----

  /**
   * @return counters since the server started. Cheap; meant for commands and tests.
   */
  public static Stats stats() {
    return new Stats(EXTENSIONS.get(), SOURCE_ROLLS.get(), STACKS_ADDED.get(), FAILURES.get(), SUPPRESSED_MAPS.get());
  }

  /**
   * @return true if a failed extension of this loot table has been reported since the last data load.
   */
  public static boolean hasReportedFailure(ResourceKey<LootTable> table) {
    return FAILED.contains(table);
  }

  /**
   * @param extensions     rolls for which at least one source table was rolled.
   * @param sourceRolls    source table rolls.
   * @param stacksAdded    stacks appended to roll results.
   * @param failures       extensions that were abandoned because of an exception.
   * @param suppressedMaps explorer maps that were not created while a source table was rolled.
   */
  public record Stats(long extensions, long sourceRolls, long stacksAdded, long failures, long suppressedMaps) {
  }

  // ----- hooks -----

  /**
   * Called by the loot table mixin from `LootTable.fill` once the roll result and the number of empty slots
   * of the container are known. With no decision for the table this is one field read and one map lookup.
   *
   * @param random     the random of the roll's own loot context.
   * @param emptySlots the number of empty slots vanilla is about to distribute the stacks over.
   */
  public static void extendFill(LootTable table, LootParams params, RandomSource random, ObjectArrayList<ItemStack> stacks, int emptySlots) {
    Active current = active;
    if (current == null) {
      return;
    }
    Target target = current.targets.get(table);
    if (target == null) {
      return;
    }
    int occupied = 0;
    for (int i = 0, size = stacks.size(); i < size; i++) {
      if (!stacks.get(i).isEmpty()) {
        occupied++;
      }
    }
    extend(current.settings, target, params, random, stacks, emptySlots - occupied);
  }

  /**
   * Called by the loot table mixin for the list-returning `LootTable.getRandomItems` overloads. No container
   * is known there, so only the configured maximum limits the extension.
   */
  public static void extendList(LootTable table, LootParams params, RandomSource random, ObjectArrayList<ItemStack> stacks) {
    Active current = active;
    if (current == null) {
      return;
    }
    Target target = current.targets.get(table);
    if (target == null) {
      return;
    }
    extend(current.settings, target, params, random, stacks, Integer.MAX_VALUE);
  }

  /**
   * Called by the explorer map mixin for every map a loot function is about to turn into an explorer map.
   *
   * @return true if the map must not be created because a source table is being rolled on this thread.
   */
  public static boolean suppressMapCreation() {
    ThreadState state = THREAD_STATE.get();
    if (state.forceSkipMaps) {
      state.forcedSkips++;
      return true;
    }
    if (active == null || !state.skipMaps) {
      return false;
    }
    SUPPRESSED_MAPS.incrementAndGet();
    return true;
  }

  /**
   * Runs the action with explorer map creation switched off on the calling thread, whether or not any loot table
   * is extended. Meant for diagnostics that roll many loot tables; maps skipped here do not count in
   * {@link #stats()}.
   *
   * @return how many maps were not created while the action ran.
   */
  public static int withoutMapCreation(Runnable action) {
    ThreadState state = THREAD_STATE.get();
    boolean wasForced = state.forceSkipMaps;
    int before = state.forcedSkips;
    state.forceSkipMaps = true;
    try {
      action.run();
    } finally {
      state.forceSkipMaps = wasForced;
    }
    return state.forcedSkips - before;
  }

  // ----- engine -----

  private static void extend(IntegratedLootSettings settings, Target target, LootParams params, RandomSource random, ObjectArrayList<ItemStack> stacks, int room) {
    ThreadState state = THREAD_STATE.get();
    // Never re-entrant: while a source table is rolled, nothing is extended on this thread.
    if (state.rolling) {
      return;
    }
    int limit = Math.min(settings.maxExtraStacks(), room);
    if (limit <= 0) {
      return;
    }
    Decision decision = target.decision();
    try {
      Rolls rolls = decision.rolls();
      int wanted = rolls.min();
      if (!rolls.isFixed()) {
        wanted += random.nextInt((int) Math.min((long) rolls.max() - rolls.min() + 1, Integer.MAX_VALUE));
      }
      wanted = Math.min(wanted, limit);
      if (wanted <= 0) {
        return;
      }

      LootTable[] sources = target.sources();
      List<ItemStack> candidates = new ArrayList<>();
      int first = pickSource(target, random, -1);
      collectCandidates(rollSource(state, sources[first], params, random, settings.skipMapItems()), stacks, settings.skipExistingItems(), candidates);
      int sourceRolls = 1;
      int second = -1;
      if (candidates.size() < wanted && sources.length > 1 && sourceRolls < MAX_SOURCE_ROLLS) {
        second = pickSource(target, random, first);
        collectCandidates(rollSource(state, sources[second], params, random, settings.skipMapItems()), stacks, settings.skipExistingItems(), candidates);
        sourceRolls++;
      }

      int sizeBefore = stacks.size();
      int added = draw(candidates, wanted, settings.moddedItemWeight(), random, stacks);

      EXTENSIONS.incrementAndGet();
      SOURCE_ROLLS.addAndGet(sourceRolls);
      STACKS_ADDED.addAndGet(added);
      if (settings.debugLog()) {
        List<ResourceLocation> rolled = new ArrayList<>();
        rolled.add(decision.pool().orElseThrow().sources().get(first).table().location());
        if (second != -1) {
          rolled.add(decision.pool().orElseThrow().sources().get(second).table().location());
        }
        List<String> addedStacks = new ArrayList<>();
        for (int i = sizeBefore; i < stacks.size(); i++) {
          addedStacks.add(stacks.get(i).getCount() + "x " + BuiltInRegistries.ITEM.getKey(stacks.get(i).getItem()));
        }
        LootrAPI.LOG.info("Integrated loot: {} extended from pool {}, sources rolled {}, stacks added {} ({} wanted, {} candidates).", decision.table().location(), decision.poolId().orElseThrow(), rolled, addedStacks, wanted, candidates.size() + added);
      }
    } catch (Exception | LinkageError e) {
      FAILURES.incrementAndGet();
      if (FAILED.add(decision.table())) {
        LootrAPI.LOG.warn("Integrated loot: extending '{}' from pool '{}' failed; the roll keeps its normal result. This is reported only once per loot table until the next data reload.", decision.table().location(), decision.poolId().orElse(null), e);
      }
    }
  }

  private static int pickSource(Target target, RandomSource random, int excluded) {
    int[] weights = target.weights();
    if (weights.length == 1) {
      return 0;
    }
    long total = target.totalWeight() - (excluded == -1 ? 0 : weights[excluded]);
    long pick = total <= Integer.MAX_VALUE ? random.nextInt((int) total) : Math.floorMod(random.nextLong(), total);
    int last = -1;
    for (int i = 0; i < weights.length; i++) {
      if (i == excluded) {
        continue;
      }
      last = i;
      pick -= weights[i];
      if (pick < 0) {
        break;
      }
    }
    return last;
  }

  private static ObjectArrayList<ItemStack> rollSource(ThreadState state, LootTable source, LootParams params, RandomSource random, boolean skipMaps) {
    // A seed of 0 would make vanilla fall back to the level's shared random.
    long seed = random.nextLong();
    if (seed == 0L) {
      seed = 1L;
    }
    boolean wasRolling = state.rolling;
    boolean wasSkippingMaps = state.skipMaps;
    state.rolling = true;
    state.skipMaps = skipMaps;
    try {
      return source.getRandomItems(params, seed);
    } finally {
      state.rolling = wasRolling;
      state.skipMaps = wasSkippingMaps;
    }
  }

  private static void collectCandidates(List<ItemStack> rolled, List<ItemStack> existing, boolean skipExisting, List<ItemStack> candidates) {
    for (ItemStack stack : rolled) {
      if (stack.isEmpty() || stack.is(EXCLUDED_ITEMS) || ItemBans.isBanned(stack)) {
        continue;
      }
      Item item = stack.getItem();
      if (skipExisting && containsItem(existing, item) || containsItem(candidates, item)) {
        continue;
      }
      candidates.add(stack);
    }
  }

  private static boolean containsItem(List<ItemStack> stacks, Item item) {
    for (int i = 0, size = stacks.size(); i < size; i++) {
      if (stacks.get(i).is(item)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Moves up to `wanted` candidates into the result, drawn without replacement. Drawn candidates are removed
   * from the list.
   *
   * @return the number of stacks added.
   */
  private static int draw(List<ItemStack> candidates, int wanted, int moddedItemWeight, RandomSource random, ObjectArrayList<ItemStack> result) {
    if (candidates.size() <= wanted) {
      int added = candidates.size();
      for (ItemStack candidate : candidates) {
        result.add(candidate.copy());
      }
      candidates.clear();
      return added;
    }
    IntArrayList weights = new IntArrayList(candidates.size());
    int total = 0;
    for (ItemStack candidate : candidates) {
      int weight = VANILLA_NAMESPACE.equals(BuiltInRegistries.ITEM.getKey(candidate.getItem()).getNamespace()) ? 1 : moddedItemWeight;
      weights.add(weight);
      total += weight;
    }
    for (int drawn = 0; drawn < wanted; drawn++) {
      int pick = random.nextInt(total);
      int index = 0;
      while ((pick -= weights.getInt(index)) >= 0) {
        index++;
      }
      total -= weights.removeInt(index);
      result.add(candidates.remove(index).copy());
    }
    return wanted;
  }

  private record Active(Reference2ObjectMap<LootTable, Target> targets, IntegratedLootSettings settings) {
  }

  private static final class ThreadState {
    private boolean rolling = false;
    private boolean skipMaps = false;
    // Set only by withoutMapCreation.
    private boolean forceSkipMaps = false;
    private int forcedSkips = 0;
  }
}
