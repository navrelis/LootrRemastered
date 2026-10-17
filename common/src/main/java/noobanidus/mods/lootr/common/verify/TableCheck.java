package noobanidus.mods.lootr.common.verify;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import noobanidus.mods.lootr.common.verify.TableAnalyzer.Facts;
import noobanidus.mods.lootr.common.verify.TableAnalyzer.ItemLookup;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The static judgement of `/lootr verify` for one loot table: can it produce an item at all? It is the same analysis
 * ({@link TableAnalyzer}) with the same reading of nested tables, so a table is reported here exactly when the
 * verification reports it as `cannot_produce`. Conditions and functions are not evaluated.
 * <br />
 * Cheap enough for a command: a few tables are encoded, nothing is cached beyond one call.
 */
public final class TableCheck {
  private TableCheck() {
  }

  /**
   * @param registries the registries the loot tables are loaded in (`server.reloadableRegistries().get()`).
   * @return why the table cannot produce an item (one of `no_pools`, `only_empty_entries`, `only_banned_items`,
   * `only_unproductive_references` and `no_producing_entry`); nothing if it can, or if it is not loaded or cannot be
   * read, as nothing is known against it then.
   */
  public static Optional<String> cannotProduceReason(RegistryAccess registries, ResourceLocation id) {
    Optional<Registry<LootTable>> registry = registries.registry(Registries.LOOT_TABLE);
    if (registry.isEmpty()) {
      return Optional.empty();
    }
    Analysis analysis = new Analysis(registry.get(), registries.createSerializationContext(JsonOps.INSTANCE));
    Facts root = analysis.facts(id);
    if (root == null || analysis.produces(id, new HashSet<>())) {
      return Optional.empty();
    }
    return Optional.of(root.reason());
  }

  private static final class Analysis {
    private final Registry<LootTable> tables;
    private final RegistryOps<JsonElement> ops;
    private final ItemLookup items = new ItemLookup(ItemBans.isActive() ? ItemBans::isBanned : null);
    // A null value: the table could not be read.
    private final Map<ResourceLocation, Facts> facts = new HashMap<>();

    private Analysis(Registry<LootTable> tables, RegistryOps<JsonElement> ops) {
      this.tables = tables;
      this.ops = ops;
    }

    private Facts facts(ResourceLocation id) {
      if (facts.containsKey(id)) {
        return facts.get(id);
      }
      Facts result = null;
      Optional<LootTable> table = tables.getOptional(id);
      if (table.isPresent()) {
        try {
          JsonElement json = LootTable.DIRECT_CODEC.encodeStart(ops, table.get()).result().orElse(null);
          result = json == null ? null : TableAnalyzer.analyse(json, items);
        } catch (RuntimeException e) {
          result = null;
        }
      }
      facts.put(id, result);
      return result;
    }

    // A table produces if one of its entries does, or one of the tables it refers to. A table that is not loaded
    // produces nothing; one that could not be read counts as producing, as in the verification.
    private boolean produces(ResourceLocation id, Set<ResourceLocation> visiting) {
      if (!tables.containsKey(id)) {
        return false;
      }
      Facts tableFacts = facts(id);
      if (tableFacts == null || tableFacts.producesDirectly) {
        return true;
      }
      if (!visiting.add(id)) {
        return false;
      }
      for (String nested : tableFacts.nested) {
        ResourceLocation target = ResourceLocation.tryParse(nested);
        if (target != null && produces(target, visiting)) {
          return true;
        }
      }
      return false;
    }
  }
}
