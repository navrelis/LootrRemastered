package noobanidus.mods.lootr.common.verify;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot;
import noobanidus.mods.lootr.common.itemban.ItemBanSource;
import noobanidus.mods.lootr.common.itemban.ItemBans;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a verification run works on, taken from the server in one go on the server thread so that the run sees one
 * consistent data load even if data packs are reloaded while it works. Everything in here is immutable or safe to
 * read from any thread.
 *
 * @param registries        the reloadable registries: loot tables.
 * @param worldgen          the registries of the world: structures and template pools.
 * @param tables            the loaded loot tables by id, ordered by id.
 * @param integratedEnabled whether integrated loot is switched on in the configuration.
 */
record VerifyContext(
    MinecraftServer server,
    VerifyOptions options,
    Instant startedAt,
    ResourceManager resources,
    RegistryAccess registries,
    RegistryAccess worldgen,
    SortedMap<ResourceLocation, LootTable> tables,
    IntegratedLootSnapshot snapshot,
    boolean integratedEnabled,
    boolean bansActive,
    boolean filterAllLoot,
    List<String> banSources,
    FeatureFlagSet features) {

  /**
   * Must be called on the server thread.
   */
  static VerifyContext capture(MinecraftServer server, VerifyOptions options) {
    RegistryAccess.Frozen registries = server.reloadableRegistries().get();
    SortedMap<ResourceLocation, LootTable> tables = new TreeMap<>();
    registries.registry(Registries.LOOT_TABLE).ifPresent(registry -> {
      for (Holder.Reference<LootTable> holder : registry.holders().toList()) {
        tables.put(holder.key().location(), holder.value());
      }
    });
    List<String> banSources = new ArrayList<>();
    for (ItemBanSource source : ItemBans.getSources()) {
      String name;
      try {
        name = String.valueOf(source.name());
      } catch (RuntimeException e) {
        name = source.getClass().getName();
      }
      banSources.add(name);
    }
    Collections.sort(banSources);
    return new VerifyContext(server, options, options.clock().instant(), server.getResourceManager(), registries, server.registryAccess(), Collections.unmodifiableSortedMap(tables), IntegratedLoot.snapshot(), IntegratedLoot.getSettings().enabled(), ItemBans.isActive(), ItemBans.isFilteringAllLoot(), List.copyOf(banSources), server.getWorldData().enabledFeatures());
  }
}
