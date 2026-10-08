package noobanidus.mods.lootr.fabric.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.LootrTags;
import noobanidus.mods.lootr.common.api.config.SyncedConfig;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.config.ConfigManagerBase;
import noobanidus.mods.lootr.common.impl.LootrServiceRegistry;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSettings;
import noobanidus.mods.lootr.common.itemban.ItemBanSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

@Config(name = LootrAPI.MODID)
public class ConfigManager extends ConfigManagerBase implements ConfigData {
  @ConfigEntry.Gui.Excluded
  private static Set<String> DECAY_MODS = null;
  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<LootTable>> DECAY_TABLES = null;
  @ConfigEntry.Gui.Excluded
  private static Set<String> REFRESH_MODS = null;
  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<LootTable>> REFRESH_TABLES = null;

  @Nullable
  @ConfigEntry.Gui.Excluded
  private static ResourceLocation PINNED_TEAM_RESOLVER = null;

  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<Level>> DIM_WHITELIST = null;
  @ConfigEntry.Gui.Excluded
  private static Set<String> MODID_DIM_WHITELIST = null;
  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<Level>> DIM_BLACKLIST = null;
  @ConfigEntry.Gui.Excluded
  private static Set<String> MODID_DIM_BLACKLIST = null;
  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<Level>> DECAY_DIMS = null;
  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<Level>> REFRESH_DIMS = null;
  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<LootTable>> LOOT_BLACKLIST = null;
  @ConfigEntry.Gui.Excluded
  private static Set<ResourceKey<LootTable>> LOOT_TABLE_FORCED_WHITELIST = null;
  @ConfigEntry.Gui.Excluded
  private static Set<String> LOOT_MODIDS = null;

  @ConfigEntry.Gui.CollapsibleObject
  public Debug debug = new Debug();
  @ConfigEntry.Gui.CollapsibleObject
  public Seed seed = new Seed();
  @ConfigEntry.Gui.CollapsibleObject
  public Conversion conversion = new Conversion();
  @ConfigEntry.Gui.CollapsibleObject
  public Breaking breaking = new Breaking();
  @ConfigEntry.Gui.CollapsibleObject
  public Lists lists = new Lists();
  @ConfigEntry.Gui.CollapsibleObject
  public Decay decay = new Decay();
  @ConfigEntry.Gui.CollapsibleObject
  public Refresh refresh = new Refresh();
  @ConfigEntry.Gui.CollapsibleObject
  public Notifications notifications = new Notifications();
  @ConfigEntry.Gui.CollapsibleObject
  public Client client = new Client();
  @ConfigEntry.Gui.CollapsibleObject
  public Team team = new Team();
  @ConfigEntry.Gui.CollapsibleObject
  public ItemBanOptions item_bans = new ItemBanOptions();
  @ConfigEntry.Gui.CollapsibleObject
  public IntegratedLootOptions integrated_loot = new IntegratedLootOptions();

  public static void reset() {
    LootrServiceRegistry.clearReplacements();
    MODID_DIM_WHITELIST = null;
    MODID_DIM_BLACKLIST = null;
    DIM_WHITELIST = null;
    DIM_BLACKLIST = null;
    LOOT_BLACKLIST = null;
    LOOT_TABLE_FORCED_WHITELIST = null;
    DECAY_MODS = null;
    DECAY_TABLES = null;
    DECAY_DIMS = null;
    LOOT_MODIDS = null;
    REFRESH_DIMS = null;
    REFRESH_MODS = null;
    REFRESH_TABLES = null;
    PINNED_TEAM_RESOLVER = null;
    LootrAPI.refreshSections();
  }

  // The holder is registered once and never replaced, unlike the configuration object it hands out: a load
  // (for example from the config screen) swaps that object, so only the holder is cached.
  @ConfigEntry.Gui.Excluded
  private static ConfigHolder<ConfigManager> HOLDER = null;

  public static ConfigManager get() {
    ConfigHolder<ConfigManager> holder = HOLDER;
    if (holder == null) {
      holder = AutoConfig.getConfigHolder(ConfigManager.class);
      HOLDER = holder;
    }
    return holder.getConfig();
  }

  public static Set<ResourceKey<Level>> getDimensionWhitelist() {
    if (DIM_WHITELIST == null) {
      DIM_WHITELIST = validateDimensions(get().lists.dimension_whitelist, "dimension_whitelist");
    }
    return DIM_WHITELIST;
  }

