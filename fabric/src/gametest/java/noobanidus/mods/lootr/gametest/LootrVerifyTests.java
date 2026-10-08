package noobanidus.mods.lootr.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Decision;
import noobanidus.mods.lootr.common.itemban.ItemBanSource;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import noobanidus.mods.lootr.common.verify.LootVerifier;
import noobanidus.mods.lootr.common.verify.LootVerifier.StartupPlan;
import noobanidus.mods.lootr.common.verify.PackOrigin;
import noobanidus.mods.lootr.common.verify.VerifyOptions;
import noobanidus.mods.lootr.common.verify.VerifyReport;
import noobanidus.mods.lootr.common.verify.VerifyResult;
import noobanidus.mods.lootr.common.verify.VerifyResult.BanInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.IntegratedInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.LoadFailure;
import noobanidus.mods.lootr.common.verify.VerifyResult.OriginInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.RollInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.Scanned;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Tests for the loot table verification (`/lootr verify`). The fixtures are in the test data pack:
 * <ul>
 * <li>`loot_table/verify/`: tables that fail to load (unknown item, broken JSON, unknown entry type), tables with
 * unmet load conditions, tables that cannot produce, one that never drops, one that throws when rolled, one with
 * banned items and one that refers to missing tables.</li>
 * <li>`loot_tables/verify/`: two files in the folder the game no longer reads.</li>
 * <li>`structure/verify/containers.nbt`: a structure template with one block per case. It was written once by a
 * throw-away program (plain NBT, gzip without a time stamp, so the bytes are reproducible) and checked in. Along x:
 * chests with the loot tables `integrated/target_a`, `verify/missing_in_structure`, a barrel with
 * `integrated/prio/excluded`, chests with `integrated/target_missing_pool` and `integrated/plain`, a decorated pot
 * with `verify/pot_only`, chests with `verify/no_pools`, `verify/only_empty`, `verify/only_banned`,
 * `verify/only_dead_references`, `verify/never_drops`, `verify/throws_when_rolled` and `integrated/source_map`,
 * a vault with `verify/missing_vault_loot`, a trial spawner that ejects `verify/missing_spawner_loot` and equips
 * from `verify/missing_equipment_loot`, a chest holding a barrel item with the container loot
 * `verify/missing_item_loot`, and suspicious sand with `minecraft:archaeology/desert_pyramid`. Entities: a chest
 * minecart with `minecraft:chests/abandoned_mineshaft` and a zombie with the death loot
 * `verify/missing_death_loot`.</li>
 * <li>`structure/verify/corrupt.nbt`: not a template at all.</li>
 * <li>`worldgen/processor_list/verify_loot.json`: appends `verify/missing_in_worldgen` and
 * `integrated/untyped_target`.</li>
 * </ul>
 * Integrated loot is covered with the pools and rules of {@link LootrIntegratedLootTests}.
 * <br />
 * A run works on other threads and reads global state there, so the tests that need one share a single run in a
 * batch of its own: the batch registers the item ban they need, starts the run and waits for it.
 */
public class LootrVerifyTests implements FabricGameTest {
  private static final String BATCH = "lootr_verify";
  private static final String RUN_BATCH = "lootr_verify_run";
  private static final String REPEAT_BATCH = "lootr_verify_repeat";
  private static final int RUN_TIMEOUT_SECONDS = 120;
  private static final String NAMESPACE = "lootr_gametest:";
  // The origin key of everything in the test data pack: the id of the test mod.
  private static final String TEST_ORIGIN = "lootr_gametest";
  private static final String BUSY = "A loot table verification is already running. Wait until it has finished.";

  // The shared run of RUN_BATCH. Only touched on the server thread.
  private static final ItemBanSource BAN = new SingleItemBan("gametest:verify", Items.JIGSAW);
  private static final List<String> FIRST_COMMAND = new ArrayList<>();
  private static final List<String> SECOND_COMMAND = new ArrayList<>();
  private static VerifyResult resultBeforeSharedRun = null;
  private static IntegratedLoot.Stats statsBeforeSharedRun = null;
  private static VerifyResult sharedResult = null;
  private static JsonObject sharedReport = null;

  @BeforeBatch(batch = RUN_BATCH)
  public void startSharedRun(ServerLevel level) {
    MinecraftServer server = level.getServer();
    FIRST_COMMAND.clear();
    SECOND_COMMAND.clear();
    sharedResult = null;
    sharedReport = null;
    resultBeforeSharedRun = LootVerifier.lastResult().orElse(null);
    statsBeforeSharedRun = IntegratedLoot.stats();
    ItemBans.register(BAN);
    run(server, "lootr verify rolls 3", 2, FIRST_COMMAND);
    // Issued in the same tick: the first run cannot have ended, because it ends on the server thread.
    run(server, "lootr verify", 2, SECOND_COMMAND);
    waitForRun(server);
  }

  @AfterBatch(batch = RUN_BATCH)
  public void endSharedRun(ServerLevel level) {
    ItemBans.unregister(BAN);
  }

