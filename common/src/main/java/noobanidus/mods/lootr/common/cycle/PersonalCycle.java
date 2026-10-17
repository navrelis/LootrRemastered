package noobanidus.mods.lootr.common.cycle;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.BuiltInLootrTypes;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.LootrTags;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.data.DataStorage;
import noobanidus.mods.lootr.common.data.LootrSavedData;
import noobanidus.mods.lootr.common.impl.DefaultLootrAPIImpl;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The personal cycle: every player's (or team's) own view of a container is re-rolled at fixed real-time intervals
 * after their first opening, a configured number of times, and afterwards decays for that player only.
 * <br />
 * Everything is evaluated lazily when the player opens, from the stored start and the configuration in force then.
 * The only periodic work is the cheap visual refresh in {@link #tick}. Containers that Lootr's own refresh or decay
 * handles are left to it; see {@link #exclusion}.
 */
public final class PersonalCycle {
  // Ticks between two visual checks of one container (plus the container's random offset).
  public static final int VISUAL_CHECK_INTERVAL = 600;

  // The refresh index of the re-roll that is being filled on this thread, read by the loot filler for its seed.
  private static final ThreadLocal<Integer> REROLL_INDEX = new ThreadLocal<>();

  private PersonalCycle() {
  }

  // ----- The schedule (no world needed) -----

  /**
   * @param due         re-rolls that are due by now (at most the configured count)
   * @param decayDue    whether the period after the last re-roll has ended and decay applies
   * @param nextEventAt epoch milliseconds of the next re-roll or decay, -1 for none
   * @param nextIsDecay whether that next event is the decay
   */
  public record Evaluation(int due, boolean decayDue, long nextEventAt, boolean nextIsDecay) {
  }

  /**
   * Re-rolls happen at start + I, start + 2I, ..., start + N*I; decay (if it applies) at start + (N+1)*I. Time before
   * the start (a clock that went backwards) counts as no time at all, and re-rolls already applied are never undone.
   * Decay needs both the schedule and the container (`canDecay`) to allow it.
   */
  public static Evaluation evaluate(long start, int applied, long now, CycleSettings.Schedule schedule, boolean canDecay) {
    long interval = schedule.intervalMillis();
    int count = schedule.count();
    boolean decays = canDecay && schedule.decay();
    long elapsed = Math.max(0L, now - start);
    long periods = elapsed / interval;
    int due = (int) Math.min(periods, count);
    boolean decayDue = decays && periods >= count + 1L;
    int effective = Math.max(applied, due);
    if (decayDue) {
      return new Evaluation(due, true, -1L, false);
    }
    if (effective < count) {
      return new Evaluation(due, false, start + (effective + 1L) * interval, false);
    }
    if (decays) {
      return new Evaluation(due, false, start + (count + 1L) * interval, true);
    }
    return new Evaluation(due, false, -1L, false);
  }

  // ----- Which containers take part -----

  public enum Exclusion {
    DISABLED,
    FAKE_PLAYER,
    CUSTOM_INVENTORY,
    TYPE,
    EXCLUDED_TABLE,
    EXCLUDED_DIMENSION,
    OLD_REFRESH,
    OLD_DECAY,
    EXEMPT_STRUCTURE;

    public Component describe() {
      return Component.translatable("lootr.cycle.reason." + name().toLowerCase(Locale.ROOT));
    }
  }

  /**
   * Why the container is not subject to the personal cycle, or null if it is. The exempt structure tag is only
   * checked when a cycle starts; its result is kept in the record.
   */
  @Nullable
  public static Exclusion exclusion(ILootrInfoProvider provider, CycleSettings settings) {
    if (!settings.enabled()) {
      return Exclusion.DISABLED;
    }
    ResourceKey<LootTable> table = provider.getInfoLootTable();
    if (table == null || provider.isInfoReferenceInventory() || provider.getInfoNewType() == BuiltInLootrTypes.INVENTORY) {
      return Exclusion.CUSTOM_INVENTORY;
    }
    if (!provider.canRefresh()) {
      return Exclusion.TYPE;
    }
    if (settings.isTableExcluded(table.location())) {
      return Exclusion.EXCLUDED_TABLE;
    }
    if (settings.isDimensionExcluded(provider.getInfoDimension().location())) {
      return Exclusion.EXCLUDED_DIMENSION;
    }
    // Lootr's own refresh and decay win. The "anything" checks are cheap and false by default; the per-container
    // checks can look up structures.
    if (LootrAPI.isAnythingRefreshing() && LootrAPI.isRefreshing(provider)) {
      return Exclusion.OLD_REFRESH;
    }
    if (LootrAPI.isAnythingDecaying() && LootrAPI.isDecaying(provider)) {
      return Exclusion.OLD_DECAY;
    }
    return null;
  }

  public static CycleSettings.Schedule scheduleFor(ILootrInfoProvider provider, CycleSettings settings) {
    ResourceKey<LootTable> table = provider.getInfoLootTable();
    return settings.scheduleFor(table == null ? null : table.location());
  }

  public static boolean decays(ILootrInfoProvider provider, CycleSettings.Schedule schedule) {
    return schedule.decay() && provider.canDecay();
  }

  public static boolean isInExemptStructure(ILootrInfoProvider provider) {
    MinecraftServer server = LootrAPI.getServer();
    if (server == null || !(provider.getInfoLevel() instanceof ServerLevel level)) {
      return false;
    }
    if (!DefaultLootrAPIImpl.hasTaggedStructures(server, LootrTags.Structure.PERSONAL_CYCLE_EXEMPT)) {
      return false;
    }
    BlockPos pos = provider.getInfoPos();
    return LootrAPI.isTaggedStructurePresent(level, new ChunkPos(pos), LootrTags.Structure.PERSONAL_CYCLE_EXEMPT, pos);
  }

  // ----- Opening (called from `handleProviderOpen`) -----

  /**
   * What {@link #beforeOpen} found out, handed to {@link #afterOpen}.
   */
  public static final class Opening {
    static final Opening NONE = new Opening(null, null, null, false, false);
    static final Opening REFUSED = new Opening(null, null, null, false, false);

    private final LootrSavedData data;
    private final UUID key;
    private final CycleSettings.Schedule schedule;
    private final boolean decays;
    private boolean rerolled;

    private Opening(LootrSavedData data, UUID key, CycleSettings.Schedule schedule, boolean decays, boolean rerolled) {
      this.data = data;
      this.key = key;
      this.schedule = schedule;
      this.decays = decays;
      this.rerolled = rerolled;
    }

    // The container decayed for the player: the menu must not open.
    public boolean refused() {
      return this == REFUSED;
    }
  }

  /**
   * Applies what is due before the menu opens: a re-roll replaces the player's inventory (once, however many periods
   * passed), decay drops it and refuses the opening.
   */
  public static Opening beforeOpen(ILootrInfoProvider provider, ServerPlayer player) {
    CycleSettings settings = LootrAPI.getPersonalCycleSettings();
    if (!settings.enabled() || LootrAPI.isFakePlayer(player) || exclusion(provider, settings) != null) {
      return Opening.NONE;
    }
    LootrSavedData data = DataStorage.getData(provider);
    if (data == null) {
      return Opening.NONE;
    }
    UUID key = LootrAPI.resolveServerPlayerTeam(player);
    CycleSettings.Schedule schedule = scheduleFor(provider, settings);
    boolean decays = decays(provider, schedule);
    CycleRecord record = data.getCycle(key);
    if (record == null) {
      // The cycle starts once the menu opened (also for an inventory stored before this feature existed).
      return new Opening(data, key, schedule, decays, false);
    }
    if (record.exempt()) {
      return Opening.NONE;
    }
    if (record.decayed()) {
      refuse(provider, player);
      return Opening.REFUSED;
    }
    Evaluation evaluation = evaluate(record.start(), record.refreshes(), WallClock.now(), schedule, decays);
    if (evaluation.decayDue()) {
      record.setDecayed(true);
      data.discardInventory(key);
      data.markChanged();
      refuse(provider, player);
      return Opening.REFUSED;
    }
    Opening opening = new Opening(data, key, schedule, decays, false);
    if (evaluation.due() > record.refreshes()) {
      record.setRefreshes(evaluation.due());
      data.discardInventory(key);
      data.markChanged();
      REROLL_INDEX.set(evaluation.due());
      try {
        data.createInventory(provider, player, provider.getDefaultFiller());
      } finally {
        REROLL_INDEX.remove();
      }
      opening.rerolled = true;
    }
    return opening;
  }

  /**
   * After the menu opened: starts the cycle of a player who has none yet, and tells the player where it stands.
   */
  public static void afterOpen(Opening opening, ILootrInfoProvider provider, ServerPlayer player) {
    if (opening.data == null) {
      return;
    }
    long now = WallClock.now();
    CycleRecord record = opening.data.getCycle(opening.key);
    if (record == null) {
      record = new CycleRecord(now, 0, false, isInExemptStructure(provider));
      opening.data.putCycle(opening.key, record);
    }
    if (record.exempt() || !LootrAPI.isNotificationsEnabled()) {
      return;
    }
    int count = opening.schedule.count();
    if (opening.rerolled) {
      player.displayClientMessage(Component.translatable("lootr.cycle.message.refreshed", record.refreshes(), count).setStyle(LootrAPI.getRefreshStyle()), true);
      return;
    }
    Evaluation evaluation = evaluate(record.start(), record.refreshes(), now, opening.schedule, opening.decays);
    if (evaluation.nextEventAt() < 0) {
      return;
    }
    Component remaining = formatDuration(evaluation.nextEventAt() - now);
    if (evaluation.nextIsDecay()) {
      player.displayClientMessage(Component.translatable("lootr.cycle.message.decays_in", remaining).setStyle(LootrAPI.getDecayStyle()), true);
    } else {
      player.displayClientMessage(Component.translatable("lootr.cycle.message.refreshes_in", remaining, record.refreshes(), count).setStyle(LootrAPI.getRefreshStyle()), true);
    }
  }

  // The container stays in place and looks opened to the player; it just does not open any more.
  private static void refuse(ILootrInfoProvider provider, ServerPlayer player) {
    if (provider.addVisualOpener(player)) {
      provider.performUpdate(player);
    }
    player.displayClientMessage(Component.translatable("lootr.cycle.message.decayed").setStyle(LootrAPI.getDecayStyle()), true);
  }

  /**
   * Whether the container decayed for the player under the cycle in force. Only data that exists is looked at.
   */
  public static boolean isDecayedFor(ILootrInfoProvider provider, ServerPlayer player) {
    CycleSettings settings = LootrAPI.getPersonalCycleSettings();
    if (!settings.enabled()) {
      return false;
    }
    LootrSavedData data = DataStorage.getExistingData(provider.getInfoKey());
    return data != null && isInventoryWithheld(data, provider, LootrAPI.resolveServerPlayerTeam(player));
  }

  /**
   * A player for whom the container decayed gets no new inventory, also not through breaking, commands or other
   * callers of `createInventory`.
   */
  public static boolean isInventoryWithheld(LootrSavedData data, ILootrInfoProvider provider, UUID key) {
    if (!data.hasCycles()) {
      return false;
    }
    CycleRecord record = data.getCycle(key);
    if (record == null || !record.decayed() || record.exempt()) {
      return false;
    }
    return exclusion(provider, LootrAPI.getPersonalCycleSettings()) == null;
  }

  /**
   * The seed of a re-roll. With `randomize_seed` off every roll of a container uses its seed, so a re-roll would
   * yield the same loot again; it is derived from the seed and the refresh index instead. A random seed is kept.
   */
  public static long rerollSeed(long infoSeed, long seed) {
    Integer index = REROLL_INDEX.get();
    if (index == null || index <= 0 || seed != infoSeed) {
      return seed;
    }
    return HashCommon.mix(infoSeed ^ HashCommon.mix((long) index));
  }

  // ----- Visual refresh (called from `handleProviderTick`) -----

  /**
   * Once every {@link #VISUAL_CHECK_INTERVAL} ticks per container: players whose next re-roll is due see the
   * container as unopened again. Costs a settings read when the feature is disabled.
   */
  public static void tick(ILootrInfoProvider provider) {
    CycleSettings settings = LootrAPI.getPersonalCycleSettings();
    if (!settings.enabled()) {
      return;
    }
    int ticks = LootrAPI.getCurrentTicks();
    if (ticks < 0 || Math.floorMod(ticks + provider.getRandomOffset(), VISUAL_CHECK_INTERVAL) != 0) {
      return;
    }
    refreshVisualOpeners(provider);
  }

  /**
   * Removes the visual opener of every player whose next re-roll is due, so that they see the container as unopened
   * again. Returns whether any was removed.
   */
  public static boolean refreshVisualOpeners(ILootrInfoProvider provider) {
    CycleSettings settings = LootrAPI.getPersonalCycleSettings();
    // Only data in memory: a container whose data is not loaded has no player near it who could see a change.
    LootrSavedData data = DataStorage.getLoadedData(provider.getInfoKey());
    if (data == null || !data.hasCycles() || data.getVisualOpeners().isEmpty() || exclusion(provider, settings) != null) {
      return false;
    }
    CycleSettings.Schedule schedule = scheduleFor(provider, settings);
    boolean decays = decays(provider, schedule);
    long now = WallClock.now();
    List<UUID> due = null;
    for (Map.Entry<UUID, CycleRecord> entry : data.getCycles().entrySet()) {
      CycleRecord record = entry.getValue();
      if (record.decayed() || record.exempt() || !data.getVisualOpeners().contains(entry.getKey())) {
        continue;
      }
      Evaluation evaluation = evaluate(record.start(), record.refreshes(), now, schedule, decays);
      if (!evaluation.decayDue() && evaluation.due() > record.refreshes()) {
        if (due == null) {
          due = new ArrayList<>();
        }
        due.add(entry.getKey());
      }
    }
    if (due == null) {
      return false;
    }
    for (UUID id : due) {
      data.removeVisualOpener(id);
      ServerPlayer player = LootrAPI.getPlayer(id);
      if (player != null) {
        provider.performClose(player);
      }
    }
    provider.performUpdate();
    return true;
  }

  // ----- Text -----

  public static Component formatDuration(long millis) {
    long minutes = Math.max(0L, millis) / 60_000L;
    if (minutes < 1) {
      return Component.translatable("lootr.cycle.duration.under_minute");
    }
    long days = minutes / 1440;
    long hours = (minutes % 1440) / 60;
    long rest = minutes % 60;
    if (days > 0) {
      return Component.translatable("lootr.cycle.duration.days_hours", days, hours);
    }
    if (hours > 0) {
      return Component.translatable("lootr.cycle.duration.hours_minutes", hours, rest);
    }
    return Component.translatable("lootr.cycle.duration.minutes", rest);
  }
}