  public static Set<String> getDimensionModidWhitelist() {
    if (MODID_DIM_WHITELIST == null) {
      MODID_DIM_WHITELIST = validateStringList(get().lists.modid_dimension_whitelist, "modid_dimension_whitelist");
    }
    return MODID_DIM_WHITELIST;
  }

  public static Set<ResourceKey<Level>> getDimensionBlacklist() {
    if (DIM_BLACKLIST == null) {
      DIM_BLACKLIST = validateDimensions(get().lists.dimension_blacklist, "dimension_blacklist");
    }
    return DIM_BLACKLIST;
  }

  public static Set<String> getDimensionModidBlacklist() {
    if (MODID_DIM_BLACKLIST == null) {
      MODID_DIM_BLACKLIST = validateStringList(get().lists.modid_dimension_blacklist, "modid_dimension_blacklist");
    }
    return MODID_DIM_BLACKLIST;
  }

  public static Set<ResourceKey<Level>> getDecayDimensions() {
    if (DECAY_DIMS == null) {
      DECAY_DIMS = validateDimensions(get().decay.decay_dimensions, "decay_dimensions");
    }
    return DECAY_DIMS;
  }

  public static Set<ResourceKey<Level>> getRefreshDimensions() {
    if (REFRESH_DIMS == null) {
      REFRESH_DIMS = validateDimensions(get().refresh.refresh_dimensions, "refresh_dimensions");
    }
    return REFRESH_DIMS;
  }


  public static Set<ResourceKey<LootTable>> getLootTableForcedWhitelist() {
    if (LOOT_TABLE_FORCED_WHITELIST == null) {
      LOOT_TABLE_FORCED_WHITELIST = validateResourceKeyList(get().lists.loot_table_forced_whitelist, "loot_table_forced_whitelist", o -> ResourceKey.create(Registries.LOOT_TABLE, o));
    }
    return LOOT_TABLE_FORCED_WHITELIST;
  }

  public static Set<ResourceKey<LootTable>> getLootBlacklist() {
    if (LOOT_BLACKLIST == null) {
      LOOT_BLACKLIST = validateResourceKeyList(get().lists.loot_table_blacklist, "loot_blacklist", o -> ResourceKey.create(Registries.LOOT_TABLE, o));
      // Fixes for #79 and #74
      LOOT_BLACKLIST.addAll(LootrAPI.gatherProblematicLootTables());
      LOOT_BLACKLIST.removeAll(getLootTableForcedWhitelist());
    }
    return LOOT_BLACKLIST;
  }

  public static Set<String> getLootModidsBlacklist() {
    if (LOOT_MODIDS == null) {
      LOOT_MODIDS = validateStringList(get().lists.loot_modid_blacklist, "loot_modid_blacklist");
    }
    return LOOT_MODIDS;
  }

  public static boolean isBlacklisted(ResourceKey<LootTable> table) {
    if (getLootBlacklist().contains(table)) {
      return true;
    }

    return getLootModidsBlacklist().contains(table.location().getNamespace());
  }

  public static Set<ResourceKey<LootTable>> getDecayingTables() {
    if (DECAY_TABLES == null) {
      DECAY_TABLES = validateResourceKeyList(get().decay.decay_loot_tables, "decay_loot_tables", o -> ResourceKey.create(Registries.LOOT_TABLE, o));
    }
    return DECAY_TABLES;
  }

  public static Set<String> getDecayMods() {
    if (DECAY_MODS == null) {
      DECAY_MODS = validateStringList(get().decay.decay_modids, "decay_mods");
    }
    return DECAY_MODS;
  }

  public static Set<ResourceKey<LootTable>> getRefreshingTables() {
    if (REFRESH_TABLES == null) {
      REFRESH_TABLES = validateResourceKeyList(get().refresh.refresh_loot_tables, "refresh_tables", o -> ResourceKey.create(Registries.LOOT_TABLE, o));
    }
    return REFRESH_TABLES;
  }

  public static Set<String> getRefreshMods() {
    if (REFRESH_MODS == null) {
      REFRESH_MODS = validateStringList(get().refresh.refresh_modids, "refresh_modids");
    }
    return REFRESH_MODS;
  }

  public static ResourceLocation getPinnedTeamResolver() {
    return getPinnedTeamResolverInternal();
  }