  // ----- the command and the files -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void commandStartsOneRunAndRefusesASecond(GameTestHelper helper) {
    helper.assertTrue(!FIRST_COMMAND.isEmpty() && FIRST_COMMAND.getFirst().startsWith("Loot table verification started with 3 sample rolls per loot table. The report goes to "), "Unexpected answer to the first command: " + FIRST_COMMAND);
    helper.assertTrue(SECOND_COMMAND.equals(List.of(BUSY)), "The second command was not refused: " + SECOND_COMMAND);
    withSharedRun(helper, report -> {
      helper.assertTrue(SECOND_COMMAND.size() == 1, "The refused command heard of the run: " + SECOND_COMMAND);
      List<String> summary = FIRST_COMMAND.subList(1, FIRST_COMMAND.size()).stream().filter(line -> !line.startsWith("Loot table verification: ")).toList();
      helper.assertTrue(summary.getFirst().matches("Loot table verification finished in \\d+\\.\\d s: \\d+ errors, \\d+ warnings, \\d+ notes\\."), "Unexpected first summary line: " + summary);
      helper.assertTrue(summary.getLast().equals("Report: " + sharedResult.files().markdown()), "The summary does not end with the report path: " + summary);
      helper.assertTrue(summary.size() <= 5, "The summary is not short: " + summary);
      helper.assertTrue(summary.equals(VerifyReport.summary(sharedResult)), "The command printed something else than the summary of the result");

      helper.assertTrue(!LootVerifier.isRunning(), "A run is still marked as active");
    });
  }

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void reportFilesAreWrittenAndParseable(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      VerifyResult.ReportFiles files = sharedResult.files();
      Path directory = VerifyOptions.defaultOutputDirectory();
      helper.assertTrue(directory.endsWith(Path.of("logs", "lootr")), "Unexpected report directory " + directory);
      for (Path file : List.of(files.markdown(), files.json(), files.latestMarkdown(), files.latestJson())) {
        helper.assertTrue(Files.isRegularFile(file) && file.getParent().equals(directory), "Missing or misplaced report file " + file);
      }
      String name = files.markdown().getFileName().toString();
      helper.assertTrue(name.matches("verify-\\d{8}-\\d{6}(-\\d+)?\\.md"), "Unexpected file name " + name);
      helper.assertTrue(files.json().getFileName().toString().equals(name.replace(".md", ".json")), "The two reports have different names");
      helper.assertTrue(files.latestMarkdown().getFileName().toString().equals("verify-latest.md") && files.latestJson().getFileName().toString().equals("verify-latest.json"), "Unexpected names of the latest reports");
      helper.assertTrue(read(files.latestJson()).equals(read(files.json())) && read(files.latestMarkdown()).equals(read(files.markdown())), "The latest reports are not copies");

      helper.assertTrue(report.get("format").getAsInt() == VerifyReport.JSON_FORMAT, "Unexpected format number");
      helper.assertTrue(List.copyOf(report.keySet()).equals(List.of("format", "run", "scanned", "item_bans", "integrated_loot", "rolls", "summary", "origins", "findings")), "Unexpected top-level keys " + report.keySet());
      JsonObject findings = report.getAsJsonObject("findings");
      helper.assertTrue(List.copyOf(findings.keySet()).equals(List.of("load_failure", "skipped_by_condition", "legacy_folder", "missing_reference", "banned_item", "cannot_produce", "roll_error", "always_empty_in_rolls", "no_integrated_loot", "integrated_loot_data", "internal_error")), "Unexpected finding classes " + findings.keySet());
      helper.assertTrue(report.equals(VerifyReport.json(sharedResult)), "The file is not the JSON form of the result");

      JsonObject scanned = report.getAsJsonObject("scanned");
      int loaded = helper.getLevel().getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE).size();
      helper.assertTrue(scanned.get("loaded_tables").getAsInt() == loaded, "Not every loaded loot table was scanned");
      helper.assertTrue(scanned.get("table_files").getAsInt() > loaded && scanned.get("structure_templates").getAsInt() > 1000 && scanned.get("worldgen_files").getAsInt() > 500 && scanned.get("legacy_table_files").getAsInt() == 2, "Unexpected scan figures " + scanned);

      // The summary counts what the lists hold.
      for (JsonElement element : report.getAsJsonArray("summary")) {
        JsonObject row = element.getAsJsonObject();
        JsonElement list = findings.get(row.get("class").getAsString());
        if (list.isJsonArray() && List.of("load_failure", "skipped_by_condition", "missing_reference", "banned_item", "roll_error", "always_empty_in_rolls", "internal_error").contains(row.get("class").getAsString())) {
          helper.assertTrue(row.get("count").getAsInt() == list.getAsJsonArray().size(), "The summary row " + row + " does not match its list");
        }
      }

      JsonObject origin = report.getAsJsonObject("origins").getAsJsonObject(TEST_ORIGIN);
      helper.assertTrue(origin != null && origin.get("type").getAsString().equals("mod") && origin.get("name").getAsString().equals("Lootr Remastered Game Tests") && strings(origin.getAsJsonArray("packs")).equals(List.of(TEST_ORIGIN)), "The test data pack is not attributed to the test mod: " + origin);
      JsonObject vanilla = report.getAsJsonObject("origins").getAsJsonObject("minecraft");
      helper.assertTrue(vanilla != null && vanilla.get("version").getAsString().equals("1.21.1") && strings(vanilla.getAsJsonArray("packs")).contains("vanilla"), "The vanilla pack is not attributed to Minecraft: " + vanilla);

      String markdown = read(files.markdown());
      for (String heading : List.of("# Lootr loot table verification", "## Summary", "## A. Loot table files that were not loaded (`load_failure`)", "## B. Loot tables in the old folder (`legacy_folder`)", "## C. References to loot tables that do not exist (`missing_reference`)", "## D. Banned items in loot tables (`banned_item`, info)", "## E. Loot tables that cannot produce anything (`cannot_produce`)", "## F. Structure containers without integrated loot (`no_integrated_loot`)", "## G. Integrated loot data (`integrated_loot_data`)", "## Internal errors (`internal_error`)")) {
        helper.assertTrue(markdown.lines().anyMatch(heading::equals), "The Markdown report lacks the heading: " + heading);
      }
      helper.assertTrue(markdown.contains("- Complete data: `" + files.json().getFileName() + "`"), "The Markdown report does not name the JSON report");
      helper.assertTrue(markdown.contains("**Lootr Remastered Game Tests (lootr_gametest "), "The Markdown report is not grouped by mod");
      // Lootr's built-in rule files for mods that are not installed are more than one group lists.
      int builtIn = all(findings(report, "integrated_loot_data"), issue -> issue.get("origin").getAsString().equals("lootr")).size();
      helper.assertTrue(builtIn > VerifyReport.GROUP_LIMIT, "Expected more issues of Lootr's built-in data than one group lists but got " + builtIn);
      helper.assertTrue(markdown.lines().anyMatch(("- ... and " + (builtIn - VerifyReport.GROUP_LIMIT) + " more; the JSON report lists all " + builtIn + ".")::equals), "The group that is too long was not cut off, or the report does not say by how much");
    });
  }

  // ----- A: load_failure and skipped_by_condition -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void loadFailuresAreClassified(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      Collection<ResourceLocation> loaded = helper.getLevel().getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE);
      for (String name : List.of("unknown_item", "invalid_json", "other_failure", "condition_fabric", "condition_neoforge", "condition_unknown")) {
        helper.assertTrue(!loaded.contains(ResourceLocation.parse(NAMESPACE + "verify/" + name)), "The fixture verify/" + name + " was loaded, so it shows nothing");
      }
      JsonArray failures = findings(report, "load_failure");
      JsonObject unknownItem = one(failures, "table", NAMESPACE + "verify/unknown_item");
      helper.assertTrue(unknownItem.get("kind").getAsString().equals("unknown_item") && strings(unknownItem.getAsJsonArray("unknown_items")).equals(List.of("absent_mod:gem", "lootr_gametest:no_such_item")), "Unexpected finding " + unknownItem);
      helper.assertTrue(unknownItem.get("origin").getAsString().equals(TEST_ORIGIN) && unknownItem.get("file").getAsString().equals("data/lootr_gametest/loot_table/verify/unknown_item.json") && !unknownItem.get("error").getAsString().isBlank(), "Unexpected finding " + unknownItem);

      JsonObject invalidJson = one(failures, "table", NAMESPACE + "verify/invalid_json");
      helper.assertTrue(invalidJson.get("kind").getAsString().equals("invalid_json") && !invalidJson.get("error").getAsString().isBlank(), "Unexpected finding " + invalidJson);

      JsonObject other = one(failures, "table", NAMESPACE + "verify/other_failure");
      helper.assertTrue(other.get("kind").getAsString().equals("other") && other.get("error").getAsString().contains("no_such_entry_type") && other.getAsJsonArray("unknown_items").isEmpty(), "Unexpected finding " + other);

      // Nothing that is loaded is listed, and a file with an unmet condition is no failure.
      for (JsonElement element : failures) {
        String table = element.getAsJsonObject().get("table").getAsString();
        helper.assertTrue(!loaded.contains(ResourceLocation.parse(table)) && !table.contains("verify/condition_"), "Listed as a load failure without being one: " + table);
      }
      helper.assertTrue(failures.size() == 3, "Expected exactly the three fixtures but got " + failures);

      JsonArray skipped = findings(report, "skipped_by_condition");
      JsonObject fabric = one(skipped, "table", NAMESPACE + "verify/condition_fabric");
      helper.assertTrue(fabric.get("key").getAsString().equals("fabric:load_conditions") && fabric.get("result").getAsString().equals("false"), "Unexpected finding " + fabric);
      // Its item is unknown as well; the unmet condition explains the file, so it is no error.
      JsonObject neoforge = one(skipped, "table", NAMESPACE + "verify/condition_neoforge");
      helper.assertTrue(neoforge.get("key").getAsString().equals("neoforge:conditions") && neoforge.get("result").getAsString().equals("false"), "Unexpected finding " + neoforge);
      JsonObject unknown = one(skipped, "table", NAMESPACE + "verify/condition_unknown");
      helper.assertTrue(unknown.get("key").getAsString().equals("fabric:load_conditions") && unknown.get("result").getAsString().equals("unknown"), "Unexpected finding " + unknown);
      helper.assertTrue(skipped.size() == 3, "Expected exactly the three fixtures but got " + skipped);

      helper.assertTrue(count(report, "load_failure", "error") == 3 && count(report, "skipped_by_condition", "info") == 3, "The summary does not count them");
    });
  }

  // ----- B: legacy_folder -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void legacyFolderIsReportedPerPack(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      JsonArray folders = findings(report, "legacy_folder");
      helper.assertTrue(folders.size() == 1, "Expected one pack with files in the old folder but got " + folders);
      JsonObject folder = one(folders, "origin", TEST_ORIGIN);
      helper.assertTrue(folder.get("count").getAsInt() == 2 && folder.get("also_in_current_folder").getAsInt() == 1, "Unexpected counts " + folder);
      JsonArray files = folder.getAsJsonArray("files");
      helper.assertTrue(one(files, "table", NAMESPACE + "verify/legacy_and_current").get("in_current_folder").getAsBoolean(), "The file that exists in both folders is not marked");
      helper.assertTrue(!one(files, "table", NAMESPACE + "verify/legacy_only").get("in_current_folder").getAsBoolean(), "The file that exists only in the old folder is marked as current");
      helper.assertTrue(count(report, "legacy_folder", "warning") == 2, "The summary does not count the files");
      Collection<ResourceLocation> loaded = helper.getLevel().getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE);
      helper.assertTrue(!loaded.contains(ResourceLocation.parse(NAMESPACE + "verify/legacy_only")) && loaded.contains(ResourceLocation.parse(NAMESPACE + "verify/legacy_and_current")), "The game reads the old folder after all");
    });
  }

  // ----- C: missing_reference -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void missingReferencesAreFoundInTablesTemplatesAndWorldgen(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      JsonArray missing = findings(report, "missing_reference");
      String table = NAMESPACE + "verify/references";
      String template = NAMESPACE + "verify/containers";
      // C1: loaded loot tables.
      assertReference(helper, missing, "verify/missing_nested", "loot_table", "nested_table", List.of(), table, false);
      assertReference(helper, missing, "verify/unknown_item", "loot_table", "nested_table", List.of(), table, true);
      assertReference(helper, missing, "verify/missing_set_loot_table", "loot_table", "set_loot_table", List.of(), table, false);
      assertReference(helper, missing, "verify/missing_component_loot", "loot_table", "container_loot_component", List.of(), table, false);
      // C2: structure templates.
      assertReference(helper, missing, "verify/missing_in_structure", "structure", "container", List.of("minecraft:chest"), template, false);
      assertReference(helper, missing, "verify/missing_vault_loot", "structure", "vault", List.of("minecraft:vault"), template, false);
      assertReference(helper, missing, "verify/missing_spawner_loot", "structure", "trial_spawner", List.of("minecraft:trial_spawner"), template, false);
      assertReference(helper, missing, "verify/missing_equipment_loot", "structure", "equipment", List.of("minecraft:trial_spawner"), template, false);
      assertReference(helper, missing, "verify/missing_item_loot", "structure", "container_item", List.of("minecraft:barrel"), template, false);
      assertReference(helper, missing, "verify/missing_death_loot", "structure", "entity_death", List.of("minecraft:zombie"), template, false);
      // C3: worldgen files.
      assertReference(helper, missing, "verify/missing_in_worldgen", "worldgen", "processor_list", List.of("minecraft:barrel"), NAMESPACE + "processor_list/verify_loot", false);

      // Existing tables are not listed, and Minecraft's own data refers to nothing that is missing.
      helper.assertTrue(missing.size() == 11, "Expected exactly the eleven fixtures but got " + missing);
      for (JsonElement element : missing) {
        helper.assertTrue(element.getAsJsonObject().get("origin").getAsString().equals(TEST_ORIGIN), "A reference outside the test data is reported: " + element);
      }
      helper.assertTrue(count(report, "missing_reference", "error") == 11, "The summary does not count them");

      // A template that cannot be read is an internal error of its own and stops nothing.
      JsonArray errors = findings(report, "internal_error");
      JsonObject corrupt = one(errors, "id", NAMESPACE + "verify/corrupt");
      helper.assertTrue(corrupt.get("stage").getAsString().equals("structure_templates") && !corrupt.get("error").getAsString().isBlank(), "Unexpected internal error " + corrupt);
      helper.assertTrue(errors.size() == 1 && count(report, "internal_error", "error") == 1, "Unexpected internal errors " + errors);
    });
  }

  // ----- D: banned_item -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void bannedItemsAreListed(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      JsonObject bans = report.getAsJsonObject("item_bans");
      helper.assertTrue(bans.get("active").getAsBoolean() && strings(bans.getAsJsonArray("sources")).contains("gametest:verify"), "The ban source of the batch was not seen: " + bans);
      JsonArray banned = findings(report, "banned_item");
      JsonObject mixed = one(banned, "table", NAMESPACE + "verify/banned");
      helper.assertTrue(strings(mixed.getAsJsonArray("items")).equals(List.of("minecraft:jigsaw")), "Unexpected banned items " + mixed);
      JsonObject tag = one(mixed.getAsJsonArray("tags"), "tag", NAMESPACE + "verify/mixed");
      helper.assertTrue(strings(tag.getAsJsonArray("banned")).equals(List.of("minecraft:jigsaw")) && tag.get("size").getAsInt() == 2, "Unexpected banned tag " + tag);
      helper.assertTrue(strings(one(banned, "table", NAMESPACE + "verify/only_banned").getAsJsonArray("items")).equals(List.of("minecraft:jigsaw")), "The table with only a banned item is not listed");
      helper.assertTrue(banned.size() == 2 && count(report, "banned_item", "info") == 2, "Expected exactly the two fixtures but got " + banned);
    });
  }

  // ----- E: cannot_produce, roll_error, always_empty_in_rolls -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void tablesThatCannotProduceAreJudgedAndRolled(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      JsonArray cannot = findings(report, "cannot_produce");
      Map<String, String> reasons = Map.of("verify/no_pools", "no_pools", "verify/only_empty", "only_empty_entries", "verify/only_banned", "only_banned_items", "verify/only_dead_references", "only_unproductive_references");
      reasons.forEach((name, reason) -> {
        JsonObject finding = one(cannot, "table", NAMESPACE + name);
        helper.assertTrue(finding.get("reason").getAsString().equals(reason) && finding.get("severity").getAsString().equals("warning") && strings(finding.getAsJsonArray("used_by")).equals(List.of("minecraft:chest")) && finding.get("type").getAsString().equals("minecraft:chest"), "Unexpected finding " + finding);
      });
      int warnings = 0;
      int infos = 0;
      int vanillaInfos = 0;
      for (JsonElement element : cannot) {
        JsonObject finding = element.getAsJsonObject();
        String table = finding.get("table").getAsString();
        helper.assertTrue(!table.equals("minecraft:empty") && !table.endsWith("verify/never_drops") && !table.endsWith("verify/throws_when_rolled") && !table.endsWith("verify/banned") && !table.endsWith("verify/references"), "Listed without reason: " + finding);
        if (finding.get("severity").getAsString().equals("warning")) {
          warnings++;
        } else {
          infos++;
          vanillaInfos += finding.get("origin").getAsString().equals("minecraft") ? 1 : 0;
          helper.assertTrue(finding.getAsJsonArray("used_by").isEmpty(), "A table a container uses is only a note: " + finding);
        }
      }
      // Minecraft itself has loot tables without pools (some mobs drop nothing); no structure uses them.
      helper.assertTrue(warnings == 4 && vanillaInfos > 0, "Expected the four fixtures as warnings and Minecraft's empty tables as notes but got " + warnings + " and " + infos);
      helper.assertTrue(count(report, "cannot_produce", "warning") == 4 && count(report, "cannot_produce", "info") == infos, "The summary does not count them");
      String markdown = read(sharedResult.files().markdown());
      helper.assertTrue(markdown.contains("| Minecraft (minecraft 1.21.1) | " + vanillaInfos + " |") && !markdown.contains("`minecraft:entities/"), "The notes are not reported as a count per origin");

      JsonObject rolls = report.getAsJsonObject("rolls");
      helper.assertTrue(rolls.get("per_table").getAsInt() == 3 && rolls.get("tables_rolled").getAsInt() > 50 && rolls.get("rolls_done").getAsInt() > 150, "Unexpected roll figures " + rolls);
      helper.assertTrue(rolls.get("skipped_cannot_produce").getAsInt() == 4, "The tables that cannot produce were rolled: " + rolls);
      // integrated/source_map creates an explorer map in every roll, and so does Minecraft's shipwreck map chest.
      helper.assertTrue(rolls.get("maps_suppressed").getAsInt() >= 6, "Explorer maps were not suppressed: " + rolls);

      JsonArray errors = findings(report, "roll_error");
      JsonObject thrown = one(errors, "table", NAMESPACE + "verify/throws_when_rolled");
      helper.assertTrue(thrown.get("exception").getAsString().equals("java.util.NoSuchElementException") && thrown.get("type").getAsString().equals("minecraft:generic"), "Unexpected finding " + thrown);
      helper.assertTrue(errors.size() == 1, "Expected exactly one roll error but got " + errors);

      JsonArray empty = findings(report, "always_empty_in_rolls");
      JsonObject never = one(empty, "table", NAMESPACE + "verify/never_drops");
      helper.assertTrue(never.get("rolls").getAsInt() == 3 && strings(never.getAsJsonArray("used_by")).equals(List.of("minecraft:chest")), "Unexpected finding " + never);
      helper.assertTrue(empty.size() == 1, "Expected exactly one always empty table but got " + empty);

      // integrated/target_a is extended and was rolled three times; the rolls went past integrated loot.
      IntegratedLoot.Stats stats = IntegratedLoot.stats();
      helper.assertTrue(stats.extensions() == statsBeforeSharedRun.extensions() && stats.sourceRolls() == statsBeforeSharedRun.sourceRolls() && stats.suppressedMaps() == statsBeforeSharedRun.suppressedMaps(), "The sample rolls were extended by integrated loot");
    });
  }

  // ----- F: no_integrated_loot -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void integratedLootCoverageOfStructureContainersIsReported(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      JsonObject containers = report.getAsJsonObject("findings").getAsJsonObject("no_integrated_loot");
      JsonArray tables = containers.getAsJsonArray("tables");
      String template = NAMESPACE + "verify/containers";

      JsonObject extended = one(tables, "table", NAMESPACE + "integrated/target_a");
      helper.assertTrue(extended.get("status").getAsString().equals("extended") && extended.get("rule_file").getAsString().equals("lootr_gametest:basic") && extended.get("detail").getAsString().equals("pool lootr_gametest:bcd, rolls 2"), "Unexpected entry " + extended);
      helper.assertTrue(strings(extended.getAsJsonArray("used_by")).equals(List.of("minecraft:chest")) && strings(extended.getAsJsonArray("examples")).equals(List.of(template)) && extended.get("origin").getAsString().equals(TEST_ORIGIN), "Unexpected entry " + extended);

      JsonObject excluded = one(tables, "table", NAMESPACE + "integrated/prio/excluded");
      helper.assertTrue(excluded.get("status").getAsString().equals("excluded") && excluded.get("rule_file").getAsString().equals("lootr_gametest:prio_high") && strings(excluded.getAsJsonArray("used_by")).equals(List.of("minecraft:barrel")), "Unexpected entry " + excluded);

      JsonObject problem = one(tables, "table", NAMESPACE + "integrated/target_missing_pool");
      helper.assertTrue(problem.get("status").getAsString().equals("rule_problem") && problem.get("detail").getAsString().equals("pool lootr_gametest:no_such_pool does not exist"), "Unexpected entry " + problem);

      JsonObject pot = one(tables, "table", NAMESPACE + "verify/pot_only");
      helper.assertTrue(pot.get("status").getAsString().equals("not_applicable") && strings(pot.getAsJsonArray("used_by")).equals(List.of("minecraft:decorated_pot")), "Unexpected entry " + pot);

      JsonObject plain = one(tables, "table", NAMESPACE + "integrated/plain");
      helper.assertTrue(plain.get("status").getAsString().equals("no_rule") && plain.get("rule_file").getAsString().isEmpty(), "Unexpected entry " + plain);

      // From the worldgen file; a table without a type.
      JsonObject untyped = one(tables, "table", NAMESPACE + "integrated/untyped_target");
      helper.assertTrue(untyped.get("status").getAsString().equals("extended") && untyped.get("type").getAsString().equals("minecraft:generic") && strings(untyped.getAsJsonArray("examples")).equals(List.of(NAMESPACE + "processor_list/verify_loot")), "Unexpected entry " + untyped);

      // The chest minecart of the fixture: a container entity counts as a structure container.
      JsonObject mineshaft = one(tables, "table", "minecraft:chests/abandoned_mineshaft");
      helper.assertTrue(mineshaft.get("origin").getAsString().equals("minecraft") && strings(mineshaft.getAsJsonArray("used_by")).contains("minecraft:chest_minecart"), "Unexpected entry " + mineshaft);

      // Missing tables and tables of another type are not part of the coverage.
      helper.assertTrue(find(tables, "table", NAMESPACE + "verify/missing_in_structure").isEmpty() && find(tables, "table", "minecraft:archaeology/desert_pyramid").isEmpty(), "A table that cannot be extended is listed");
      helper.assertTrue(report.getAsJsonObject("integrated_loot").get("container_tables_of_other_types").getAsInt() >= 1, "The archaeology table of the fixture was not counted");

      // Every status is what the rules in force say about the table, whoever ships them: the built-in rules of
      // Lootr decide on Minecraft's chests, the rules of the test data on the fixtures.
      IntegratedLootSnapshot snapshot = IntegratedLoot.snapshot();
      int withoutRule = 0;
      Map<String, int[]> perOrigin = new TreeMap<>();
      List<String> order = List.of("extended", "excluded", "rule_problem", "not_applicable", "no_rule");
      for (JsonElement element : tables) {
        JsonObject table = element.getAsJsonObject();
        String status = table.get("status").getAsString();
        helper.assertTrue(order.contains(status), "Unknown status in " + table);
        Optional<Decision> decision = snapshot.decisionFor(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(table.get("table").getAsString())));
        if (decision.isEmpty()) {
          helper.assertTrue((status.equals("no_rule") || status.equals("not_applicable")) && table.get("rule_file").getAsString().isEmpty(), "A table without a decision is reported with one: " + table);
        } else {
          String expected = decision.get().exclusion() ? "excluded" : decision.get().active() ? "extended" : "rule_problem";
          helper.assertTrue(status.equals(expected) && table.get("rule_file").getAsString().equals(decision.get().ruleFile().toString()), "The status does not follow from the decision " + decision.get() + ": " + table);
        }
        perOrigin.computeIfAbsent(table.get("origin").getAsString(), ignored -> new int[5])[order.indexOf(status)]++;
        withoutRule += status.equals("no_rule") ? 1 : 0;
      }
      JsonArray coverage = containers.getAsJsonArray("coverage");
      helper.assertTrue(coverage.size() == perOrigin.size(), "The coverage does not have one row per origin");
      for (JsonElement element : coverage) {
        JsonObject row = element.getAsJsonObject();
        int[] expected = perOrigin.get(row.get("origin").getAsString());
        int sum = 0;
        for (int i = 0; i < order.size(); i++) {
          helper.assertTrue(row.get(order.get(i)).getAsInt() == expected[i], "The coverage row " + row + " does not match the tables");
          sum += expected[i];
        }
        helper.assertTrue(row.get("tables").getAsInt() == sum, "The coverage row " + row + " does not add up");
      }
      JsonObject own = one(coverage, "origin", TEST_ORIGIN);
      helper.assertTrue(own.get("excluded").getAsInt() == 1 && own.get("rule_problem").getAsInt() == 1 && own.get("not_applicable").getAsInt() == 1 && own.get("extended").getAsInt() == 2 && own.get("no_rule").getAsInt() == 8, "Unexpected coverage of the test data " + own);
      helper.assertTrue(count(report, "no_integrated_loot", "info") == withoutRule, "The summary does not count the tables without a rule");
    });
  }

  // ----- G: integrated_loot_data -----

  @GameTest(template = EMPTY_STRUCTURE, batch = RUN_BATCH)
  public void integratedLootDiagnosticsAreReported(GameTestHelper helper) {
    withSharedRun(helper, report -> {
      JsonArray issues = findings(report, "integrated_loot_data");
      assertIssue(helper, issues, "malformed_file", "error", "rule lootr_gametest:malformed", "");
      assertIssue(helper, issues, "malformed_file", "error", "pool lootr_gametest:broken_syntax", "not readable as JSON");
      assertIssue(helper, issues, "inactive_rule_file", "info", "rule lootr_gametest:requires_missing", "lootr_gametest_absent_mod");
      assertIssue(helper, issues, "unmatched_pattern", "warning", "rule lootr_gametest:basic", "lootr_gametest:integrated/does_not_exist");
      assertIssue(helper, issues, "dropped_source", "info", "pool lootr_gametest:dropped", "lootr_gametest:integrated/no_such_table");
      assertIssue(helper, issues, "unusable_pool", "warning", "pool lootr_gametest:unusable", "");
      assertIssue(helper, issues, "ineligible_match", "warning", "rule lootr_gametest:basic", "lootr_gametest:integrated/entity_target of type minecraft:entity");
      // Whatever makes a rule file inactive is shown: here a required namespace that no loot table has.
      assertIssue(helper, issues, "inactive_rule_file", "info", "rule lootr_gametest_ns:ns_missing", "missingNamespaces [lootr_gametest_absent_namespace]");
      // Every issue belongs to the pack that ships the file: the test data pack, or Lootr for its built-in rules.
      for (JsonElement element : issues) {
        JsonObject issue = element.getAsJsonObject();
        String file = issue.get("id").getAsString();
        String expected = file.startsWith("rule lootr:") || file.startsWith("pool lootr:") ? "lootr" : TEST_ORIGIN;
        helper.assertTrue(issue.get("origin").getAsString().equals(expected), "An issue is not attributed to the pack of its file: " + issue);
      }
      int total = count(report, "integrated_loot_data", "error") + count(report, "integrated_loot_data", "warning") + count(report, "integrated_loot_data", "info");
      helper.assertTrue(total == issues.size(), "The summary does not count the issues");
      JsonObject integrated = report.getAsJsonObject("integrated_loot");
      helper.assertTrue(integrated.get("enabled").getAsBoolean() && integrated.get("extended_tables").getAsInt() == IntegratedLoot.snapshot().extensionCount() && integrated.get("rule_files").getAsInt() == IntegratedLoot.snapshot().ruleFiles().size(), "Unexpected figures " + integrated);
    });
  }

  // ----- determinism -----

  // Two runs over the same data report the same, entry for entry; only the header of the run differs.
  @GameTest(template = EMPTY_STRUCTURE, batch = REPEAT_BATCH)
  public void twoRunsOfTheSameDataReportTheSame(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    VerifyResult before = LootVerifier.lastResult().orElse(null);
    List<String> output = new ArrayList<>();

    run(server, "lootr verify rolls 2", 2, output);
    helper.assertTrue(LootVerifier.isRunning(), "The command started nothing: " + output);
    waitForRun(server);
    VerifyResult first = LootVerifier.lastResult().orElse(null);
    helper.assertTrue(!LootVerifier.isRunning() && first != null && first != before && first.files() != null, "The first run left no result: " + output);

    run(server, "lootr verify rolls 2", 2, output);
    helper.assertTrue(LootVerifier.isRunning(), "The second command started nothing: " + output);
    waitForRun(server);
    VerifyResult second = LootVerifier.lastResult().orElse(null);
    helper.assertTrue(!LootVerifier.isRunning() && second != null && second != first && second.files() != null, "The second run left no result: " + output);

    helper.assertTrue(!second.files().json().equals(first.files().json()) && Files.isRegularFile(first.files().json()), "The second run overwrote the report of the first");
    JsonObject a = JsonParser.parseString(read(first.files().json())).getAsJsonObject();
    JsonObject b = JsonParser.parseString(read(second.files().json())).getAsJsonObject();
    for (String key : List.of("scanned", "item_bans", "integrated_loot", "rolls", "summary", "origins", "findings")) {
      helper.assertTrue(a.get(key).equals(b.get(key)), "The two runs differ in '" + key + "'");
    }
    helper.assertTrue(read(second.files().latestJson()).equals(read(second.files().json())), "The latest report is not the one of the second run");
    List<String> firstLines = read(first.files().markdown()).lines().filter(line -> !line.startsWith("- Started ") && !line.startsWith("- Complete data: ")).toList();
    List<String> secondLines = read(second.files().markdown()).lines().filter(line -> !line.startsWith("- Started ") && !line.startsWith("- Complete data: ")).toList();
    helper.assertTrue(firstLines.equals(secondLines), "The Markdown reports differ outside their header");
    // No ban source in this batch: banned items are not checked at all.
    helper.assertTrue(!b.getAsJsonObject("item_bans").get("active").getAsBoolean() && findings(b, "banned_item").isEmpty() && find(findings(b, "cannot_produce"), "table", NAMESPACE + "verify/only_banned").isEmpty(), "Banned items were reported without a ban source");
    helper.succeed();
  }

  // ----- pure parts -----

  // What happens at server start is decided from the two system properties alone.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void startupDecisionFollowsTheProperties(GameTestHelper helper) {
    helper.assertTrue(LootVerifier.VERIFY_PROPERTY.equals("lootr.verify") && LootVerifier.ROLLS_PROPERTY.equals("lootr.verify.rolls"), "The property names changed");

    // `true`: run, never stop.
    for (boolean dedicated : new boolean[]{true, false}) {
      helper.assertTrue(LootVerifier.startupPlan("true", null, dedicated).equals(new StartupPlan(true, false, 0, List.of())), "Unexpected plan for 'true'");
      helper.assertTrue(LootVerifier.startupPlan(" TRUE ", "", dedicated).equals(new StartupPlan(true, false, 0, List.of())), "The value is not trimmed or is case-sensitive");
    }
    // `exit`: run, and stop a dedicated server only.
    helper.assertTrue(LootVerifier.startupPlan("exit", null, true).equals(new StartupPlan(true, true, 0, List.of())), "Unexpected plan for 'exit' on a dedicated server");
    helper.assertTrue(LootVerifier.startupPlan("exit", null, false).equals(new StartupPlan(true, false, 0, List.of())), "'exit' would stop an integrated server");
    helper.assertTrue(LootVerifier.startupPlan("Exit", "7", true).equals(new StartupPlan(true, true, 7, List.of())), "Unexpected plan for 'Exit' with rolls");

    // Unset, empty and `false`: nothing, without a word. The rolls alone start nothing.
    for (String unset : new String[]{null, "", "   ", "false", "FALSE"}) {
      for (String rolls : new String[]{null, "5", "garbage"}) {
        helper.assertTrue(LootVerifier.startupPlan(unset, rolls, true).equals(new StartupPlan(false, false, 0, List.of())), "Unexpected plan for '" + unset + "' with rolls '" + rolls + "'");
      }
    }
    // Garbage: nothing, with one warning that names the value.
    for (String garbage : new String[]{"yes", "1", "exit now", "true,exit", "exti"}) {
      StartupPlan plan = LootVerifier.startupPlan(garbage, "5", true);
      helper.assertTrue(!plan.run() && !plan.stopServer() && plan.rolls() == 0, "Garbage '" + garbage + "' started something: " + plan);
      helper.assertTrue(plan.warnings().size() == 1 && plan.warnings().getFirst().contains("lootr.verify=" + garbage), "Garbage '" + garbage + "' was not reported: " + plan.warnings());
    }

    // The roll count: a number within the limits, otherwise limited or ignored with a warning.
    helper.assertTrue(LootVerifier.startupPlan("true", "12", false).equals(new StartupPlan(true, false, 12, List.of())), "The roll count was not read");
    helper.assertTrue(LootVerifier.startupPlan("true", " 3 ", false).rolls() == 3 && LootVerifier.startupPlan("true", "0", false).rolls() == 0, "The roll count is not trimmed");
    for (String garbage : new String[]{"many", "1.5", "0x10", "2147483648"}) {
      StartupPlan plan = LootVerifier.startupPlan("exit", garbage, true);
      helper.assertTrue(plan.run() && plan.stopServer() && plan.rolls() == 0 && plan.warnings().size() == 1 && plan.warnings().getFirst().contains("lootr.verify.rolls=" + garbage), "Unexpected plan for the roll count '" + garbage + "': " + plan);
    }
    StartupPlan tooMany = LootVerifier.startupPlan("true", "5000", false);
    helper.assertTrue(tooMany.run() && tooMany.rolls() == VerifyOptions.MAX_ROLLS && tooMany.warnings().size() == 1, "A huge roll count is not limited: " + tooMany);
    StartupPlan negative = LootVerifier.startupPlan("true", "-4", false);
    helper.assertTrue(negative.run() && negative.rolls() == 0 && negative.warnings().size() == 1, "A negative roll count is not limited: " + negative);

    // The test server was started without the property, so nothing ran on its own.
    helper.assertTrue(System.getProperty(LootVerifier.VERIFY_PROPERTY) == null, "The game tests are expected to run without -Dlootr.verify");
    helper.succeed();
  }

  // The command is for operators and takes 0 to 100 rolls; anything else starts nothing.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void commandNeedsPermissionAndAValidRollCount(GameTestHelper helper) {
    MinecraftServer server = helper.getLevel().getServer();
    helper.assertTrue(!LootVerifier.isRunning(), "A verification is active outside its batch");
    for (String command : new String[]{"lootr verify rolls 101", "lootr verify rolls -1", "lootr verify rolls", "lootr verify rolls many", "lootr verify 3"}) {
      List<String> output = new ArrayList<>();
      run(server, command, 2, output);
      helper.assertTrue(!LootVerifier.isRunning() && !output.isEmpty() && output.stream().noneMatch(line -> line.contains("started")), "'" + command + "' was accepted: " + output);
    }
    List<String> output = new ArrayList<>();
    run(server, "lootr verify", 1, output);
    helper.assertTrue(!LootVerifier.isRunning() && output.stream().noneMatch(line -> line.contains("started")), "A source without operator permission started a verification: " + output);

    helper.assertTrue(new VerifyOptions(-5, Path.of("x"), null).rolls() == 0 && new VerifyOptions(1000, Path.of("x"), null).rolls() == VerifyOptions.MAX_ROLLS && VerifyOptions.of(7).rolls() == 7, "The options do not keep the rolls inside their limits");
    helper.succeed();
  }

  // The Markdown report groups by origin, orders the groups by name, cuts every group off after the limit and says
  // so; the JSON report has everything; the time of the run is in the header only.
  @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
  public void markdownIsGroupedCappedAndStable(GameTestHelper helper) {
    int many = VerifyReport.GROUP_LIMIT + 5;
    VerifyResult result = syntheticResult(Instant.parse("2026-01-02T03:04:05Z"), 1500L, many);
    String markdown = VerifyReport.markdown(result);
    List<String> lines = markdown.lines().toList();

    int big = lines.indexOf("**Alpha Mod (zeta 1.0)**: " + many);
    int small = lines.indexOf("**Pack file/b.zip**: 1");
    helper.assertTrue(big != -1 && small != -1 && big < small, "The groups are missing or not ordered by the name of the origin");
    helper.assertTrue(lines.stream().filter(line -> line.startsWith("- `zeta:table_")).count() == VerifyReport.GROUP_LIMIT, "The big group is not cut off at the limit");
    helper.assertTrue(lines.contains("- ... and 5 more; the JSON report lists all " + many + "."), "The report does not say how many findings it left out");
    helper.assertTrue(lines.contains("- `zeta:table_00` (unknown_item): unknown items `absent:gem`: Failed to parse") && !markdown.contains("zeta:table_" + (many - 1)), "The group does not start with its first finding or does not end at the limit");
    helper.assertTrue(lines.contains("- `other:table` (invalid_json): Unterminated object"), "The small group is incomplete");
    helper.assertTrue(lines.contains("| `load_failure` | error | " + (many + 1) + " |") && lines.contains("| Alpha Mod (zeta 1.0) | " + many + " | 0 |"), "The summary tables are wrong");

    JsonObject json = VerifyReport.json(result);
    helper.assertTrue(findings(json, "load_failure").size() == many + 1, "The JSON report is not complete");
    helper.assertTrue(json.equals(VerifyReport.json(result)) && markdown.equals(VerifyReport.markdown(result)), "Rendering the same result twice gives different reports");

    // Another time and duration change the header and nothing else.
    VerifyResult later = syntheticResult(Instant.parse("2027-06-07T08:09:10Z"), 99000L, many);
    List<String> laterLines = VerifyReport.markdown(later).lines().toList();
    helper.assertTrue(lines.size() == laterLines.size(), "The reports have different lengths");
    for (int i = 0; i < lines.size(); i++) {
      boolean same = lines.get(i).equals(laterLines.get(i));
      helper.assertTrue(same == !lines.get(i).startsWith("- Started "), "Line " + i + " " + (same ? "does not show" : "depends on") + " the time of the run: " + lines.get(i));
    }
    helper.assertTrue(lines.contains("- Started 2026-01-02 03:04:05 +00:00, took 1.5 s"), "Unexpected header");
    JsonObject laterJson = VerifyReport.json(later);
    helper.assertTrue(!json.get("run").equals(laterJson.get("run")), "The JSON header does not show the time of the run");
    json.remove("run");
    laterJson.remove("run");
    helper.assertTrue(json.equals(laterJson) && !json.toString().contains("2026-01-02"), "The time of the run appears outside the JSON header");
    helper.succeed();
  }

  // ----- helpers -----

  private record SingleItemBan(String name, Item item) implements ItemBanSource {
    @Override
    public boolean isBanned(ItemStack stack) {
      return stack.is(item);
    }
  }

  private static VerifyResult syntheticResult(Instant startedAt, long durationMillis, int failuresOfBigGroup) {
    Map<String, OriginInfo> origins = new TreeMap<>();
    // The key sorts last, the name first: groups are ordered by name.
    origins.put("zeta", new OriginInfo(PackOrigin.mod("zeta", "Alpha Mod", "1.0"), List.of("zeta")));
    origins.put("pack:file/b.zip", new OriginInfo(PackOrigin.pack("file/b.zip"), List.of("file/b.zip")));
    List<LoadFailure> failures = new ArrayList<>();
    failures.add(new LoadFailure("other:table", "pack:file/b.zip", "data/other/loot_table/table.json", VerifyResult.INVALID_JSON, "Unterminated object", List.of(), ""));
    for (int i = 0; i < failuresOfBigGroup; i++) {
      failures.add(new LoadFailure(String.format("zeta:table_%02d", i), "zeta", String.format("data/zeta/loot_table/table_%02d.json", i), VerifyResult.UNKNOWN_ITEM, "Failed to parse", List.of("absent:gem"), ""));
    }
    return new VerifyResult(startedAt, ZoneOffset.UTC, "2.0.0", "1.21.1", durationMillis, List.of(), new Scanned(10, 10 + failures.size(), 0, 0, 0), new BanInfo(false, false, List.of()), new IntegratedInfo(true, 0, 0, 0, 0, 0, 0), new RollInfo(0, 0, 0, 0, 0, 0, List.of()), origins, failures, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
  }

  /**
   * Hands the JSON report of the run of {@link #RUN_BATCH}, read back from its file, to the assertions.
   */
  private static void withSharedRun(GameTestHelper helper, Consumer<JsonObject> assertions) {
    if (sharedReport == null) {
      VerifyResult result = LootVerifier.lastResult().orElse(null);
      if (LootVerifier.isRunning() || result == null || result == resultBeforeSharedRun || result.files() == null) {
        throw new GameTestAssertException("The shared run did not finish or left no report files. Command output: " + FIRST_COMMAND);
      }
      sharedResult = result;
      sharedReport = JsonParser.parseString(read(result.files().json())).getAsJsonObject();
    }
    assertions.accept(sharedReport);
    helper.succeed();
  }

  /**
   * Lets the server work on its tasks until no verification is active. The test server ticks as fast as it can, so
   * a number of ticks says nothing about how long a run may take; this waits in real time instead. The run needs
   * the server thread for its sample rolls and to report its end, both of which are server tasks.
   */
  private static void waitForRun(MinecraftServer server) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RUN_TIMEOUT_SECONDS);
    server.managedBlock(() -> !LootVerifier.isRunning() || System.nanoTime() > deadline);
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new GameTestAssertException("Could not read " + file + ": " + e);
    }
  }

  /**
   * Runs a command with the given permission level and collects every line it prints, also later ones.
   */
  private static void run(MinecraftServer server, String command, int permission, List<String> lines) {
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
    CommandSourceStack source = server.createCommandSourceStack().withSource(output).withPermission(permission);
    server.getCommands().performPrefixedCommand(source, command);
  }

  private static JsonArray findings(JsonObject report, String findingClass) {
    return report.getAsJsonObject("findings").getAsJsonArray(findingClass);
  }

  private static int count(JsonObject report, String findingClass, String severity) {
    for (JsonElement element : report.getAsJsonArray("summary")) {
      JsonObject row = element.getAsJsonObject();
      if (row.get("class").getAsString().equals(findingClass) && row.get("severity").getAsString().equals(severity)) {
        return row.get("count").getAsInt();
      }
    }
    throw new GameTestAssertException("The summary has no row for " + findingClass + " (" + severity + ")");
  }

  private static List<JsonObject> all(JsonArray array, Predicate<JsonObject> filter) {
    List<JsonObject> result = new ArrayList<>();
    for (JsonElement element : array) {
      if (filter.test(element.getAsJsonObject())) {
        result.add(element.getAsJsonObject());
      }
    }
    return result;
  }

  private static Optional<JsonObject> find(JsonArray array, String key, String value) {
    return all(array, json -> json.get(key).getAsString().equals(value)).stream().findFirst();
  }

  private static JsonObject one(JsonArray array, String key, String value) {
    List<JsonObject> found = all(array, json -> json.get(key).getAsString().equals(value));
    if (found.size() != 1) {
      throw new GameTestAssertException("Expected exactly one entry with " + key + " " + value + " but found " + found.size() + " in " + array);
    }
    return found.getFirst();
  }

  private static List<String> strings(JsonArray array) {
    List<String> result = new ArrayList<>();
    array.forEach(element -> result.add(element.getAsString()));
    return result;
  }

  private static void assertReference(GameTestHelper helper, JsonArray missing, String name, String source, String kind, List<String> holders, String example, boolean fileExists) {
    JsonObject reference = one(missing, "table", NAMESPACE + name);
    helper.assertTrue(reference.get("source").getAsString().equals(source) && reference.get("kind").getAsString().equals(kind) && reference.get("count").getAsInt() == 1, "Unexpected reference " + reference);
    helper.assertTrue(strings(reference.getAsJsonArray("holders")).equals(holders) && strings(reference.getAsJsonArray("examples")).equals(List.of(example)) && reference.get("file_exists").getAsBoolean() == fileExists, "Unexpected reference " + reference);
  }

  private static void assertIssue(GameTestHelper helper, JsonArray issues, String kind, String severity, String id, String detailPart) {
    List<JsonObject> found = all(issues, issue -> issue.get("kind").getAsString().equals(kind) && issue.get("id").getAsString().equals(id) && issue.get("detail").getAsString().contains(detailPart));
    helper.assertTrue(found.size() == 1 && found.getFirst().get("severity").getAsString().equals(severity) && !found.getFirst().get("detail").getAsString().isBlank(), "Expected one " + kind + " issue (" + severity + ") for " + id + " mentioning '" + detailPart + "' but found " + found);
  }
}
