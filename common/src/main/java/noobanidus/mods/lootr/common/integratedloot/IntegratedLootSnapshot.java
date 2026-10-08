package noobanidus.mods.lootr.common.integratedloot;

import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.FileProblem;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.PoolDefinition;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.Rolls;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.RuleEntry;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.RuleFile;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.SourceDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The result of resolving the pool and rule files against the loot tables of one data load: which loot table is
 * extended from which pool, plus everything worth knowing about files, patterns and sources that did not work out.
 * Immutable and safe to read from any thread. Get the current one from {@link IntegratedLoot#snapshot()}.
 */
public final class IntegratedLootSnapshot {
  public static final IntegratedLootSnapshot EMPTY = new IntegratedLootSnapshot(0, Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), new Reference2ObjectOpenHashMap<>());

  private final int tableCount;
  private final Map<ResourceLocation, Pool> pools;
  private final List<ResourceLocation> unusablePools;
  private final List<DroppedSource> droppedSources;
  private final List<RuleFileInfo> ruleFiles;
  private final List<RuleFileInfo> activeRuleFiles;
  private final List<RuleFileInfo> inactiveRuleFiles;
  private final List<UnmatchedPattern> unmatchedPatterns;
  private final List<IneligibleMatch> ineligibleMatches;
  private final List<FileProblem> malformedFiles;
  private final Map<ResourceKey<LootTable>, Decision> decisions;
  private final int extensionCount;
  private final int exclusionCount;
  // Keyed by the loot table objects of the data load this snapshot was resolved from; never changed after construction.
  final Reference2ObjectMap<LootTable, Target> targets;

  private IntegratedLootSnapshot(int tableCount, Map<ResourceLocation, Pool> pools, List<ResourceLocation> unusablePools, List<DroppedSource> droppedSources, List<RuleFileInfo> ruleFiles, List<UnmatchedPattern> unmatchedPatterns, List<IneligibleMatch> ineligibleMatches, List<FileProblem> malformedFiles, Map<ResourceKey<LootTable>, Decision> decisions, Reference2ObjectMap<LootTable, Target> targets) {
    this.tableCount = tableCount;
    this.pools = Collections.unmodifiableMap(pools);
    this.unusablePools = List.copyOf(unusablePools);
    this.droppedSources = List.copyOf(droppedSources);
    this.ruleFiles = List.copyOf(ruleFiles);
    this.activeRuleFiles = ruleFiles.stream().filter(RuleFileInfo::active).toList();
    this.inactiveRuleFiles = ruleFiles.stream().filter(file -> !file.active()).toList();
    this.unmatchedPatterns = List.copyOf(unmatchedPatterns);
    this.ineligibleMatches = List.copyOf(ineligibleMatches);
    this.malformedFiles = List.copyOf(malformedFiles);
    this.decisions = Collections.unmodifiableMap(decisions);
    this.extensionCount = (int) decisions.values().stream().filter(Decision::active).count();
    this.exclusionCount = (int) decisions.values().stream().filter(Decision::exclusion).count();
    this.targets = targets;
  }

  // ----- read-only API -----

  /**
   * @return true if no pool or rule file was found at all (also before the first resolution and after the
   * server stopped).
   */
  public boolean isEmpty() {
    return pools.isEmpty() && unusablePools.isEmpty() && ruleFiles.isEmpty() && malformedFiles.isEmpty();
  }

  /**
   * @return how many loot tables were loaded when this snapshot was resolved.
   */
  public int tableCount() {
    return tableCount;
  }

  /**
   * @return the usable pools (at least one existing source), ordered by id.
   */
  public Collection<Pool> pools() {
    return pools.values();
  }

  public Optional<Pool> pool(ResourceLocation id) {
    return Optional.ofNullable(pools.get(id));
  }

  /**
   * @return the ids of the pools that have no existing source table.
   */
  public List<ResourceLocation> unusablePools() {
    return unusablePools;
  }

  /**
   * @return the pool sources that were dropped because their loot table is not loaded.
   */
  public List<DroppedSource> droppedSources() {
    return droppedSources;
  }

  /**
   * @return all rule files in the order they are consulted: priority descending, then id ascending.
   */
  public List<RuleFileInfo> ruleFiles() {
    return ruleFiles;
  }

  public List<RuleFileInfo> activeRuleFiles() {
    return activeRuleFiles;
  }

  /**
   * @return the rule files that are ignored because a required mod is missing, see {@link RuleFileInfo#missingMods()}.
   */
  public List<RuleFileInfo> inactiveRuleFiles() {
    return inactiveRuleFiles;
  }

  /**
   * @return the patterns of active rule files that match none of the loaded loot tables.
   */
  public List<UnmatchedPattern> unmatchedPatterns() {
    return unmatchedPatterns;
  }

  /**
   * @return the loot tables that an "extend" entry decided on but whose type is not a container type.
   */
  public List<IneligibleMatch> ineligibleMatches() {
    return ineligibleMatches;
  }

  /**
   * @return the pool and rule files that were skipped because they are malformed.
   */
  public List<FileProblem> malformedFiles() {
    return malformedFiles;
  }

  /**
   * @return every decision by loot table, ordered by loot table id.
   */
  public Map<ResourceKey<LootTable>, Decision> decisions() {
    return decisions;
  }

  /**
   * @return the decision for the loot table, or nothing if no entry of an active rule file matches it (or if
   * it is not eligible, see {@link #ineligibleMatches()}).
   */
  public Optional<Decision> decisionFor(ResourceKey<LootTable> table) {
    return Optional.ofNullable(decisions.get(table));
  }

  /**
   * @return how many loot tables are extended ({@link Decision#active()}).
   */
  public int extensionCount() {
    return extensionCount;
  }

  /**
   * @return how many loot tables are excluded by an `exclude` entry.
   */
  public int exclusionCount() {
    return exclusionCount;
  }

  // ----- data -----

  public record Source(ResourceKey<LootTable> table, int weight) {
  }

  /**
   * A pool with its existing sources only.
   */
  public record Pool(ResourceLocation id, List<Source> sources, long totalWeight) {
    public Pool {
      sources = List.copyOf(sources);
    }
  }

  public record DroppedSource(ResourceLocation pool, ResourceLocation table) {
  }

  /**
   * @param missingMods the required mods that are not loaded; the file is active exactly when this is empty.
   */
  public record RuleFileInfo(ResourceLocation id, int priority, int entries, List<String> missingMods) {
    public RuleFileInfo {
      missingMods = List.copyOf(missingMods);
    }

    public boolean active() {
      return missingMods.isEmpty();
    }
  }

  public record UnmatchedPattern(ResourceLocation ruleFile, int entryIndex, String pattern) {
  }

  /**
   * @param type the loot context param set of the table, e.g. `minecraft:entity`.
   */
  public record IneligibleMatch(ResourceKey<LootTable> table, String type, ResourceLocation ruleFile, int entryIndex, String pattern) {
  }

  /**
   * What the first matching rule entry says about a loot table.
   *
   * @param ruleFile   the rule file that decided.
   * @param entryIndex the index of the deciding entry in that file, starting at 0.
   * @param pattern    the pattern of that entry that matched.
   * @param exclusion  true if the entry is an `exclude` entry: the table is never extended.
   * @param poolId     the pool the entry names (empty for an exclusion).
   * @param pool       the resolved pool; empty for an exclusion and when {@link #problem()} is present.
   * @param rolls      how many stacks the entry asks for.
   * @param problem    why an "extend" decision has no effect (missing or unusable pool).
   */
  public record Decision(ResourceKey<LootTable> table, ResourceLocation ruleFile, int entryIndex, String pattern, boolean exclusion, Optional<ResourceLocation> poolId, Optional<Pool> pool, Rolls rolls, Optional<String> problem) {
    /**
     * @return true if rolls of the table are really extended.
     */
    public boolean active() {
      return !exclusion && pool.isPresent();
    }
  }

  record Target(Decision decision, LootTable[] sources, int[] weights, long totalWeight) {
  }

  // ----- resolution -----

  private record ResolvedPool(Pool pool, LootTable[] tables, int[] weights) {
  }

  private record OrderedRuleFile(ResourceLocation id, RuleFile file) {
  }

  private static boolean isEligible(LootContextParamSet type) {
    return type == LootContextParamSets.CHEST || type == LootContextParamSets.VAULT || type == LootTable.DEFAULT_PARAM_SET;
  }

  private static String typeName(LootContextParamSet type) {
    return LootContextParamSets.CODEC.encodeStart(JsonOps.INSTANCE, type).result().map(json -> json.isJsonPrimitive() ? json.getAsString() : json.toString()).orElse("unknown");
  }

  /**
   * Runs once per data load. For every loot table the active rule files are consulted by priority descending,
   * then id ascending, entries in file order; the first entry with a matching pattern decides.
   */
  static IntegratedLootSnapshot resolve(IntegratedLootDefinitions definitions, Registry<LootTable> registry, Predicate<String> modLoaded) {
    Map<ResourceLocation, ResolvedPool> resolvedPools = new HashMap<>();
    Map<ResourceLocation, Pool> pools = new TreeMap<>();
    List<ResourceLocation> unusablePools = new ArrayList<>();
    List<DroppedSource> droppedSources = new ArrayList<>();
    for (Map.Entry<ResourceLocation, PoolDefinition> entry : definitions.pools().entrySet()) {
      // Sources naming the same table are merged, so that "another source" always means another table.
      Map<ResourceKey<LootTable>, Integer> weights = new LinkedHashMap<>();
      Map<ResourceKey<LootTable>, LootTable> tables = new HashMap<>();
      for (SourceDefinition source : entry.getValue().sources()) {
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, source.table());
        LootTable table = registry.get(key);
        if (table == null) {
          droppedSources.add(new DroppedSource(entry.getKey(), source.table()));
          continue;
        }
        tables.put(key, table);
        weights.merge(key, source.weight(), (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
      }
      if (weights.isEmpty()) {
        unusablePools.add(entry.getKey());
        continue;
      }
      List<Source> sources = new ArrayList<>();
      LootTable[] sourceTables = new LootTable[weights.size()];
      int[] sourceWeights = new int[weights.size()];
      long total = 0;
      int index = 0;
      for (Map.Entry<ResourceKey<LootTable>, Integer> source : weights.entrySet()) {
        sources.add(new Source(source.getKey(), source.getValue()));
        sourceTables[index] = tables.get(source.getKey());
        sourceWeights[index] = source.getValue();
        total += source.getValue();
        index++;
      }
      Pool pool = new Pool(entry.getKey(), sources, total);
      pools.put(entry.getKey(), pool);
      resolvedPools.put(entry.getKey(), new ResolvedPool(pool, sourceTables, sourceWeights));
    }

    List<OrderedRuleFile> ordered = new ArrayList<>();
    definitions.ruleFiles().forEach((id, file) -> ordered.add(new OrderedRuleFile(id, file)));
    ordered.sort(Comparator.<OrderedRuleFile>comparingInt(file -> file.file().priority()).reversed().thenComparing(OrderedRuleFile::id));
    List<RuleFileInfo> ruleFiles = new ArrayList<>();
    List<OrderedRuleFile> active = new ArrayList<>();
    for (OrderedRuleFile file : ordered) {
      List<String> missing = file.file().requiredMods().stream().filter(mod -> !modLoaded.test(mod)).distinct().toList();
      ruleFiles.add(new RuleFileInfo(file.id(), file.file().priority(), file.file().entries().size(), missing));
      if (missing.isEmpty()) {
        active.add(file);
      }
    }

    List<Holder.Reference<LootTable>> holders = new ArrayList<>(registry.holders().toList());
    holders.sort(Comparator.comparing(holder -> holder.key().location()));

    // matched[file][entry][pattern]
    boolean[][][] matched = new boolean[active.size()][][];
    for (int f = 0; f < active.size(); f++) {
      List<RuleEntry> entries = active.get(f).file().entries();
      matched[f] = new boolean[entries.size()][];
      for (int e = 0; e < entries.size(); e++) {
        matched[f][e] = new boolean[entries.get(e).tables().size()];
      }
    }

    Map<ResourceKey<LootTable>, Decision> decisions = new LinkedHashMap<>();
    List<IneligibleMatch> ineligibleMatches = new ArrayList<>();
    Reference2ObjectMap<LootTable, Target> targets = new Reference2ObjectOpenHashMap<>();
    for (Holder.Reference<LootTable> holder : holders) {
      ResourceLocation id = holder.key().location();
      search:
      for (int f = 0; f < active.size(); f++) {
        OrderedRuleFile file = active.get(f);
        List<RuleEntry> entries = file.file().entries();
        for (int e = 0; e < entries.size(); e++) {
          RuleEntry entry = entries.get(e);
          List<TablePattern> patterns = entry.tables();
          for (int p = 0; p < patterns.size(); p++) {
            if (!patterns.get(p).matches(id)) {
              continue;
            }
            matched[f][e][p] = true;
            decide(holder, file.id(), e, patterns.get(p).toString(), entry, resolvedPools, definitions, decisions, ineligibleMatches, targets);
            break search;
          }
        }
      }
    }

    // A pattern can also be without effect because earlier entries decide all its tables; that is not a typo
    // and not reported. Only patterns that fit no loaded table at all are.
    List<UnmatchedPattern> unmatchedPatterns = new ArrayList<>();
    for (int f = 0; f < active.size(); f++) {
      List<RuleEntry> entries = active.get(f).file().entries();
      for (int e = 0; e < entries.size(); e++) {
        List<TablePattern> patterns = entries.get(e).tables();
        for (int p = 0; p < patterns.size(); p++) {
          if (matched[f][e][p]) {
            continue;
          }
          TablePattern pattern = patterns.get(p);
          if (holders.stream().noneMatch(holder -> pattern.matches(holder.key().location()))) {
            unmatchedPatterns.add(new UnmatchedPattern(active.get(f).id(), e, pattern.toString()));
          }
        }
      }
    }

    return new IntegratedLootSnapshot(holders.size(), pools, unusablePools, droppedSources, ruleFiles, unmatchedPatterns, ineligibleMatches, definitions.problems(), decisions, targets);
  }

  private static void decide(Holder.Reference<LootTable> holder, ResourceLocation ruleFile, int entryIndex, String pattern, RuleEntry entry, Map<ResourceLocation, ResolvedPool> resolvedPools, IntegratedLootDefinitions definitions, Map<ResourceKey<LootTable>, Decision> decisions, List<IneligibleMatch> ineligibleMatches, Reference2ObjectMap<LootTable, Target> targets) {
    ResourceKey<LootTable> key = holder.key();
    LootTable table = holder.value();
    if (!isEligible(table.getParamSet())) {
      // Tables that are no container loot are never extended, so excluding them is not worth recording either.
      if (!entry.exclude()) {
        ineligibleMatches.add(new IneligibleMatch(key, typeName(table.getParamSet()), ruleFile, entryIndex, pattern));
      }
      return;
    }
    if (entry.exclude()) {
      decisions.put(key, new Decision(key, ruleFile, entryIndex, pattern, true, Optional.empty(), Optional.empty(), entry.rolls(), Optional.empty()));
      return;
    }
    ResourceLocation poolId = entry.pool().orElseThrow();
    ResolvedPool pool = resolvedPools.get(poolId);
    if (pool == null) {
      String problem = definitions.pools().containsKey(poolId) ? "pool " + poolId + " has no existing source table" : "pool " + poolId + " does not exist";
      decisions.put(key, new Decision(key, ruleFile, entryIndex, pattern, false, Optional.of(poolId), Optional.empty(), entry.rolls(), Optional.of(problem)));
      return;
    }
    Decision decision = new Decision(key, ruleFile, entryIndex, pattern, false, Optional.of(poolId), Optional.of(pool.pool()), entry.rolls(), Optional.empty());
    decisions.put(key, decision);
    // Should one table object be registered under two ids, the first id (in id order) decides for it.
    targets.putIfAbsent(table, new Target(decision, pool.tables(), pool.weights(), pool.pool().totalWeight()));
  }
}