  @NotNull
  public static ResourceLocation getPinnedTeamResolverInternal() {
    if (PINNED_TEAM_RESOLVER == null) {
      String team = get().team.pinned_team_resolver;
      if (!team.isEmpty()) {
        PINNED_TEAM_RESOLVER = ResourceLocation.tryParse(get().team.pinned_team_resolver);
      }
      if (PINNED_TEAM_RESOLVER == null) {
        PINNED_TEAM_RESOLVER = LootrAPI.DEFAULT_TEAM_RESOLVER;
      }
    }

    return PINNED_TEAM_RESOLVER;
  }

  public static boolean isDimensionBlocked(ResourceKey<Level> key) {
    if (!getDimensionModidWhitelist().isEmpty() && !getDimensionModidWhitelist().contains(key.location()
        .getNamespace()) || getDimensionModidBlacklist().contains(key.location().getNamespace())) {
      return true;
    }

    return (!getDimensionWhitelist().isEmpty() && !getDimensionWhitelist().contains(key)) || getDimensionBlacklist().contains(key);
  }

  public static boolean isDimensionDecaying(ResourceKey<Level> key) {
    return getDecayDimensions().contains(key);
  }

  public static boolean isDimensionRefreshing(ResourceKey<Level> key) {
    return getRefreshDimensions().contains(key);
  }

  public static boolean isDecaying(ILootrInfoProvider tile) {
    if (get().decay.decay_all) {
      return true;
    }
    if (tile.getInfoLootTable() != null) {
      if (getDecayingTables().contains(tile.getInfoLootTable())) {
        return true;
      }
      if (getDecayMods().contains(tile.getInfoLootTable().location().getNamespace())) {
        return true;
      }
    }
    // Structures are known to a server level only; the level can be missing (unknown dimension) or a client level.
    if (tile.getInfoLevel() instanceof ServerLevel level && LootrAPI.isTaggedStructurePresent(level, new ChunkPos(tile.getInfoPos()), LootrTags.Structure.DECAY_STRUCTURES, tile.getInfoPos())) {
      return true;
    }
    return isDimensionDecaying(tile.getInfoDimension());
  }

  public static boolean isRefreshing(ILootrInfoProvider tile) {
    if (get().refresh.refresh_all) {
      return true;
    }
    if (tile.getInfoLootTable() != null) {
      if (getRefreshingTables().contains(tile.getInfoLootTable())) {
        return true;
      }
      if (getRefreshMods().contains(tile.getInfoLootTable().location().getNamespace())) {
        return true;
      }
    }
    if (tile.getInfoLevel() instanceof ServerLevel level && LootrAPI.isTaggedStructurePresent(level, new ChunkPos(tile.getInfoPos()), LootrTags.Structure.REFRESH_STRUCTURES, tile.getInfoPos())) {
      return true;
    }
    return isDimensionRefreshing(tile.getInfoDimension());
  }


  public static boolean shouldNotify(int remaining) {
    int delay = get().client.notification_delay;
    return !get().client.disable_notifications && (delay == -1 || remaining <= delay);
  }

  public static boolean shouldPerformPiecewiseCheck() {
    return get().conversion.perform_piecewise_check;
  }

  public static boolean isVanillaTextures() {
    return get().client.vanilla_textures;
  }

  public static SyncedConfig getConfigForSync () {
    return get().configSync();
  }

  // Snapshot of the `item_bans` section for common code (see ItemBans#applySettings). Not static on purpose:
  // while a load listener runs, `get()` still returns the previous configuration.
  public ItemBanSettings itemBanSettings() {
    ItemBanOptions options = item_bans;
    if (options == null) {
      return ItemBanSettings.DEFAULT;
    }
    return new ItemBanSettings(options.respect_item_obliterator, options.filter_all_loot, options.scrub_saved_inventories, options.extra_banned_items);
  }

  // Snapshot of the `integrated_loot` section for common code (see IntegratedLoot#applySettings). A configuration
  // file written before the section existed keeps the defaults of the field initialiser.
  public IntegratedLootSettings integratedLootSettings() {
    IntegratedLootOptions options = integrated_loot;
    if (options == null) {
      return IntegratedLootSettings.DEFAULT;
    }
    return new IntegratedLootSettings(options.enabled, options.modded_item_weight, options.skip_existing_items, options.skip_map_items, options.max_extra_stacks, options.debug_log);
  }

  private SyncedConfig configSync () {
    return new SyncedConfig(
        conversion.bypass_spawn_protection,
        breaking.disable_break,
        breaking.enable_break,
        team.team_loot,
        getPinnedTeamResolverInternal()
    );
  }

