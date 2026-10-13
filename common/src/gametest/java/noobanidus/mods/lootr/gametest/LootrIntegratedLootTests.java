package noobanidus.mods.lootr.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.api.registry.LootrRegistry;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.FileKind;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.FileProblem;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.Rolls;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSettings;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Decision;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.DroppedSource;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.IneligibleMatch;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Pool;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.RuleFileInfo;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.UnmatchedPattern;
import noobanidus.mods.lootr.common.integratedloot.LootIntegrationsImport;
import noobanidus.mods.lootr.common.integratedloot.TablePattern;
import noobanidus.mods.lootr.common.itemban.ItemBanSettings;
import noobanidus.mods.lootr.common.itemban.ItemBanSource;
import noobanidus.mods.lootr.common.itemban.ItemBans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static noobanidus.mods.lootr.gametest.LootrTestSupport.EMPTY_STRUCTURE;

/**
 * Tests for integrated loot. The pools, rules and loot tables are in the test data pack
 * (`data/lootr_gametest/lootr/integrated_loot` and `data/lootr_gametest/loot_table/integrated`); every test table
 * yields fixed items, so the assertions are exact. The extra rule files for the namespace gate are in
 * `data/lootr_gametest_ns/lootr/integrated_loot/rule`. The pools and rules that ship with the mod (namespace `lootr`)
 * are loaded as well; the tests of the fixture data look at the namespace `lootr_gametest` only.
 * <br />
 * Settings and ban sources are global: a test changes them only inside one synchronous block on the server
 * thread and restores them in a `finally`. The runtime counters are compared as differences inside such a block.
 */
public class LootrIntegratedLootTests {
  private static final String BATCH = "lootr_integrated_loot";
  private static final String RELOAD_BATCH = "lootr_integrated_loot_reload";
  private static final BlockPos CHEST_POS = new BlockPos(0, 1, 0);
  private static final int CONVERSION_TIMEOUT_TICKS = 200;
  private static final int SEEDS = 30;
  // The namespace of the test data pack, of its extra rule files for the namespace gate, and of the files that ship with the mod.
  private static final String FIXTURE_NAMESPACE = "lootr_gametest";
  private static final String GATE_NAMESPACE = "lootr_gametest_ns";
  private static final String BUILT_IN_NAMESPACE = "lootr";

  // What the test tables yield: targets A, the usual source B, C and D, the second-level source E.
  private static final Item A = Items.STICK;
  private static final Item B = Items.PAPER;
  private static final Item C = Items.FEATHER;
  private static final Item D = Items.FLINT;
  private static final Item E = Items.BONE;
  private static final Set<Item> BCD = Set.of(B, C, D);

  // a. A rule adds stacks: the target yields A, the pool's source B, C and D, the rule asks for two.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void ruleAddsStacksToFillAndListRolls(GameTestHelper helper) {
    LootTable table = table(helper, "target_a");
    LootParams params = params(helper);
    for (long seed = 1; seed <= SEEDS; seed++) {
      SimpleContainer container = new SimpleContainer(27);
      table.fill(container, params, seed);
      assertTargetPlusTwo(helper, stacks(container), "fill with seed " + seed);
      assertTargetPlusTwo(helper, table.getRandomItems(params, seed), "getRandomItems(params, " + seed + ")");
      assertTargetPlusTwo(helper, table.getRandomItems(params, RandomSource.create(seed)), "getRandomItems(params, random " + seed + ")");
    }
    assertTargetPlusTwo(helper, table.getRandomItems(params), "getRandomItems(params)");