  public static class Debug {
    public boolean report_unresolved_tables = true;
  }

  public static class Seed {
    public boolean randomize_seed = true;
  }

  public static class Conversion {
    public boolean disable = false;
    public boolean convert_elytras_to_chests = false;
    public boolean convert_elytras_to_item_frames = true;
    @Deprecated
    public boolean convert_mineshafts = true;
    public boolean convert_structure_item_frames = true;
    public boolean world_border = false;
    public boolean perform_piecewise_check = true;
    public boolean bypass_spawn_protection = true;
    public boolean skip_logging_no_loot_table_at_generation = true;
    public int tickDelay = 20;
  }

  public static class Breaking {
    public boolean disable_break = false;
    public boolean enable_break = false;
    public boolean enable_fake_player_break = false;
    public boolean power_comparators = true;
    public boolean blast_resistant = false;
    public boolean blast_immune = false;
    public boolean trapped_custom = false;
    public boolean should_drop_player_loot = false;
    public boolean break_to_drop_loot = false;
    public boolean brushables_self_support = false;
    public boolean item_frames_self_support = false;
  }

  public static class Lists {
    public List<String> dimension_whitelist = List.of();
    public List<String> dimension_blacklist = List.of();
    public List<String> loot_table_blacklist = List.of();
    public List<String> loot_modid_blacklist = List.of();
    public List<String> modid_dimension_whitelist = List.of();
    public List<String> modid_dimension_blacklist = List.of();
    public List<String> loot_table_forced_whitelist = List.of();
  }

  public static class Decay {
    public int decay_value = 6000;
    public boolean decay_all = false;
    @ConfigEntry.Gui.RequiresRestart
    public boolean perform_tick_decay = true;
    @ConfigEntry.Gui.RequiresRestart
    public boolean start_tick_decay = false;
    public List<String> decay_modids = List.of();
    public List<String> decay_loot_tables = List.of();
    public List<String> decay_dimensions = List.of();
    public boolean replace_when_decayed = false;
  }

  public static class Refresh {
    public int refresh_value = 24000;
    public boolean refresh_all = false;
    @ConfigEntry.Gui.RequiresRestart
    public boolean perform_tick_refresh = true;
    @ConfigEntry.Gui.RequiresRestart
    public boolean start_tick_refresh = true;
    public List<String> refresh_modids = List.of();
    public List<String> refresh_loot_tables = List.of();
    public List<String> refresh_dimensions = List.of();
  }

  public static class Notifications {
    public boolean disable_message_styles = false;
  }

  public static class Client {
    public boolean vanilla_textures = false;
    public boolean unopened_particles = true;
    public boolean refresh_particles = true;
    public boolean decay_particles = true;
    public boolean display_toasts = true;
    public int notification_delay = 30 * 20;
    public boolean disable_notifications = false;
  }

  public static class Team {
    public boolean team_loot = false;
    public String pinned_team_resolver = "";
  }

  public static class ItemBanOptions {
    // Treat items disabled by the mod Item Obliterator (if installed) as banned.
    public boolean respect_item_obliterator = true;
    // Remove banned items from every loot table roll; if false, only from Lootr's own containers.
    public boolean filter_all_loot = true;
    // Remove banned items from already saved per-player inventories when they are accessed (permanent).
    public boolean scrub_saved_inventories = true;
    // Additional banned items: "namespace:path" item ids or "#namespace:path" item tags.
    public List<String> extra_banned_items = List.of();
  }

  public static class IntegratedLootOptions {
    // Add a few stacks from other loot tables to the container loot tables selected by data pack rules.
    public boolean enabled = true;
    // Draw weight of items from other mods; vanilla items weigh 1. Minimum 1.
    public int modded_item_weight = 2;
    // Do not add an item the container already yields.
    public boolean skip_existing_items = true;
    // Do not create explorer maps (structure searches) for added loot.
    public boolean skip_map_items = true;
    // Upper limit of added stacks per container, whatever a rule asks for.
    @ConfigEntry.BoundedDiscrete(min = IntegratedLootSettings.MIN_EXTRA_STACKS, max = IntegratedLootSettings.MAX_EXTRA_STACKS)
    public int max_extra_stacks = 4;
    // Log one line per extended loot roll.
    public boolean debug_log = false;
    // Set once the settings of `lootintegrations.json` have been taken over; set to false to take them over again.
    public boolean imported_loot_integrations_config = false;
  }
}