    // The Consumer overloads (mob drops, nested table references) are not extended.
    List<ItemStack> raw = new ArrayList<>();
    table.getRandomItemsRaw(params, raw::add);
    assertItems(helper, raw, "getRandomItemsRaw", A);
    List<ItemStack> consumed = new ArrayList<>();
    table.getRandomItems(params, 1L, consumed::add);
    assertItems(helper, consumed, "getRandomItems(params, seed, consumer)", A);
    helper.succeed();
  }

  // b. Capacity: never more stacks than empty slots, and the original stack is always there.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void extensionRespectsEmptySlots(GameTestHelper helper) {
    LootTable table = table(helper, "target_cap");
    LootParams params = params(helper);
    for (long seed = 1; seed <= SEEDS; seed++) {
      IntegratedLoot.Stats before = IntegratedLoot.stats();
      SimpleContainer oneFree = new SimpleContainer(3);
      oneFree.setItem(0, new ItemStack(Items.DIRT));
      oneFree.setItem(2, new ItemStack(Items.DIRT));
      table.fill(oneFree, params, seed);
      helper.assertTrue(oneFree.getItem(0).is(Items.DIRT) && oneFree.getItem(2).is(Items.DIRT), "An occupied slot was overwritten");
      helper.assertTrue(oneFree.getItem(1).is(A), "The original stack is missing from the only empty slot");
      helper.assertTrue(IntegratedLoot.stats().stacksAdded() == before.stacksAdded(), "A stack was added although the container had no room for it");

      SimpleContainer twoFree = new SimpleContainer(3);
      twoFree.setItem(1, new ItemStack(Items.DIRT));
      table.fill(twoFree, params, seed);
      List<ItemStack> filled = stacks(twoFree);
      helper.assertTrue(filled.size() == 3 && count(filled, Items.DIRT) == 1 && count(filled, A) == 1, "Expected dirt, the original stack and one extra but got " + describe(filled));
      helper.assertTrue(filled.stream().filter(stack -> BCD.contains(stack.getItem())).count() == 1, "Expected exactly one extra stack but got " + describe(filled));
      helper.assertTrue(IntegratedLoot.stats().stacksAdded() == before.stacksAdded() + 1, "The counter does not show exactly one added stack");

      SimpleContainer roomy = new SimpleContainer(27);
      table.fill(roomy, params, seed);
      assertItems(helper, stacks(roomy), "fill of an empty container with seed " + seed, A, B, C, D);
    }
    helper.succeed();
  }

  // c. No re-entrance: the source table has a rule of its own, but rolled as a source it is not extended.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void sourceTableIsNotExtendedWhileRolledAsSource(GameTestHelper helper) {
    LootTable target = table(helper, "target_reentrant");
    LootTable source = table(helper, "source_nested");
    LootParams params = params(helper);
    for (long seed = 1; seed <= SEEDS; seed++) {
      // On its own the source is a target: it gets E from its pool.
      assertItems(helper, source.getRandomItems(params, seed), "the source rolled on its own", B, C, D, E);

      IntegratedLoot.Stats before = IntegratedLoot.stats();
      assertItems(helper, target.getRandomItems(params, seed), "the target with seed " + seed, A, B, C, D);
      IntegratedLoot.Stats after = IntegratedLoot.stats();
      helper.assertTrue(after.extensions() == before.extensions() + 1, "Expected exactly one extension but got " + (after.extensions() - before.extensions()));
      helper.assertTrue(after.sourceRolls() == before.sourceRolls() + 1, "Expected exactly one source roll but got " + (after.sourceRolls() - before.sourceRolls()));
      helper.assertTrue(after.stacksAdded() == before.stacksAdded() + 3, "Expected exactly three added stacks but got " + (after.stacksAdded() - before.stacksAdded()));
    }
    helper.succeed();
  }

  // d. A banned item is never added, whether every loot roll is filtered or only Lootr's own fills.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void bannedItemIsNeverAdded(GameTestHelper helper) {
    LootTable table = table(helper, "target_a");
    LootParams params = params(helper);
    ItemBanSource source = new SingleItemBan("gametest:integrated", B);
    ItemBanSettings original = ItemBans.getSettings();
    ItemBans.register(source);
    try {
      for (boolean filterAll : new boolean[]{true, false}) {
        ItemBans.applySettings(original.withFilterAllLoot(filterAll));
        helper.assertTrue(ItemBans.isFilteringAllLoot() == filterAll, "filter_all_loot was not applied");
        for (long seed = 1; seed <= SEEDS; seed++) {
          assertItems(helper, table.getRandomItems(params, seed), "roll with filter_all_loot " + filterAll, A, C, D);
          SimpleContainer container = new SimpleContainer(27);
          table.fill(container, params, seed);
          assertItems(helper, stacks(container), "fill with filter_all_loot " + filterAll, A, C, D);
        }
      }
    } finally {
      ItemBans.unregister(source);
      ItemBans.applySettings(original);
    }
    helper.succeed();
  }

  // Items in the tag lootr:integrated_loot/excluded are never added (the source yields a barrier and a feather).
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void taggedItemIsNeverAdded(GameTestHelper helper) {
    LootParams params = params(helper);
    helper.assertTrue(new ItemStack(Items.BARRIER).is(IntegratedLoot.EXCLUDED_ITEMS), "The excluded tag does not contain the barrier");
    helper.assertTrue(has(table(helper, "source_tagged").getRandomItems(params, 1L), Items.BARRIER), "The test source must yield a barrier");
    for (long seed = 1; seed <= SEEDS; seed++) {
      assertItems(helper, table(helper, "target_tagged").getRandomItems(params, seed), "roll with seed " + seed, A, C);
    }
    helper.succeed();
  }

  // e. `required_mods`: a missing mod makes the whole file inactive, a present one does not.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void requiredModsDecideWhetherARuleFileIsActive(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    RuleFileInfo missing = ruleFile(snapshot, "requires_missing");
    helper.assertTrue(!missing.active(), "A rule file with a missing mod is active");
    helper.assertTrue(missing.missingMods().equals(List.of("lootr_gametest_absent_mod")) && missing.missingNamespaces().isEmpty(), "Expected exactly the absent mod as missing but got " + missing);
    helper.assertTrue(fixtureFiles(snapshot.inactiveRuleFiles()).equals(List.of(missing)), "Expected exactly one inactive rule file but got " + fixtureFiles(snapshot.inactiveRuleFiles()));
    helper.assertTrue(snapshot.decisionFor(key("target_required_missing")).isEmpty(), "An inactive rule file decided on a table");
    assertItems(helper, table(helper, "target_required_missing").getRandomItems(params(helper), 1L), "the table of the inactive rule file", A);

    RuleFileInfo present = ruleFile(snapshot, "requires_lootr");
    helper.assertTrue(present.active() && present.missingMods().isEmpty(), "A rule file requiring lootr is inactive");
    Decision decision = snapshot.decisionFor(key("target_required_present")).orElseThrow(() -> new GameTestAssertException("The active rule file made no decision"));
    helper.assertTrue(decision.active() && decision.ruleFile().equals(id("requires_lootr")) && decision.entryIndex() == 0, "Unexpected decision " + decision);
    helper.assertTrue(table(helper, "target_required_present").getRandomItems(params(helper), 1L).size() == 2, "The table of the active rule file was not extended");
    helper.succeed();
  }

  // `required_namespaces`: a namespace without a loaded loot table makes the file inactive, a namespace with one does
  // not; with both gates either unmet is enough.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void requiredNamespacesDecideWhetherARuleFileIsActive(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    LootParams params = params(helper);
    String absentMod = "lootr_gametest_absent_mod";
    String absentNamespace = "lootr_gametest_absent_namespace";

    for (String name : new String[]{"ns_present", "ns_both_met"}) {
      RuleFileInfo file = ruleFile(snapshot, gateId(name));
      helper.assertTrue(file.active() && file.missingMods().isEmpty() && file.missingNamespaces().isEmpty(), "The rule file " + name + " is inactive: " + file);
      Decision decision = snapshot.decisionFor(key("target_" + name)).orElseThrow(() -> new GameTestAssertException("The active rule file " + name + " made no decision"));
      helper.assertTrue(decision.active() && decision.ruleFile().equals(gateId(name)) && decision.entryIndex() == 0, "Unexpected decision " + decision);
      helper.assertTrue(table(helper, "target_" + name).getRandomItems(params, 1L).size() == 2, "The table of the active rule file " + name + " was not extended");
    }

    record Unmet(String name, List<String> mods, List<String> namespaces) {
    }
    for (Unmet unmet : List.of(new Unmet("ns_missing", List.of(), List.of(absentNamespace)), new Unmet("ns_both_mod_unmet", List.of(absentMod), List.of()), new Unmet("ns_both_namespace_unmet", List.of(), List.of(absentNamespace)))) {
      RuleFileInfo file = ruleFile(snapshot, gateId(unmet.name()));
      helper.assertTrue(!file.active() && file.missingMods().equals(unmet.mods()) && file.missingNamespaces().equals(unmet.namespaces()), "Unexpected state of the rule file " + unmet.name() + ": " + file);
      helper.assertTrue(snapshot.inactiveRuleFiles().contains(file) && !snapshot.activeRuleFiles().contains(file), "The rule file " + unmet.name() + " is not listed as inactive");
      helper.assertTrue(snapshot.decisionFor(key("target_" + unmet.name())).isEmpty(), "An inactive rule file decided on a table");
      assertItems(helper, table(helper, "target_" + unmet.name()).getRandomItems(params, 1L), "the table of the inactive rule file " + unmet.name(), A);
    }
    List<ResourceLocation> inactive = snapshot.inactiveRuleFiles().stream().map(RuleFileInfo::id).filter(id -> id.getNamespace().equals(GATE_NAMESPACE)).toList();
    helper.assertTrue(inactive.equals(List.of(gateId("ns_both_mod_unmet"), gateId("ns_both_namespace_unmet"), gateId("ns_missing"))), "Unexpected inactive rule files of the namespace gate: " + inactive);
    helper.succeed();
  }

  // `required_namespaces` is optional, a list of namespaces, and an invalid entry makes the whole file malformed.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void requiredNamespacesAreParsedAndValidated(GameTestHelper helper) {
    String entries = "\"entries\": [{\"tables\": [\"a:b\"], \"exclude\": true}]";
    Map<ResourceLocation, JsonElement> rules = new HashMap<>();
    rules.put(rl("x:valid"), JsonParser.parseString("{\"required_mods\": [\"m\"], \"required_namespaces\": [\"cataclysm\", \"my-mod_1.x\"], " + entries + "}"));
    rules.put(rl("x:absent"), JsonParser.parseString("{" + entries + "}"));
    rules.put(rl("x:empty_list"), JsonParser.parseString("{\"required_namespaces\": [], " + entries + "}"));
    rules.put(rl("x:upper_case"), JsonParser.parseString("{\"required_namespaces\": [\"Cataclysm\"], " + entries + "}"));
    rules.put(rl("x:space"), JsonParser.parseString("{\"required_namespaces\": [\"a b\"], " + entries + "}"));
    rules.put(rl("x:colon"), JsonParser.parseString("{\"required_namespaces\": [\"a:b\"], " + entries + "}"));
    rules.put(rl("x:empty_name"), JsonParser.parseString("{\"required_namespaces\": [\"\"], " + entries + "}"));
    rules.put(rl("x:one_bad_of_two"), JsonParser.parseString("{\"required_namespaces\": [\"fine\", \"not fine\"], " + entries + "}"));
    rules.put(rl("x:not_a_list"), JsonParser.parseString("{\"required_namespaces\": {\"a\": \"b\"}, " + entries + "}"));
    IntegratedLootDefinitions parsed = IntegratedLootDefinitions.parse(Map.of(), rules);

    helper.assertTrue(parsed.ruleFiles().keySet().equals(Set.of(rl("x:valid"), rl("x:absent"), rl("x:empty_list"))), "Unexpected intact rule files: " + parsed.ruleFiles().keySet());
    helper.assertTrue(parsed.ruleFiles().get(rl("x:valid")).requiredNamespaces().equals(List.of("cataclysm", "my-mod_1.x")) && parsed.ruleFiles().get(rl("x:valid")).requiredMods().equals(List.of("m")), "The gates were not read: " + parsed.ruleFiles().get(rl("x:valid")));
    helper.assertTrue(parsed.ruleFiles().get(rl("x:absent")).requiredNamespaces().isEmpty() && parsed.ruleFiles().get(rl("x:empty_list")).requiredNamespaces().isEmpty(), "A missing or empty list must mean no gate");
    Set<ResourceLocation> malformed = new HashSet<>();
    parsed.problems().forEach(problem -> {
      helper.assertTrue(problem.kind() == FileKind.RULE && !problem.message().isBlank(), "Unexpected problem " + problem);
      malformed.add(problem.id());
    });
    helper.assertTrue(parsed.problems().size() == 6 && malformed.equals(Set.of(rl("x:upper_case"), rl("x:space"), rl("x:colon"), rl("x:empty_name"), rl("x:one_bad_of_two"), rl("x:not_a_list"))), "Expected the six invalid files to be malformed but got " + parsed.problems());
    helper.succeed();
  }

  // The pools and rule files that ship with the mod (namespace lootr). The test environment has vanilla and the test
  // data only, so everything that needs another mod or namespace is inactive.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void builtInPoolsAndRuleFilesAreLoadedAndWellFormed(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    Set<ResourceLocation> pools = snapshot.pools().stream().map(Pool::id).filter(id -> id.getNamespace().equals(BUILT_IN_NAMESPACE)).collect(Collectors.toSet());
    helper.assertTrue(pools.equals(Set.of(builtIn("end"), builtIn("nether"), builtIn("ocean"), builtIn("overworld_common"), builtIn("overworld_rare"), builtIn("village"))), "Unexpected built-in pools: " + pools);
    long ruleFiles = snapshot.ruleFiles().stream().filter(file -> file.id().getNamespace().equals(BUILT_IN_NAMESPACE)).count();
    helper.assertTrue(ruleFiles == 51, "Expected 51 built-in rule files but got " + ruleFiles);

    List<FileProblem> malformed = snapshot.malformedFiles().stream().filter(problem -> problem.id().getNamespace().equals(BUILT_IN_NAMESPACE)).toList();
    helper.assertTrue(malformed.isEmpty(), "Built-in files are malformed: " + malformed);
    List<ResourceLocation> unusable = snapshot.unusablePools().stream().filter(pool -> pool.getNamespace().equals(BUILT_IN_NAMESPACE)).toList();
    helper.assertTrue(unusable.isEmpty(), "Built-in pools without an existing source: " + unusable);
    List<DroppedSource> dropped = snapshot.droppedSources().stream().filter(source -> source.pool().getNamespace().equals(BUILT_IN_NAMESPACE)).toList();
    helper.assertTrue(dropped.isEmpty(), "Sources of built-in pools that are not loaded: " + dropped);
    helper.succeed();
  }

  // How the built-in rule files resolve against the vanilla loot tables.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void builtInRuleFilesResolveAgainstVanilla(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    RuleFileInfo vanilla = ruleFile(snapshot, builtIn("vanilla"));
    helper.assertTrue(vanilla.active(), "lootr:vanilla is inactive: " + vanilla);
    List<UnmatchedPattern> unmatched = snapshot.unmatchedPatterns().stream().filter(pattern -> pattern.ruleFile().getNamespace().equals(BUILT_IN_NAMESPACE)).toList();
    helper.assertTrue(unmatched.isEmpty(), "Built-in patterns match no loot table: " + unmatched);
    List<IneligibleMatch> ineligible = snapshot.ineligibleMatches().stream().filter(match -> match.ruleFile().getNamespace().equals(BUILT_IN_NAMESPACE)).toList();
    helper.assertTrue(ineligible.isEmpty(), "Built-in entries match tables of another type: " + ineligible);
    List<Decision> unusable = snapshot.decisions().values().stream().filter(decision -> decision.ruleFile().getNamespace().equals(BUILT_IN_NAMESPACE) && !decision.exclusion() && !decision.active()).toList();
    helper.assertTrue(unusable.isEmpty(), "Built-in entries without a usable pool: " + unusable);

    Decision dungeon = snapshot.decisionFor(vanillaKey("chests/simple_dungeon")).orElseThrow(() -> new GameTestAssertException("No decision for minecraft:chests/simple_dungeon"));
    helper.assertTrue(dungeon.active() && dungeon.poolId().equals(Optional.of(builtIn("overworld_common"))) && dungeon.ruleFile().equals(builtIn("vanilla")), "Unexpected decision " + dungeon);
    Decision bonus = snapshot.decisionFor(vanillaKey("chests/spawn_bonus_chest")).orElseThrow(() -> new GameTestAssertException("No decision for minecraft:chests/spawn_bonus_chest"));
    helper.assertTrue(bonus.exclusion() && !bonus.active() && bonus.ruleFile().equals(builtIn("vanilla")), "Unexpected decision " + bonus);

    RuleFileInfo cataclysm = ruleFile(snapshot, builtIn("excluded_cataclysm"));
    helper.assertTrue(!cataclysm.active() && cataclysm.missingMods().isEmpty() && cataclysm.missingNamespaces().equals(List.of("cataclysm")), "Unexpected state of lootr:excluded_cataclysm: " + cataclysm);
    RuleFileInfo astral = ruleFile(snapshot, builtIn("excluded_astral_dimension"));
    helper.assertTrue(!astral.active() && astral.missingMods().isEmpty() && astral.missingNamespaces().equals(List.of("astral_dimension")), "Unexpected state of lootr:excluded_astral_dimension: " + astral);
    RuleFileInfo arise = ruleFile(snapshot, builtIn("dungeons_arise"));
    helper.assertTrue(!arise.active() && arise.missingMods().equals(List.of("dungeons_arise")) && arise.missingNamespaces().isEmpty(), "Unexpected state of lootr:dungeons_arise: " + arise);
    helper.succeed();
  }

  // f. Malformed files are reported and skipped; everything else is untouched.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void malformedFilesAreReportedAndSkipped(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    // The files that ship with the mod are checked in builtInPoolsAndRuleFilesAreLoadedAndWellFormed.
    List<FileProblem> fixtureProblems = snapshot.malformedFiles().stream().filter(problem -> problem.id().getNamespace().equals(FIXTURE_NAMESPACE)).toList();
    Set<String> malformed = new HashSet<>();
    fixtureProblems.forEach(problem -> {
      helper.assertTrue(!problem.message().isBlank(), "A malformed file was reported without a reason: " + problem);
      malformed.add(problem.kind().label() + " " + problem.id().getPath());
    });
    helper.assertTrue(malformed.equals(Set.of("pool malformed", "pool broken_syntax", "rule malformed", "rule malformed_entry", "rule malformed_pattern")), "Unexpected malformed files: " + malformed);
    helper.assertTrue(fixtureProblems.size() == 5, "A malformed file was reported more than once");

    helper.assertTrue(snapshot.pool(id("malformed")).isEmpty() && snapshot.pool(id("broken_syntax")).isEmpty(), "A malformed pool is in use");
    helper.assertTrue(snapshot.ruleFiles().stream().noneMatch(file -> file.id().getPath().startsWith("malformed")), "A malformed rule file is in use");
    helper.assertTrue(snapshot.pool(id("bcd")).isPresent() && ruleFile(snapshot, "basic").active(), "The malformed files disturbed the other files");
    // The malformed rule files all name target_a; its decision still comes from the intact file.
    helper.assertTrue(snapshot.decisionFor(key("target_a")).orElseThrow().ruleFile().equals(id("basic")), "target_a is not decided by the intact rule file");
    helper.succeed();
  }

  // g. Priority, first match, `exclude` and the two wildcards.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void priorityFirstMatchExcludeAndGlobs(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    LootParams params = params(helper);
    List<String> order = fixtureFiles(snapshot.ruleFiles()).stream().map(file -> file.id().getPath()).toList();
    helper.assertTrue(order.equals(List.of("prio_high", "prio_low", "basic", "requires_lootr", "requires_missing", "globs")), "Rule files are not ordered by priority, then id: " + order);

    // Higher priority wins over the lower file's `prio/*`.
    assertDecision(helper, snapshot, "prio/both", "prio_high", 0, "lootr_gametest:integrated/prio/both", false, "e");
    assertItems(helper, table(helper, "prio/both").getRandomItems(params, 1L), "prio/both", A, E);

    // An `exclude` entry blocks the lower-priority extend.
    Decision excluded = assertDecision(helper, snapshot, "prio/excluded", "prio_high", 1, "lootr_gametest:integrated/prio/excluded", true, null);
    helper.assertTrue(!excluded.active() && excluded.pool().isEmpty(), "An exclusion is active");
    assertItems(helper, table(helper, "prio/excluded").getRandomItems(params, 1L), "prio/excluded", A);

    // Within a file the first matching entry wins: `prio/*` (entry 0), not the exact id in entry 1.
    assertDecision(helper, snapshot, "prio/first", "prio_low", 0, "lootr_gametest:integrated/prio/*", false, "bcd");

    // `*` does not cross `/`, `**` does; a wildcard works in the namespace as well.
    assertDecision(helper, snapshot, "prio/deep/nested", "globs", 0, "lootr_gametest:integrated/prio/**", false, "e");
    assertDecision(helper, snapshot, "glob_ns", "globs", 1, "lootr_*:integrated/glob_ns", false, "e");

    TablePattern star = pattern(helper, "a:b/*");
    TablePattern doubleStar = pattern(helper, "a:b/**");
    TablePattern exact = pattern(helper, "a:b/c");
    TablePattern inner = pattern(helper, "a*:*/c_*.x");
    helper.assertTrue(star.matches(rl("a:b/c")) && star.matches(rl("a:b/")) && !star.matches(rl("a:b/c/d")) && !star.matches(rl("a:b")) && !star.matches(rl("x:b/c")), "`*` does not behave as specified");
    helper.assertTrue(doubleStar.matches(rl("a:b/c")) && doubleStar.matches(rl("a:b/c/d/e")) && !doubleStar.matches(rl("a:c/b/c")), "`**` does not behave as specified");
    helper.assertTrue(exact.isExact() && exact.matches(rl("a:b/c")) && !exact.matches(rl("a:b/cc")) && !star.isExact(), "An exact pattern does not behave as specified");
    helper.assertTrue(inner.matches(rl("abc:dir/c_1.x")) && !inner.matches(rl("abc:dir/sub/c_1.x")) && !inner.matches(rl("abc:dir/c_1ax")) && !inner.matches(rl("b:dir/c_1.x")), "Wildcards inside a part do not behave as specified");
    for (String broken : new String[]{"", "no_namespace", ":path", "namespace:", "a:b:c", "A:b", "a:b c", "a/b:c"}) {
      helper.assertTrue(TablePattern.parse(broken).error().isPresent(), "The broken pattern '" + broken + "' was accepted");
    }

    // Diagnostics: patterns that fit no table (but not those that only lose against earlier entries).
    List<UnmatchedPattern> unmatched = snapshot.unmatchedPatterns().stream().filter(pattern -> pattern.ruleFile().getNamespace().equals(FIXTURE_NAMESPACE)).toList();
    helper.assertTrue(unmatched.equals(List.of(new UnmatchedPattern(id("basic"), 17, "lootr_gametest:integrated/does_not_exist"), new UnmatchedPattern(id("basic"), 17, "lootr_gametest:integrated/nothing/**"))), "Unexpected unmatched patterns: " + unmatched);
    helper.succeed();
  }

  // Pools: missing sources are dropped, a pool without any source is unusable, decisions on unusable or missing
  // pools are recorded but have no effect.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void poolProblemsAreDiagnosedAndHarmless(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    LootParams params = params(helper);
    // The pools that ship with the mod are checked in builtInPoolsAndRuleFilesAreLoadedAndWellFormed.
    List<ResourceLocation> unusable = snapshot.unusablePools().stream().filter(pool -> pool.getNamespace().equals(FIXTURE_NAMESPACE)).toList();
    helper.assertTrue(unusable.equals(List.of(id("unusable"))), "Unexpected unusable pools: " + unusable);
    List<DroppedSource> droppedList = snapshot.droppedSources().stream().filter(source -> source.pool().getNamespace().equals(FIXTURE_NAMESPACE)).toList();
    Set<DroppedSource> dropped = Set.of(new DroppedSource(id("dropped"), rl("lootr_gametest:integrated/no_such_table")), new DroppedSource(id("unusable"), rl("lootr_gametest:integrated/no_such_table")), new DroppedSource(id("unusable"), rl("lootr_gametest:integrated/another_missing_table")));
    helper.assertTrue(new HashSet<>(droppedList).equals(dropped) && droppedList.size() == 3, "Unexpected dropped sources: " + droppedList);

    Pool pool = snapshot.pool(id("dropped")).orElseThrow(() -> new GameTestAssertException("The pool with one valid source is missing"));
    helper.assertTrue(pool.sources().size() == 1 && pool.sources().getFirst().table().equals(key("source_e")) && pool.sources().getFirst().weight() == 3 && pool.totalWeight() == 3, "Unexpected sources " + pool.sources());
    assertItems(helper, table(helper, "target_dropped").getRandomItems(params, 1L), "the pool with a dropped source", A, E);

    Pool three = snapshot.pool(id("three")).orElseThrow();
    helper.assertTrue(three.totalWeight() == 6 && three.sources().stream().map(IntegratedLootSnapshot.Source::weight).toList().equals(List.of(1, 2, 3)), "Unexpected weights " + three.sources());

    for (String name : new String[]{"target_missing_pool", "target_unusable_pool"}) {
      Decision decision = snapshot.decisionFor(key(name)).orElseThrow(() -> new GameTestAssertException("No decision recorded for " + name));
      helper.assertTrue(!decision.active() && !decision.exclusion() && decision.pool().isEmpty() && decision.problem().isPresent(), "Unexpected decision " + decision);
      assertItems(helper, table(helper, name).getRandomItems(params, 1L), name, A);
    }
    helper.assertTrue(snapshot.decisionFor(key("target_missing_pool")).orElseThrow().poolId().equals(Optional.of(id("no_such_pool"))), "The missing pool is not named");
    long fixtureExclusions = snapshot.decisions().values().stream().filter(decision -> decision.exclusion() && decision.ruleFile().getNamespace().equals(FIXTURE_NAMESPACE)).count();
    helper.assertTrue(snapshot.extensionCount() == snapshot.decisions().values().stream().filter(Decision::active).count() && snapshot.exclusionCount() == snapshot.decisions().values().stream().filter(Decision::exclusion).count() && fixtureExclusions == 1, "The decision counts are off");
    helper.succeed();
  }

  // h. Determinism: the same non-zero seed gives the same result, and the level's shared random is not touched.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void extensionIsDeterministicAndLeavesLevelRandomAlone(GameTestHelper helper) {
    LootTable table = table(helper, "target_range");
    LootParams params = params(helper);
    Set<String> outcomes = new HashSet<>();
    for (long seed = 1; seed <= SEEDS; seed++) {
      SimpleContainer first = new SimpleContainer(27);
      SimpleContainer second = new SimpleContainer(27);
      table.fill(first, params, seed);
      table.fill(second, params, seed);
      for (int slot = 0; slot < 27; slot++) {
        helper.assertTrue(ItemStack.matches(first.getItem(slot), second.getItem(slot)), "Two fills with seed " + seed + " differ in slot " + slot);
      }
      ObjectArrayList<ItemStack> firstList = table.getRandomItems(params, seed);
      helper.assertTrue(ItemStack.listMatches(firstList, table.getRandomItems(params, seed)), "Two list rolls with seed " + seed + " differ");
      outcomes.add(describe(firstList));
    }
    helper.assertTrue(outcomes.size() > 1, "Every seed gave the same result, so the seed is not used at all");

    // The level random cannot be read without advancing it, but it can be seeded: after seeding it, the next
    // value it yields is known. If that value still comes out after an extended fill, the fill drew nothing
    // from it. Everything happens in this one block on the server thread, so nothing else draws in between.
    RandomSource levelRandom = helper.getLevel().getRandom();
    try {
      levelRandom.setSeed(42L);
      long untouched = levelRandom.nextLong();

      levelRandom.setSeed(42L);
      IntegratedLoot.Stats before = IntegratedLoot.stats();
      table.fill(new SimpleContainer(27), params, 777L);
      helper.assertTrue(IntegratedLoot.stats().extensions() == before.extensions() + 1, "The fill was not extended");
      helper.assertTrue(levelRandom.nextLong() == untouched, "An extension with a non-zero seed advanced the level random");

      // Counter-check that the method notices a draw: with seed 0 vanilla rolls with the level random itself.
      levelRandom.setSeed(42L);
      table.fill(new SimpleContainer(27), params, 0L);
      helper.assertTrue(levelRandom.nextLong() != untouched, "The check cannot detect draws from the level random");
    } finally {
      levelRandom.setSeed(System.nanoTime());
    }
    helper.succeed();
  }

  // i. The options: skip_existing_items, max_extra_stacks, enabled; and the rolls of a rule (0, a range).
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void optionsAndRollsAreRespected(GameTestHelper helper) {
    LootParams params = params(helper);
    IntegratedLootSettings original = IntegratedLoot.getSettings();
    helper.assertTrue(original.equals(IntegratedLootSettings.DEFAULT), "The tests expect the default configuration but found " + original);
    LootTable existing = table(helper, "target_existing");
    for (long seed = 1; seed <= SEEDS; seed++) {
      long current = seed;
      // The target yields B itself and asks for three of B, C and D.
      withSettings(settings -> settings.withSkipExistingItems(true), () -> assertItems(helper, existing.getRandomItems(params, current), "skip_existing_items on", B, C, D));
      withSettings(settings -> settings.withSkipExistingItems(false), () -> assertItems(helper, existing.getRandomItems(params, current), "skip_existing_items off", B, B, C, D));

      // target_cap asks for three.
      withSettings(settings -> settings.withMaxExtraStacks(1), () -> helper.assertTrue(table(helper, "target_cap").getRandomItems(params, current).size() == 2, "max_extra_stacks 1 was not respected"));
      withSettings(settings -> settings.withMaxExtraStacks(2), () -> helper.assertTrue(table(helper, "target_cap").getRandomItems(params, current).size() == 3, "max_extra_stacks 2 was not respected"));
      withSettings(settings -> settings.withMaxExtraStacks(0), () -> assertItems(helper, table(helper, "target_cap").getRandomItems(params, current), "max_extra_stacks 0", A));
      assertItems(helper, table(helper, "target_cap").getRandomItems(params, current), "the default max_extra_stacks", A, B, C, D);

      withSettings(settings -> settings.withEnabled(false), () -> {
        IntegratedLoot.Stats before = IntegratedLoot.stats();
        assertItems(helper, table(helper, "target_a").getRandomItems(params, current), "enabled off", A);
        SimpleContainer container = new SimpleContainer(27);
        table(helper, "target_a").fill(container, params, current);
        assertItems(helper, stacks(container), "enabled off (fill)", A);
        helper.assertTrue(IntegratedLoot.stats().equals(before), "The counters moved although the feature is off");
      });

      assertItems(helper, table(helper, "target_zero").getRandomItems(params, current), "rolls 0", A);
    }
    helper.assertTrue(IntegratedLoot.getSettings().equals(original), "The settings were not restored");
    Decision zero = IntegratedLoot.snapshot().decisionFor(key("target_zero")).orElseThrow();
    helper.assertTrue(zero.active() && zero.rolls().equals(new Rolls(0, 0)), "Unexpected decision " + zero);
    helper.assertTrue(IntegratedLoot.snapshot().decisionFor(key("target_range")).orElseThrow().rolls().equals(new Rolls(1, 3)), "The rolls range was not read");

    // Range 1-3 over many seeds: never outside, and every value occurs.
    Set<Integer> seen = new HashSet<>();
    for (long seed = 1; seed <= 200; seed++) {
      ObjectArrayList<ItemStack> rolled = table(helper, "target_range").getRandomItems(params, seed);
      int extras = rolled.size() - 1;
      helper.assertTrue(has(rolled, A) && extras >= 1 && extras <= 3, "Seed " + seed + " added " + extras + " stacks, outside 1-3");
      seen.add(extras);
    }
    helper.assertTrue(seen.equals(Set.of(1, 2, 3)), "Not every value of the range 1-3 occurred: " + seen);

    // The record keeps the options inside their limits.
    IntegratedLootSettings clamped = new IntegratedLootSettings(true, 0, true, true, 99, false);
    helper.assertTrue(clamped.moddedItemWeight() == 1 && clamped.maxExtraStacks() == 9 && IntegratedLootSettings.DEFAULT.withMaxExtraStacks(-3).maxExtraStacks() == 0, "The settings are not clamped");
    helper.succeed();
  }

  // j. Lootr's own path: both players get the original stack and the extras in their own inventory.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = CONVERSION_TIMEOUT_TICKS)
  public void lootrInventoriesAreExtended(GameTestHelper helper) {
    LootrTestSupport.placeLootContainer(helper, CHEST_POS, Blocks.CHEST, key("target_a"), 0L);
    helper.startSequence()
        .thenWaitUntil(() -> LootrTestSupport.assertBlockIs(helper, CHEST_POS, LootrRegistry.getChestBlock()))
        .thenExecute(() -> {
          for (int i = 0; i < 2; i++) {
            ServerPlayer player = LootrTestSupport.createRealPlayer(helper);
            IntegratedLoot.Stats before = IntegratedLoot.stats();
            ILootrInventory inventory = LootrAPI.getInventory(LootrTestSupport.getLootrBlockEntity(helper, CHEST_POS), player);
            helper.assertTrue(inventory != null, "Lootr returned no inventory for player " + i);
            assertTargetPlusTwo(helper, stacks(inventory), "the Lootr inventory of player " + i);
            helper.assertTrue(IntegratedLoot.stats().extensions() == before.extensions() + 1, "Player " + i + " did not get an extension of their own");
          }
        })
        .thenSucceed();
  }

  // k. A matched table of another type is never extended and listed as ineligible; a table without a type is fine.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void entityTableIsIneligible(GameTestHelper helper) {
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    LootParams params = params(helper);
    helper.assertTrue(table(helper, "entity_target").getParamSet() == LootContextParamSets.ENTITY, "The test table is not an entity table");
    helper.assertTrue(snapshot.decisionFor(key("entity_target")).isEmpty(), "An entity table got a decision");
    List<IneligibleMatch> ineligible = snapshot.ineligibleMatches();
    helper.assertTrue(ineligible.equals(List.of(new IneligibleMatch(key("entity_target"), "minecraft:entity", id("basic"), 7, "lootr_gametest:integrated/entity_target"))), "Unexpected ineligible matches: " + ineligible);
    for (long seed = 1; seed <= SEEDS; seed++) {
      assertItems(helper, table(helper, "entity_target").getRandomItems(params, seed), "the entity table", A);
    }

    helper.assertTrue(table(helper, "untyped_target").getParamSet() == LootTable.DEFAULT_PARAM_SET, "The test table has a type");
    assertDecision(helper, snapshot, "untyped_target", "basic", 7, "lootr_gametest:integrated/untyped_target", false, "bcd");
    helper.assertTrue(table(helper, "untyped_target").getRandomItems(params, 1L).size() == 2, "A table without a type was not extended");
    helper.succeed();
  }

  // l. Explorer maps: while a source is rolled no map is created (no structure search), other rolls are untouched.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void explorerMapIsSkippedWhileRollingASource(GameTestHelper helper) {
    LootTable target = table(helper, "target_map");
    LootTable source = table(helper, "source_map");
    LootParams params = params(helper);
    helper.assertTrue(IntegratedLoot.getSettings().skipMapItems(), "skip_map_items is expected to be on by default");

    IntegratedLoot.Stats before = IntegratedLoot.stats();
    assertItems(helper, target.getRandomItems(params, 1L), "the extension with skip_map_items on", A, C);
    helper.assertTrue(IntegratedLoot.stats().suppressedMaps() == before.suppressedMaps() + 1, "The explorer map function was not short-circuited exactly once");
    SimpleContainer container = new SimpleContainer(27);
    target.fill(container, params, 2L);
    assertItems(helper, stacks(container), "the fill with skip_map_items on", A, C);
    helper.assertTrue(IntegratedLoot.stats().suppressedMaps() == before.suppressedMaps() + 2, "The explorer map function was not short-circuited during the fill");

    // A normal roll of the same table runs the vanilla function. The test world generates no structures, so
    // vanilla finds nothing (without searching) and leaves the plain map.
    before = IntegratedLoot.stats();
    assertItems(helper, source.getRandomItems(params, 1L), "a normal roll of the source", Items.MAP, C);
    helper.assertTrue(IntegratedLoot.stats().suppressedMaps() == before.suppressedMaps(), "A normal roll was short-circuited");

    withSettings(settings -> settings.withSkipMapItems(false), () -> {
      IntegratedLoot.Stats off = IntegratedLoot.stats();
      assertItems(helper, target.getRandomItems(params, 1L), "the extension with skip_map_items off", A, Items.MAP, C);
      helper.assertTrue(IntegratedLoot.stats().suppressedMaps() == off.suppressedMaps(), "The map was skipped although skip_map_items is off");
    });
    helper.succeed();
  }

  // m. The pure mapping of lootintegrations.json onto the settings.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void lootIntegrationsConfigIsMapped(GameTestHelper helper) {
    IntegratedLootSettings base = IntegratedLootSettings.DEFAULT;
    String example = """
        // Loot table settings
        {
          "moddedItemWeight": {
            "desc:": "Weight bonus for modded items", // a comment
            "moddedItemWeight": 1
          },
          "skipExistingItems": {
            "desc:": "...",
            "skipExistingItems": false
          },
          "skipMapItems": {
            "desc:": "...",
            "skipMapItems": false
          },
          "debugOutput": {
            "desc:": "...",
            "debugOutput": true
          },
          "showcontainerloottable": {
            "desc:": "...",
            "showcontainerloottable": true
          }
        }
        """;
    LootIntegrationsImport.Result result = LootIntegrationsImport.map(base, example);
    helper.assertTrue(result.settings().equals(new IntegratedLootSettings(true, 2, false, false, 4, true)), "Unexpected mapping: " + result.settings());
    helper.assertTrue(result.takenOver().equals(List.of("modded_item_weight=2", "skip_existing_items=false", "skip_map_items=false", "debug_log=true")), "Unexpected list of values: " + result.takenOver());

    // The weight is their bonus plus one; values that are already ours are still taken over.
    helper.assertTrue(LootIntegrationsImport.map(base, "{\"moddedItemWeight\": {\"moddedItemWeight\": 0}}").settings().moddedItemWeight() == 1, "A bonus of 0 must become the weight 1");
    helper.assertTrue(LootIntegrationsImport.map(base, "{\"moddedItemWeight\": {\"moddedItemWeight\": 6}}").settings().equals(base.withModdedItemWeight(7)), "Only the weight may change");
    helper.assertTrue(LootIntegrationsImport.map(base.withSkipMapItems(false), "{\"skipMapItems\": {\"skipMapItems\": true}}").settings().equals(base), "A single option was not mapped");
    helper.assertTrue(LootIntegrationsImport.map(base, "{\"moddedItemWeight\": {\"moddedItemWeight\": 2000000000}}").settings().moddedItemWeight() == IntegratedLootSettings.MAX_MODDED_ITEM_WEIGHT, "A huge bonus is not limited");

    // Missing and malformed options keep our values.
    String malformed = """
        {
          "moddedItemWeight": { "moddedItemWeight": "three" },
          "skipExistingItems": { "skipExistingItems": "false" },
          "skipMapItems": false,
          "debugOutput": { "wrongName": true },
          "showcontainerloottable": { "showcontainerloottable": true }
        }
        """;
    for (String content : new String[]{malformed, "{\"moddedItemWeight\": {\"moddedItemWeight\": -1}}", "{\"moddedItemWeight\": {\"moddedItemWeight\": 2.5}}", "{}", "", "   ", "not json at all {{{", "[1, 2, 3]", "42", "{\"moddedItemWeight\": {\"moddedItemWeight\": 1}"}) {
      LootIntegrationsImport.Result unchanged = LootIntegrationsImport.map(base, content);
      helper.assertTrue(unchanged.settings().equals(base) && unchanged.takenOver().isEmpty(), "Content that sets nothing changed the settings: " + content);
    }
    helper.assertTrue(LootIntegrationsImport.map(base, null).settings().equals(base), "A missing content changed the settings");

    // File access: no directory and no file change nothing; an existing file is read and left exactly as it was.
    helper.assertTrue(LootIntegrationsImport.read(null, base).isEmpty(), "A missing configuration directory was read");
    Path directory = null;
    try {
      directory = Files.createTempDirectory("lootr-gametest");
      helper.assertTrue(LootIntegrationsImport.read(directory, base).isEmpty(), "A missing file was read");
      Path file = directory.resolve(LootIntegrationsImport.FILE_NAME);
      Files.writeString(file, example, StandardCharsets.UTF_8);
      Optional<LootIntegrationsImport.Result> read = LootIntegrationsImport.read(directory, base);
      helper.assertTrue(read.isPresent() && read.get().equals(result), "The file was not read like its content");
      helper.assertTrue(Files.readString(file, StandardCharsets.UTF_8).equals(example), "The file was modified");
    } catch (IOException e) {
      throw new GameTestAssertException("Could not use a temporary directory: " + e);
    } finally {
      if (directory != null) {
        try {
          Files.deleteIfExists(directory.resolve(LootIntegrationsImport.FILE_NAME));
          Files.deleteIfExists(directory);
        } catch (IOException ignored) {
        }
      }
    }
    helper.succeed();
  }

  // n. A table without a decision is untouched and the counters do not move.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void tableWithoutDecisionIsUntouched(GameTestHelper helper) {
    LootTable table = table(helper, "plain");
    LootParams params = params(helper);
    helper.assertTrue(IntegratedLoot.snapshot().decisionFor(key("plain")).isEmpty(), "The plain table has a decision");
    IntegratedLoot.Stats before = IntegratedLoot.stats();
    for (long seed = 0; seed <= SEEDS; seed++) {
      assertItems(helper, table.getRandomItems(params, seed), "the plain table", A);
      assertItems(helper, table.getRandomItems(params, RandomSource.create(seed)), "the plain table", A);
      SimpleContainer container = new SimpleContainer(27);
      table.fill(container, params, seed);
      assertItems(helper, stacks(container), "the plain table (fill)", A);
    }
    assertItems(helper, table.getRandomItems(params), "the plain table", A);
    // Outside the test namespace only the rule files that ship with the mod decide.
    helper.assertTrue(IntegratedLoot.snapshot().decisions().values().stream().allMatch(decision -> decision.table().location().getNamespace().equals(FIXTURE_NAMESPACE) || decision.ruleFile().getNamespace().equals(BUILT_IN_NAMESPACE)), "A table outside the test namespace was decided by a test rule file");
    helper.assertTrue(IntegratedLoot.stats().equals(before), "The counters moved for a table without a decision");
    helper.succeed();
  }

  // o. Weighted draw: one of two candidates is drawn; the modded one clearly more often with a weight of 5.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void moddedItemsAreDrawnMoreOften(GameTestHelper helper) {
    LootTable table = table(helper, "target_weighted");
    LootParams params = params(helper);
    Item modded = BuiltInRegistries.ITEM.get(LootrAPI.rl("trophy"));
    helper.assertTrue(modded != Items.AIR, "The trophy item is not registered");
    int rolls = 300;

    int[] counts = new int[2];
    withSettings(settings -> settings.withModdedItemWeight(5), () -> {
      for (long seed = 1; seed <= rolls; seed++) {
        ObjectArrayList<ItemStack> rolled = table.getRandomItems(params, seed);
        helper.assertTrue(rolled.size() == 2 && has(rolled, A), "Expected the original stack and one extra but got " + describe(rolled));
        counts[0] += count(rolled, modded);
        counts[1] += count(rolled, C);
      }
    });
    helper.assertTrue(counts[0] + counts[1] == rolls, "An extra was neither of the two candidates");
    // Expected 250 of 300 (5 : 1). The seeds are fixed, so the outcome is the same on every run.
    helper.assertTrue(counts[0] >= 210 && counts[1] >= 10, "With weight 5 the modded item was drawn " + counts[0] + " times, the vanilla one " + counts[1] + " times");

    int[] even = new int[2];
    withSettings(settings -> settings.withModdedItemWeight(1), () -> {
      for (long seed = 1; seed <= rolls; seed++) {
        ObjectArrayList<ItemStack> rolled = table.getRandomItems(params, seed);
        even[0] += count(rolled, modded);
        even[1] += count(rolled, C);
      }
    });
    // Expected 150 of 300 (1 : 1).
    helper.assertTrue(even[0] + even[1] == rolls && even[0] >= 110 && even[0] <= 190, "With weight 1 the modded item was drawn " + even[0] + " times of " + rolls);
    helper.succeed();
  }

  // Too few candidates: exactly one more, different source is rolled, and never a third.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void secondSourceIsRolledWhenCandidatesAreShort(GameTestHelper helper) {
    LootParams params = params(helper);
    Set<String> pairs = new HashSet<>();
    for (long seed = 1; seed <= 60; seed++) {
      // Two sources with one item each, two stacks wanted: both sources are needed.
      IntegratedLoot.Stats before = IntegratedLoot.stats();
      assertItems(helper, table(helper, "target_two_sources").getRandomItems(params, seed), "two sources with seed " + seed, A, B, C);
      helper.assertTrue(IntegratedLoot.stats().sourceRolls() == before.sourceRolls() + 2, "Expected two source rolls");

      // Three sources with one item each, three stacks wanted: still only two source rolls, so two stacks.
      before = IntegratedLoot.stats();
      ObjectArrayList<ItemStack> rolled = table(helper, "target_three_sources").getRandomItems(params, seed);
      Set<Item> extras = new HashSet<>();
      rolled.stream().map(ItemStack::getItem).filter(BCD::contains).forEach(extras::add);
      helper.assertTrue(rolled.size() == 3 && has(rolled, A) && extras.size() == 2, "Expected the original stack and two different extras but got " + describe(rolled));
      helper.assertTrue(IntegratedLoot.stats().sourceRolls() == before.sourceRolls() + 2, "More or less than two source rolls for one extension");
      pairs.add(describe(rolled));

      // Enough candidates from the first source: no second roll.
      before = IntegratedLoot.stats();
      table(helper, "target_a").getRandomItems(params, seed);
      helper.assertTrue(IntegratedLoot.stats().sourceRolls() == before.sourceRolls() + 1, "A second source was rolled without need");
    }
    helper.assertTrue(pairs.size() > 1, "The sources of the pool are not picked at random");
    helper.succeed();
  }

  // A failing source leaves the roll as vanilla produced it and is reported once per target table.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void failingSourceLeavesTheRollUntouched(GameTestHelper helper) {
    LootTable table = table(helper, "target_throwing");
    LootParams params = params(helper);
    try {
      table(helper, "source_throwing").getRandomItems(params, 1L);
      throw new GameTestAssertException("The test source is expected to throw when it is rolled with chest parameters");
    } catch (RuntimeException expected) {
      if (expected instanceof GameTestAssertException) {
        throw expected;
      }
    }
    IntegratedLoot.Stats before = IntegratedLoot.stats();
    for (long seed = 1; seed <= 5; seed++) {
      assertItems(helper, table.getRandomItems(params, seed), "the roll with a failing source", A);
      SimpleContainer container = new SimpleContainer(27);
      table.fill(container, params, seed);
      assertItems(helper, stacks(container), "the fill with a failing source", A);
    }
    IntegratedLoot.Stats after = IntegratedLoot.stats();
    helper.assertTrue(after.failures() == before.failures() + 10, "Expected ten failed extensions but got " + (after.failures() - before.failures()));
    helper.assertTrue(after.extensions() == before.extensions() && after.stacksAdded() == before.stacksAdded(), "A failed extension was counted as performed");
    helper.assertTrue(IntegratedLoot.hasReportedFailure(key("target_throwing")), "The failure was not reported");
    helper.assertTrue(!IntegratedLoot.hasReportedFailure(key("target_a")), "A working table is marked as failed");

    // The thread's "rolling a source" state was restored: the next extension works.
    assertTargetPlusTwo(helper, table(helper, "target_a").getRandomItems(params, 1L), "a roll after a failed one");
    helper.succeed();
  }

  // Lifecycle: one data load is resolved once, the pure parser reports problems without affecting other files,
  // and the debug line does not break an extension.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void resolutionHappensOncePerDataLoad(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    helper.assertTrue(!snapshot.isEmpty() && snapshot.tableCount() == server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).size(), "The snapshot was not resolved against the loaded loot tables");
    IntegratedLoot.onDataLoaded(server.reloadableRegistries().get());
    IntegratedLoot.onServerStarting(server);
    helper.assertTrue(IntegratedLoot.snapshot() == snapshot, "The same data load was resolved again");
    helper.assertTrue(IntegratedLootSnapshot.EMPTY.isEmpty() && IntegratedLootSnapshot.EMPTY.decisionFor(key("target_a")).isEmpty(), "The empty snapshot is not empty");

    JsonElement goodPool = JsonParser.parseString("{\"sources\": [{\"table\": \"minecraft:chests/simple_dungeon\", \"weight\": 3}, {\"table\": \"minecraft:chests/abandoned_mineshaft\"}]}");
    JsonElement goodRule = JsonParser.parseString("{\"required_mods\": [\"a\"], \"priority\": 7, \"entries\": [{\"tables\": [\"a:b/*\"], \"pool\": \"x:y\", \"rolls\": {\"min\": 2, \"max\": 5}}, {\"tables\": [\"a:c\"], \"exclude\": true}]}");
    Map<ResourceLocation, JsonElement> pools = Map.of(rl("x:good"), goodPool, rl("x:no_sources"), JsonParser.parseString("{}"), rl("x:zero_weight"), JsonParser.parseString("{\"sources\": [{\"table\": \"a:b\", \"weight\": 0}]}"));
    Map<ResourceLocation, JsonElement> rules = Map.of(
        rl("x:good"), goodRule,
        rl("x:exclude_with_pool"), JsonParser.parseString("{\"entries\": [{\"tables\": [\"a:b\"], \"exclude\": true, \"pool\": \"x:y\"}]}"),
        rl("x:no_tables"), JsonParser.parseString("{\"entries\": [{\"tables\": [], \"pool\": \"x:y\"}]}"),
        rl("x:negative_rolls"), JsonParser.parseString("{\"entries\": [{\"tables\": [\"a:b\"], \"pool\": \"x:y\", \"rolls\": -1}]}"),
        rl("x:min_above_max"), JsonParser.parseString("{\"entries\": [{\"tables\": [\"a:b\"], \"pool\": \"x:y\", \"rolls\": {\"min\": 3, \"max\": 2}}]}"),
        rl("x:not_an_object"), JsonParser.parseString("[]"));
    IntegratedLootDefinitions parsed = IntegratedLootDefinitions.parse(pools, rules);
    helper.assertTrue(parsed.pools().keySet().equals(Set.of(rl("x:good"))) && parsed.ruleFiles().keySet().equals(Set.of(rl("x:good"))), "Only the intact files may be kept: " + parsed.pools().keySet() + " " + parsed.ruleFiles().keySet());
    helper.assertTrue(parsed.problems().size() == 7 && parsed.problems().stream().filter(problem -> problem.kind() == FileKind.POOL).count() == 2, "Expected seven skipped files but got " + parsed.problems());
    helper.assertTrue(parsed.pools().get(rl("x:good")).sources().get(0).weight() == 3 && parsed.pools().get(rl("x:good")).sources().get(1).weight() == 1, "The weights were not read");
    IntegratedLootDefinitions.RuleFile rule = parsed.ruleFiles().get(rl("x:good"));
    helper.assertTrue(rule.priority() == 7 && rule.requiredMods().equals(List.of("a")) && rule.entries().size() == 2, "The rule file was not read: " + rule);
    helper.assertTrue(rule.entries().get(0).rolls().equals(new Rolls(2, 5)) && rule.entries().get(0).pool().equals(Optional.of(rl("x:y"))) && rule.entries().get(1).exclude(), "The entries were not read: " + rule.entries());

    withSettings(settings -> settings.withDebugLog(true), () -> {
      IntegratedLoot.Stats before = IntegratedLoot.stats();
      assertTargetPlusTwo(helper, table(helper, "target_a").getRandomItems(params(helper), 1L), "a roll with debug_log on");
      assertItems(helper, table(helper, "target_two_sources").getRandomItems(params(helper), 1L), "a roll with debug_log on", A, B, C);
      helper.assertTrue(IntegratedLoot.stats().failures() == before.failures(), "The debug line made the extension fail");
    });
    helper.succeed();
  }

  // A real data pack reload: the rules are resolved again against the new loot table objects, and a loot table
  // object of the previous load no longer has a decision. In a batch of its own, because it replaces all data.
  @GameTest(template = EMPTY_STRUCTURE, batch = RELOAD_BATCH)
  public void dataReloadResolvesAgainstTheNewTables(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    LootParams params = params(helper);
    LootTable oldTable = table(helper, "target_a");
    IntegratedLootSnapshot oldSnapshot = IntegratedLoot.snapshot();
    assertTargetPlusTwo(helper, oldTable.getRandomItems(params, 1L), "the table before the reload");
    table(helper, "target_throwing").getRandomItems(params, 1L);
    helper.assertTrue(IntegratedLoot.hasReportedFailure(key("target_throwing")), "The failure was not reported");
    IntegratedLoot.Stats before = IntegratedLoot.stats();

    // On the server thread this returns only after the reload is complete.
    helper.assertTrue(!server.reloadResources(server.getPackRepository().getSelectedIds()).isCompletedExceptionally(), "The data pack reload failed");

    IntegratedLootSnapshot newSnapshot = IntegratedLoot.snapshot();
    LootTable newTable = table(helper, "target_a");
    helper.assertTrue(newTable != oldTable, "The reload did not create new loot table objects");
    helper.assertTrue(newSnapshot != oldSnapshot, "The reload did not resolve the rules again");
    helper.assertTrue(newSnapshot.decisions().equals(oldSnapshot.decisions()) && newSnapshot.malformedFiles().equals(oldSnapshot.malformedFiles()) && newSnapshot.unmatchedPatterns().equals(oldSnapshot.unmatchedPatterns()), "The same data resolved to something else");
    assertTargetPlusTwo(helper, newTable.getRandomItems(params, 1L), "the table after the reload");
    assertItems(helper, oldTable.getRandomItems(params, 1L), "the table object of the previous load", A);
    SimpleContainer container = new SimpleContainer(27);
    oldTable.fill(container, params, 1L);
    assertItems(helper, stacks(container), "the table object of the previous load (fill)", A);

    helper.assertTrue(!IntegratedLoot.hasReportedFailure(key("target_throwing")), "The reload did not reset the reported failures");
    helper.assertTrue(IntegratedLoot.stats().extensions() == before.extensions() + 1, "The counters did not survive the reload or counted the old table object");
    helper.succeed();
  }

  // /lootr integrated <loot_table> and /lootr integrated stats.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void commandReportsDecisionsAndStats(GameTestHelper helper) {
    helper.assertTrue(run(helper, "lootr integrated lootr_gametest:integrated/plain").equals(List.of("lootr_gametest:integrated/plain: no rule.")), "Unexpected output for a table without a rule");

    List<String> excluded = run(helper, "lootr integrated lootr_gametest:integrated/prio/excluded");
    helper.assertTrue(excluded.equals(List.of("lootr_gametest:integrated/prio/excluded: excluded by rule file lootr_gametest:prio_high, entry 1 (pattern lootr_gametest:integrated/prio/excluded).")), "Unexpected output for an excluded table: " + excluded);

    List<String> extended = run(helper, "lootr integrated lootr_gametest:integrated/target_three_sources");
    helper.assertTrue(extended.size() == 3, "Unexpected output for an extended table: " + extended);
    helper.assertTrue(extended.get(0).equals("lootr_gametest:integrated/target_three_sources: extended by rule file lootr_gametest:basic, entry 11 (pattern lootr_gametest:integrated/target_three_sources)."), "Unexpected first line: " + extended.get(0));
    helper.assertTrue(extended.get(1).contains("Pool lootr_gametest:three, rolls 3,"), "Unexpected pool line: " + extended.get(1));
    helper.assertTrue(extended.get(2).equals("  Sources: lootr_gametest:integrated/source_b (weight 1), lootr_gametest:integrated/source_c (weight 2), lootr_gametest:integrated/source_d (weight 3)"), "Unexpected sources line: " + extended.get(2));

    helper.assertTrue(run(helper, "lootr integrated lootr_gametest:integrated/target_range").get(1).contains("rolls 1-3,"), "A rolls range is not shown");
    helper.assertTrue(run(helper, "lootr integrated lootr_gametest:integrated/entity_target").getFirst().contains("has the type minecraft:entity"), "An ineligible table is not explained");
    helper.assertTrue(run(helper, "lootr integrated lootr_gametest:integrated/target_missing_pool").getFirst().contains("pool lootr_gametest:no_such_pool does not exist"), "A missing pool is not explained");
    helper.assertTrue(run(helper, "lootr integrated lootr_gametest:integrated/target_unusable_pool").getFirst().contains("pool lootr_gametest:unusable has no existing source table"), "An unusable pool is not explained");
    helper.assertTrue(run(helper, "lootr integrated lootr_gametest:integrated/no_such_table").equals(List.of("Loot table lootr_gametest:integrated/no_such_table is not loaded.")), "A table that is not loaded is not reported");

    IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
    List<String> stats = run(helper, "lootr integrated stats");
    helper.assertTrue(stats.size() == 6 && stats.get(0).equals("Integrated loot is enabled."), "Unexpected stats output: " + stats);
    helper.assertTrue(stats.get(1).equals("  Pools: " + snapshot.pools().size() + " usable, 1 without a source, 3 dropped sources."), "Unexpected pools line: " + stats.get(1));
    // Active: 5 test files, 2 test files of the namespace gate, lootr:vanilla. Inactive: 1 test file with a missing
    // mod, 3 with a missing mod or namespace, and 50 built-in files that need a mod or a namespace that is not here.
    helper.assertTrue(stats.get(2).equals("  Rule files: 8 active, 54 inactive (missing mods or namespaces), 5 malformed files skipped."), "Unexpected rule files line: " + stats.get(2));
    long builtInExcluded = snapshot.decisions().values().stream().filter(decision -> decision.exclusion() && decision.ruleFile().getNamespace().equals(BUILT_IN_NAMESPACE)).count();
    helper.assertTrue(stats.get(3).startsWith("  Loot tables: " + snapshot.extensionCount() + " extended, " + (1 + builtInExcluded) + " excluded, 2 without a usable pool, 1 of another type, "), "Unexpected loot tables line: " + stats.get(3));
    helper.assertTrue(stats.get(4).equals("  Patterns matching no loot table: 2."), "Unexpected patterns line: " + stats.get(4));
    helper.assertTrue(stats.get(5).startsWith("  Since server start: " + IntegratedLoot.stats().extensions() + " extensions, "), "Unexpected counters line: " + stats.get(5));
    helper.succeed();
  }

  // ----- helpers -----

  private record SingleItemBan(String name, Item item) implements ItemBanSource {
    @Override
    public boolean isBanned(ItemStack stack) {
      return stack.is(item);
    }
  }

  private static void withSettings(UnaryOperator<IntegratedLootSettings> change, Runnable body) {
    IntegratedLootSettings original = IntegratedLoot.getSettings();
    IntegratedLoot.applySettings(change.apply(original));
    try {
      body.run();
    } finally {
      IntegratedLoot.applySettings(original);
    }
  }

  /**
   * Runs a command with operator permission and returns the lines it printed (success and failure messages).
   */
  private static List<String> run(GameTestHelper helper, String command) {
    List<String> lines = new ArrayList<>();
    CommandSource output = new CommandSource() {
      @Override
      public void sendSystemMessage(Component component) {
        lines.add(component.getString());
      }

      @Override
      public boolean acceptsSuccess() {
        return true;
      }

      @Override
      public boolean acceptsFailure() {
        return true;
      }

      @Override
      public boolean shouldInformAdmins() {
        return false;
      }
    };
    MinecraftServer server = helper.getLevel().getServer();
    CommandSourceStack source = server.createCommandSourceStack().withSource(output).withPermission(2);
    server.getCommands().performPrefixedCommand(source, command);
    return lines;
  }

  private static ResourceLocation rl(String id) {
    return ResourceLocation.parse(id);
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("lootr_gametest", path);
  }

  private static ResourceLocation gateId(String path) {
    return ResourceLocation.fromNamespaceAndPath(GATE_NAMESPACE, path);
  }

  private static ResourceLocation builtIn(String path) {
    return ResourceLocation.fromNamespaceAndPath(BUILT_IN_NAMESPACE, path);
  }

  private static ResourceKey<LootTable> vanillaKey(String path) {
    return ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace(path));
  }

  private static ResourceKey<LootTable> key(String name) {
    return ResourceKey.create(Registries.LOOT_TABLE, id("integrated/" + name));
  }

  private static LootTable table(GameTestHelper helper, String name) {
    ResourceKey<LootTable> key = key(name);
    LootTable table = helper.getLevel().getServer().reloadableRegistries().getLootTable(key);
    if (table == LootTable.EMPTY) {
      throw new GameTestAssertException("Test loot table " + key.location() + " was not loaded");
    }
    return table;
  }

  private static LootParams params(GameTestHelper helper) {
    return new LootParams.Builder(helper.getLevel())
        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(CHEST_POS)))
        .create(LootContextParamSets.CHEST);
  }

  private static TablePattern pattern(GameTestHelper helper, String text) {
    return TablePattern.parse(text).result().orElseThrow(() -> new GameTestAssertException("The pattern '" + text + "' was rejected"));
  }

  private static RuleFileInfo ruleFile(IntegratedLootSnapshot snapshot, String path) {
    return ruleFile(snapshot, id(path));
  }

  private static RuleFileInfo ruleFile(IntegratedLootSnapshot snapshot, ResourceLocation id) {
    return snapshot.ruleFiles().stream().filter(file -> file.id().equals(id)).findFirst().orElseThrow(() -> new GameTestAssertException("Rule file " + id + " is not in the snapshot"));
  }

  /**
   * Only the rule files of the test data pack, without the extra ones for the namespace gate and without the built-in ones.
   */
  private static List<RuleFileInfo> fixtureFiles(List<RuleFileInfo> files) {
    return files.stream().filter(file -> file.id().getNamespace().equals(FIXTURE_NAMESPACE)).toList();
  }

  private static Decision assertDecision(GameTestHelper helper, IntegratedLootSnapshot snapshot, String table, String ruleFile, int entryIndex, String pattern, boolean exclusion, String pool) {
    Decision decision = snapshot.decisionFor(key(table)).orElseThrow(() -> new GameTestAssertException("No decision for " + table));
    helper.assertTrue(decision.table().equals(key(table)) && decision.ruleFile().equals(id(ruleFile)) && decision.entryIndex() == entryIndex && decision.pattern().equals(pattern) && decision.exclusion() == exclusion, "Unexpected decision for " + table + ": " + decision);
    if (pool != null) {
      helper.assertTrue(decision.active() && decision.poolId().equals(Optional.of(id(pool))) && decision.pool().orElseThrow().id().equals(id(pool)), "Unexpected pool for " + table + ": " + decision);
    }
    return decision;
  }

  private static List<ItemStack> stacks(Container container) {
    List<ItemStack> result = new ArrayList<>();
    for (int i = 0; i < container.getContainerSize(); i++) {
      if (!container.getItem(i).isEmpty()) {
        result.add(container.getItem(i));
      }
    }
    return result;
  }

  private static boolean has(List<ItemStack> stacks, Item item) {
    return count(stacks, item) > 0;
  }

  private static int count(List<ItemStack> stacks, Item item) {
    return (int) stacks.stream().filter(stack -> stack.is(item)).count();
  }

  private static String describe(List<ItemStack> stacks) {
    return stacks.stream().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount()).sorted().toList().toString();
  }

  /**
   * The stacks are exactly one single-item stack of each given item (an item given twice: two such stacks).
   */
  private static void assertItems(GameTestHelper helper, List<ItemStack> stacks, String what, Item... expected) {
    List<String> wanted = new ArrayList<>();
    for (Item item : expected) {
      wanted.add(BuiltInRegistries.ITEM.getKey(item).getPath() + " x1");
    }
    wanted.sort(null);
    helper.assertTrue(describe(stacks).equals(wanted.toString()), "Expected " + wanted + " for " + what + " but got " + describe(stacks));
  }

  /**
   * target_a: the original stack and exactly two different ones of B, C and D.
   */
  private static void assertTargetPlusTwo(GameTestHelper helper, List<ItemStack> stacks, String what) {
    Set<Item> extras = new HashSet<>();
    stacks.stream().map(ItemStack::getItem).filter(BCD::contains).forEach(extras::add);
    helper.assertTrue(stacks.size() == 3 && count(stacks, A) == 1 && extras.size() == 2 && stacks.stream().allMatch(stack -> stack.getCount() == 1), "Expected the original stack and two different extras for " + what + " but got " + describe(stacks));
  }
}
