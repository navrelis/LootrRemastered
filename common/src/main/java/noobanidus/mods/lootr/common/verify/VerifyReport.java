package noobanidus.mods.lootr.common.verify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import noobanidus.mods.lootr.common.verify.VerifyResult.AlwaysEmpty;
import noobanidus.mods.lootr.common.verify.VerifyResult.BannedItems;
import noobanidus.mods.lootr.common.verify.VerifyResult.BannedTag;
import noobanidus.mods.lootr.common.verify.VerifyResult.CannotProduce;
import noobanidus.mods.lootr.common.verify.VerifyResult.ContainerTable;
import noobanidus.mods.lootr.common.verify.VerifyResult.Coverage;
import noobanidus.mods.lootr.common.verify.VerifyResult.IntegratedLootIssue;
import noobanidus.mods.lootr.common.verify.VerifyResult.InternalError;
import noobanidus.mods.lootr.common.verify.VerifyResult.LegacyFile;
import noobanidus.mods.lootr.common.verify.VerifyResult.LegacyFolder;
import noobanidus.mods.lootr.common.verify.VerifyResult.LoadFailure;
import noobanidus.mods.lootr.common.verify.VerifyResult.MissingReference;
import noobanidus.mods.lootr.common.verify.VerifyResult.OriginInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.ReportFiles;
import noobanidus.mods.lootr.common.verify.VerifyResult.RollError;
import noobanidus.mods.lootr.common.verify.VerifyResult.RollInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.SkippedByCondition;
import noobanidus.mods.lootr.common.verify.VerifyResult.SlowRoll;
import noobanidus.mods.lootr.common.verify.VerifyResult.SummaryRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renders a {@link VerifyResult}: a Markdown report for people, grouped by the responsible mod or pack and capped
 * per group, and a JSON report with everything. Both keep the order of the result, and the time of the run appears
 * only in their header, so that two reports of the same data differ in nothing but that.
 */
public final class VerifyReport {
  /**
   * How many findings of one group the Markdown report lists; it says how many it left out.
   */
  public static final int GROUP_LIMIT = 25;
  public static final int JSON_FORMAT = 1;

  private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
  private static final DateTimeFormatter HEADER_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private VerifyReport() {
  }

  // ----- files -----

  /**
   * Writes `verify-&lt;time&gt;.md` and `.json` plus `verify-latest.md` and `.json` into the directory.
   *
   * @return the result with the files it was written to.
   */
  static VerifyResult write(VerifyResult result, Path directory) throws IOException {
    Files.createDirectories(directory);
    String base = "verify-" + FILE_STAMP.format(result.startedAt().atZone(result.zone()));
    // Two runs within the same second must not overwrite each other.
    String name = base;
    for (int number = 2; Files.exists(directory.resolve(name + ".md")) || Files.exists(directory.resolve(name + ".json")); number++) {
      name = base + "-" + number;
    }
    ReportFiles files = new ReportFiles(directory.resolve(name + ".md"), directory.resolve(name + ".json"), directory.resolve("verify-latest.md"), directory.resolve("verify-latest.json"));
    VerifyResult written = result.withFiles(files, result.notes());
    String markdown = markdown(written);
    String json = GSON.toJson(json(written)) + "\n";
    Files.writeString(files.markdown(), markdown, StandardCharsets.UTF_8);
    Files.writeString(files.json(), json, StandardCharsets.UTF_8);
    Files.writeString(files.latestMarkdown(), markdown, StandardCharsets.UTF_8);
    Files.writeString(files.latestJson(), json, StandardCharsets.UTF_8);
    return written;
  }

  // ----- summary for the chat and the log -----

  /**
   * @return a few lines that say how the run went and where the report is.
   */
  public static List<String> summary(VerifyResult result) {
    List<String> lines = new ArrayList<>();
    lines.add("Loot table verification finished in " + seconds(result.durationMillis()) + ": " + result.total(VerifyResult.ERROR) + " errors, " + result.total(VerifyResult.WARNING) + " warnings, " + result.total(VerifyResult.INFO) + " notes.");
    if (result.lootRollsEmptied()) {
      lines.add(emptiedRolls(result));
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (SummaryRow row : result.summary()) {
      if (row.count() > 0 && !row.severity().equals(VerifyResult.INFO)) {
        counts.merge(row.category(), row.count(), Integer::sum);
      }
    }
    if (!counts.isEmpty()) {
      lines.add("Errors and warnings: " + counts.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).collect(Collectors.joining(", ")) + ".");
    }
    Coverage total = totalCoverage(result);
    if (total.tables() > 0) {
      lines.add("Integrated loot: " + total.extended() + " of " + total.tables() + " structure container loot tables are extended, " + total.noRule() + " have no rule.");
    }
    lines.add(structureLine(result, true));
    result.notes().forEach(lines::add);
    if (result.files() != null) {
      lines.add("Report: " + result.files().markdown());
    }
    return lines;
  }

  /**
   * @return the line of the summary about the structures with problem containers, with the path of the report if there is one.
   */
  static String structureLine(VerifyResult result, boolean withPath) {
    VerifyResult.Structures structures = result.structures();
    return "Structures with problem containers: " + structures.problems().size() + " (" + structures.missingTables() + " container tables missing, " + structures.emptyTables() + " empty)"
        + (!withPath || result.files() == null ? "" : " \u2014 see " + result.files().markdown());
  }

  private static Coverage totalCoverage(VerifyResult result) {
    int[] sum = new int[6];
    for (Coverage row : result.coverage()) {
      sum[0] += row.tables();
      sum[1] += row.extended();
      sum[2] += row.excluded();
      sum[3] += row.ruleProblem();
      sum[4] += row.notApplicable();
      sum[5] += row.noRule();
    }
    return new Coverage("", sum[0], sum[1], sum[2], sum[3], sum[4], sum[5]);
  }

  private static String seconds(long millis) {
    return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
  }

  /**
   * What there is to say when {@link VerifyResult#lootRollsEmptied()}: one statement at the top instead of one
   * finding per loot table that nobody would trace back to a common cause.
   */
  private static String emptiedRolls(VerifyResult result) {
    return "Loot rolls are being emptied: " + result.alwaysEmptyInRolls().size() + " of " + result.tablesSampled() + " sampled loot tables produced nothing in any roll, although each of them has entries that can."
        + " Something takes the loot out of the rolls: most likely mods that change how loot tables are rolled and get in each other's way, or an item ban that covers nearly everything."
        + " To narrow it down, set 'item_bans.filter_all_loot' to false in Lootr's configuration and run the verification again."
        + " Until this is solved the findings 'always_empty_in_rolls' say nothing about the single loot tables.";
  }

  // ----- Markdown -----

  public static String markdown(VerifyResult result) {
    StringBuilder out = new StringBuilder();
    out.append("# Lootr loot table verification\n\n");
    if (result.lootRollsEmptied()) {
      out.append("**Warning: ").append(emptiedRolls(result)).append("**\n\n");
    }
    header(out, result);

    out.append("\n## Summary\n\n| Finding class | Severity | Count |\n|---|---|---:|\n");
    for (SummaryRow row : result.summary()) {
      out.append("| `").append(row.category()).append("` | ").append(row.severity()).append(" | ").append(row.count()).append(" |\n");
    }
    byOrigin(out, result);

    out.append("\n## A. Loot table files that were not loaded (`load_failure`)\n\n");
    out.append("Files under `loot_table/` that are not in the game's registry, with what the game's own parser says about them.\n");
    groups(out, result, result.loadFailures(), LoadFailure::origin, VerifyReport::line);
    out.append("\n### Skipped by a load condition (`skipped_by_condition`, info)\n");
    groups(out, result, result.skippedByCondition(), SkippedByCondition::origin, VerifyReport::line);

    out.append("\n## B. Loot tables in the old folder (`legacy_folder`)\n\n");
    out.append("Files under `loot_tables/`, the folder the game read before 1.21. The game ignores them.\n");
    legacy(out, result);

    out.append("\n## C. References to loot tables that do not exist (`missing_reference`)\n\n");
    out.append("From loaded loot tables (nested tables, `set_loot_table`, the container loot component), structure templates and worldgen files. Grouped by who refers to the missing table.\n");
    groups(out, result, result.missingReferences(), MissingReference::origin, VerifyReport::line);

    out.append("\n## D. Banned items in loot tables (`banned_item`, info)\n\n");
    if (!result.itemBans().active()) {
      out.append("Not checked: no item ban source is active.\n");
    } else {
      out.append("Item and tag entries of loaded loot tables that name a banned item, and tables that reach one through the tables they refer to (nested table entries). Banned items are removed from loot rolls.\n");
      groups(out, result, result.bannedItems(), BannedItems::origin, VerifyReport::line);
    }

    out.append("\n## E. Loot tables that cannot produce anything (`cannot_produce`)\n\n");
    out.append("Judged from the entries alone: no pools, only empty entries, only banned items, or only references to tables that cannot produce either. Conditions and functions are not evaluated.\n");
    out.append("\n### Used by a structure container (warning)\n");
    groups(out, result, result.cannotProduce().stream().filter(finding -> finding.severity().equals(VerifyResult.WARNING)).toList(), CannotProduce::origin, VerifyReport::line);
    out.append("\n### Not used by a structure container (info)\n\n");
    countsByOrigin(out, result, result.cannotProduce().stream().filter(finding -> finding.severity().equals(VerifyResult.INFO)).map(CannotProduce::origin).toList());
    rolls(out, result);

    out.append("\n## F. Structure containers without integrated loot (`no_integrated_loot`)\n\n");
    coverage(out, result);

    out.append("\n## G. Integrated loot data (`integrated_loot_data`)\n\n");
    out.append("What the integrated loot engine says about its own pool and rule files.\n");
    groups(out, result, result.integratedLootIssues(), IntegratedLootIssue::origin, VerifyReport::line);

    out.append("\n## H. Structures with problem containers (`structure_containers`)\n\n");
    structures(out, result);

    if (!result.internalErrors().isEmpty()) {
      out.append("\n## Internal errors (`internal_error`)\n\n");
      out.append("What the verification itself could not do. Everything else in this report is unaffected.\n\n");
      limited(out, result.internalErrors(), VerifyReport::line);
    }
    return out.toString();
  }

  private static void header(StringBuilder out, VerifyResult result) {
    out.append("- Lootr Remastered ").append(result.modVersion()).append(" on Minecraft ").append(result.gameVersion()).append('\n');
    out.append("- Started ").append(HEADER_STAMP.format(result.startedAt().atZone(result.zone()))).append(", took ").append(seconds(result.durationMillis())).append('\n');
    if (result.files() != null) {
      out.append("- Complete data: `").append(result.files().json().getFileName()).append("`\n");
    }
    VerifyResult.Scanned scanned = result.scanned();
    out.append("- Scanned: ").append(scanned.loadedTables()).append(" loaded loot tables, ").append(scanned.tableFiles()).append(" loot table files, ").append(scanned.templates()).append(" structure templates, ").append(scanned.worldgenFiles()).append(" worldgen files, ").append(scanned.legacyTableFiles()).append(" files in the old `loot_tables` folder\n");
    VerifyResult.BanInfo bans = result.itemBans();
    out.append("- Item bans: ");
    if (bans.active()) {
      out.append("active (").append(String.join(", ", bans.sources())).append(bans.filterAllLoot() ? "); every loot roll is filtered\n" : "); only Lootr containers are filtered\n");
    } else {
      out.append("not active\n");
    }
    VerifyResult.IntegratedInfo integrated = result.integratedLoot();
    out.append("- Integrated loot: ").append(integrated.enabled() ? "enabled" : "switched off in the configuration").append("; ").append(integrated.extended()).append(" loot tables extended, ").append(integrated.excluded()).append(" excluded; ").append(integrated.activeRuleFiles()).append(" of ").append(integrated.ruleFiles()).append(" rule files active, ").append(integrated.pools()).append(" pools\n");
    RollInfo rolls = result.rolls();
    out.append("- Sample rolls: ");
    if (rolls.requested() == 0) {
      out.append("none (`/lootr verify rolls <n>` rolls every structure and `chests/` loot table n times)\n");
    } else {
      out.append(rolls.requested()).append(" per table, ").append(rolls.tablesRolled()).append(" tables, ").append(rolls.rollsDone()).append(" rolls\n");
    }
    for (String note : result.notes()) {
      out.append("- Note: ").append(note).append('\n');
    }
  }

  // Errors and warnings per origin, so that the mods that need attention are seen at a glance.
  private static void byOrigin(StringBuilder out, VerifyResult result) {
    Map<String, int[]> counts = new TreeMap<>();
    result.loadFailures().forEach(finding -> counts.computeIfAbsent(finding.origin(), ignored -> new int[2])[0]++);
    result.missingReferences().forEach(finding -> counts.computeIfAbsent(finding.origin(), ignored -> new int[2])[0]++);
    result.legacyFolders().forEach(finding -> counts.computeIfAbsent(finding.origin(), ignored -> new int[2])[1] += finding.count());
    result.cannotProduce().stream().filter(finding -> finding.severity().equals(VerifyResult.WARNING)).forEach(finding -> counts.computeIfAbsent(finding.origin(), ignored -> new int[2])[1]++);
    result.rollErrors().forEach(finding -> counts.computeIfAbsent(finding.origin(), ignored -> new int[2])[1]++);
    result.alwaysEmptyInRolls().stream().filter(finding -> finding.severity().equals(VerifyResult.WARNING)).forEach(finding -> counts.computeIfAbsent(finding.origin(), ignored -> new int[2])[1]++);
    for (IntegratedLootIssue issue : result.integratedLootIssues()) {
      if (!issue.severity().equals(VerifyResult.INFO)) {
        counts.computeIfAbsent(issue.origin(), ignored -> new int[2])[issue.severity().equals(VerifyResult.ERROR) ? 0 : 1]++;
      }
    }
    if (counts.isEmpty()) {
      return;
    }
    out.append("\n### Errors and warnings by origin\n\n| Origin | Errors | Warnings |\n|---|---:|---:|\n");
    for (String origin : ordered(result, counts.keySet())) {
      out.append("| ").append(label(result, origin)).append(" | ").append(counts.get(origin)[0]).append(" | ").append(counts.get(origin)[1]).append(" |\n");
    }
  }

  private static void legacy(StringBuilder out, VerifyResult result) {
    if (result.legacyFolders().isEmpty()) {
      out.append("\nNone.\n");
      return;
    }
    Map<String, LegacyFolder> byOrigin = new TreeMap<>();
    result.legacyFolders().forEach(folder -> byOrigin.put(folder.origin(), folder));
    for (String origin : ordered(result, byOrigin.keySet())) {
      LegacyFolder folder = byOrigin.get(origin);
      out.append("\n**").append(label(result, origin)).append("**: ").append(folder.count()).append(" files, ").append(folder.alsoInCurrentFolder()).append(" of them also in `loot_table/`\n\n");
      limited(out, folder.files(), VerifyReport::line);
    }
  }

  private static void rolls(StringBuilder out, VerifyResult result) {
    RollInfo rolls = result.rolls();
    out.append("\n### Sample rolls (`roll_error`, `always_empty_in_rolls`)\n\n");
    if (rolls.requested() == 0) {
      out.append("No loot table was rolled.\n");
      return;
    }
    out.append("Every loot table that a structure container uses and every `chests/` loot table was rolled ").append(rolls.requested()).append(" times at the world spawn, without integrated loot and without explorer maps (").append(rolls.mapsSuppressed()).append(" maps skipped). Not rolled: ").append(rolls.skippedOtherType()).append(" tables whose type needs more than a position, ").append(rolls.skippedNoProducing()).append(" listed above as unable to produce.\n");
    out.append("\n#### A roll threw an exception (`roll_error`, warning)\n");
    groups(out, result, result.rollErrors(), RollError::origin, VerifyReport::line);
    out.append("\n#### No roll produced an item: used by a structure container (`always_empty_in_rolls`, warning)\n");
    groups(out, result, result.alwaysEmptyInRolls().stream().filter(finding -> finding.severity().equals(VerifyResult.WARNING)).toList(), AlwaysEmpty::origin, VerifyReport::line);
    out.append("\n#### No roll produced an item: not used by a structure container (`always_empty_in_rolls`, info)\n\n");
    out.append("A few rolls cannot tell a rare drop from none, least of all for a table that is only a part of other tables.\n\n");
    countsByOrigin(out, result, result.alwaysEmptyInRolls().stream().filter(finding -> finding.severity().equals(VerifyResult.INFO)).map(AlwaysEmpty::origin).toList());
    if (!rolls.slowTables().isEmpty()) {
      out.append("\n#### Rolls cut short because one roll took very long\n\n");
      limited(out, rolls.slowTables(), VerifyReport::line);
    }
  }

  private static void structures(StringBuilder out, VerifyResult result) {
    VerifyResult.Structures structures = result.structures();
    out.append("Containers in structure templates whose loot table is missing (`missing_reference`), cannot produce an item (`cannot_produce`) or produced nothing in any sample roll (`always_empty_in_rolls`), listed per structure with every template and holder.\n");
    out.append("\nA template belongs to a structure if the structure's start pool leads to it through pool elements and the jigsaw blocks of the templates. ").append(structures.jigsawStructures()).append(" jigsaw structures were followed; ").append(structures.attributedTemplates()).append(" of ").append(result.scanned().templates()).append(" templates belong to one of them. Templates that no jigsaw structure reaches (the mansion, the stronghold, structures that place templates from code) are listed as unattributed, grouped by namespace and the first part of the template path. Containers in worldgen files (processor lists) are not part of this view.\n");
    if (result.rolls().requested() == 0) {
      out.append("\nNo loot table was rolled, so `always_empty_in_rolls` is not part of it. `/lootr verify rolls <n>` adds it.\n");
    }
    out.append("\n").append(structureLine(result, false)).append(".\n");
    for (VerifyResult.StructureProblem problem : structures.problems()) {
      out.append("\n### ").append(code(problem.structure())).append(problem.attributed() ? "" : " (unattributed)").append(": ").append(problem.missing()).append(" missing, ").append(problem.empty()).append(" empty\n\n");
      for (VerifyResult.ContainerProblem container : problem.containers()) {
        out.append("- ").append(line(container)).append('\n');
      }
    }
  }

  private static void coverage(StringBuilder out, VerifyResult result) {
    Coverage total = totalCoverage(result);
    out.append("Loot tables of an extendable type (chest, vault, untyped) that a container in a structure template or a worldgen file uses: ").append(total.tables()).append(". ");
    out.append(result.integratedLoot().otherTypeContainerTables()).append(" more are of another type (archaeology, for example) and are never extended.\n");
    if (total.tables() == 0) {
      return;
    }
    out.append("\n| Origin of the loot table | Tables | Extended | Excluded | Rule problem | Not applicable | No rule |\n|---|---:|---:|---:|---:|---:|---:|\n");
    Map<String, Coverage> byOrigin = new TreeMap<>();
    result.coverage().forEach(row -> byOrigin.put(row.origin(), row));
    for (String origin : ordered(result, byOrigin.keySet())) {
      coverageRow(out, label(result, origin), byOrigin.get(origin));
    }
    coverageRow(out, "**Total**", total);
    out.append("\n\"Not applicable\": only decorated pots or brushable blocks use the table, which have no room for extra stacks.\n");

    out.append("\n### Without a rule (`no_rule`, info)\n");
    groups(out, result, withStatus(result, VerifyResult.NO_RULE), ContainerTable::origin, VerifyReport::line);
    out.append("\n### With a rule that has no effect (`rule_problem`)\n");
    groups(out, result, withStatus(result, VerifyResult.RULE_PROBLEM), ContainerTable::origin, VerifyReport::line);
    out.append("\n### Excluded by a rule (`excluded`)\n");
    groups(out, result, withStatus(result, VerifyResult.EXCLUDED), ContainerTable::origin, VerifyReport::line);
  }

  private static void coverageRow(StringBuilder out, String label, Coverage row) {
    out.append("| ").append(label).append(" | ").append(row.tables()).append(" | ").append(row.extended()).append(" | ").append(row.excluded()).append(" | ").append(row.ruleProblem()).append(" | ").append(row.notApplicable()).append(" | ").append(row.noRule()).append(" |\n");
  }

  private static List<ContainerTable> withStatus(VerifyResult result, String status) {
    return result.containerTables().stream().filter(table -> table.status().equals(status)).toList();
  }

  private static void countsByOrigin(StringBuilder out, VerifyResult result, List<String> origins) {
    if (origins.isEmpty()) {
      out.append("None.\n");
      return;
    }
    Map<String, Integer> counts = new TreeMap<>();
    origins.forEach(origin -> counts.merge(origin, 1, Integer::sum));
    out.append("| Origin | Loot tables |\n|---|---:|\n");
    for (String origin : ordered(result, counts.keySet())) {
      out.append("| ").append(label(result, origin)).append(" | ").append(counts.get(origin)).append(" |\n");
    }
  }

  // ----- Markdown: groups and lines -----

  /**
   * Writes the findings grouped by origin, the groups ordered by the origin's name, each group cut off after
   * {@link #GROUP_LIMIT} findings.
   */
  private static <T> void groups(StringBuilder out, VerifyResult result, List<T> findings, Function<T, String> origin, Function<T, String> line) {
    if (findings.isEmpty()) {
      out.append("\nNone.\n");
      return;
    }
    Map<String, List<T>> byOrigin = new TreeMap<>();
    findings.forEach(finding -> byOrigin.computeIfAbsent(origin.apply(finding), ignored -> new ArrayList<>()).add(finding));
    for (String key : ordered(result, byOrigin.keySet())) {
      List<T> group = byOrigin.get(key);
      out.append("\n**").append(label(result, key)).append("**: ").append(group.size()).append("\n\n");
      limited(out, group, line);
    }
  }

  private static <T> void limited(StringBuilder out, List<T> findings, Function<T, String> line) {
    for (int i = 0; i < findings.size() && i < GROUP_LIMIT; i++) {
      out.append("- ").append(line.apply(findings.get(i))).append('\n');
    }
    if (findings.size() > GROUP_LIMIT) {
      out.append("- ... and ").append(findings.size() - GROUP_LIMIT).append(" more; the JSON report lists all ").append(findings.size()).append(".\n");
    }
  }

  private static List<String> ordered(VerifyResult result, Collection<String> origins) {
    List<String> keys = new ArrayList<>(origins);
    keys.sort(Comparator.comparing((String key) -> label(result, key).toLowerCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder()));
    return keys;
  }

  private static String label(VerifyResult result, String origin) {
    return origin.isEmpty() ? "Unknown origin" : result.originLabel(origin);
  }

  private static String code(String text) {
    return "`" + text.replace('`', '\'') + "`";
  }

  private static String codes(List<String> texts) {
    return texts.stream().map(VerifyReport::code).collect(Collectors.joining(", "));
  }

  private static String line(LoadFailure finding) {
    StringBuilder line = new StringBuilder(code(finding.table())).append(" (").append(finding.kind()).append(")");
    if (!finding.unknownItems().isEmpty()) {
      line.append(": unknown items ").append(codes(finding.unknownItems()));
    }
    if (!finding.error().isEmpty()) {
      line.append(": ").append(finding.error());
    }
    if (!finding.note().isEmpty()) {
      line.append(" ").append(finding.note());
    }
    return line.toString();
  }

  private static String line(SkippedByCondition finding) {
    return code(finding.table()) + ": " + code(finding.key()) + (finding.result().equals("false") ? " are not met" : " could not be evaluated here; the file itself parses");
  }

  private static String line(LegacyFile file) {
    return code(file.table()) + (file.inCurrentFolder() ? " (also in `loot_table/`)" : " (only here: the game does not load it)");
  }

  private static String line(MissingReference finding) {
    StringBuilder line = new StringBuilder(code(finding.table())).append(finding.fileExists() ? " (has a file but was not loaded)" : "").append(": ").append(finding.count()).append(" x ").append(finding.kind()).append(" in ").append(finding.source());
    if (!finding.holders().isEmpty()) {
      line.append(", held by ").append(codes(finding.holders()));
    }
    return line.append(", e.g. ").append(codes(finding.examples())).toString();
  }

  private static String line(BannedItems finding) {
    List<String> parts = new ArrayList<>();
    if (!finding.items().isEmpty()) {
      parts.add("items " + codes(finding.items()));
    }
    for (BannedTag tag : finding.tags()) {
      parts.add("tag " + code("#" + tag.tag()) + " (" + tag.banned().size() + " of " + tag.size() + " banned: " + codes(tag.banned()) + ")");
    }
    if (parts.isEmpty()) {
      parts.add("banned items of nested tables");
    }
    return code(finding.table()) + ": " + String.join("; ", parts) + (finding.via().isEmpty() ? "" : " (through " + codes(finding.via()) + ")");
  }

  private static String line(CannotProduce finding) {
    return code(finding.table()) + " (" + finding.type() + "): " + finding.reason() + ", used by " + codes(finding.usedBy());
  }

  private static String line(RollError finding) {
    return code(finding.table()) + " (" + finding.type() + "): " + finding.exception() + (finding.message().isEmpty() ? "" : ": " + finding.message());
  }

  private static String line(AlwaysEmpty finding) {
    return code(finding.table()) + ": empty in " + finding.rolls() + " rolls" + (finding.usedBy().isEmpty() ? "" : ", used by " + codes(finding.usedBy()));
  }

  private static String line(SlowRoll roll) {
    return code(roll.table()) + ": one roll took " + roll.millis() + " ms";
  }

  private static String line(ContainerTable table) {
    StringBuilder line = new StringBuilder(code(table.table()));
    if (!table.ruleFile().isEmpty()) {
      line.append(": rule file ").append(code(table.ruleFile())).append(", ").append(table.detail());
    }
    line.append(table.ruleFile().isEmpty() ? ": " : "; ").append("used by ").append(codes(table.usedBy()));
    if (!table.examples().isEmpty()) {
      line.append(", e.g. ").append(code(table.examples().getFirst()));
    }
    return line.toString();
  }

  private static String line(VerifyResult.ContainerProblem container) {
    return code(container.template()) + ": " + (container.count() == 1 ? "" : container.count() + " x ") + code(container.holder()) + " with " + code(container.table()) + " (" + container.problem() + ")";
  }

  private static String line(IntegratedLootIssue issue) {
    return issue.kind() + " (" + issue.severity() + "): " + code(issue.id()) + ": " + issue.detail();
  }

  private static String line(InternalError error) {
    return code(error.stage()) + (error.id().isEmpty() ? "" : " at " + code(error.id())) + ": " + error.error();
  }

  // ----- JSON -----

  public static JsonObject json(VerifyResult result) {
    JsonObject root = new JsonObject();
    root.addProperty("format", JSON_FORMAT);

    JsonObject run = new JsonObject();
    run.addProperty("started", result.startedAt().atZone(result.zone()).toOffsetDateTime().toString());
    run.addProperty("duration_ms", result.durationMillis());
    run.addProperty("mod_version", result.modVersion());
    run.addProperty("minecraft_version", result.gameVersion());
    run.add("notes", strings(result.notes()));
    root.add("run", run);

    JsonObject scanned = new JsonObject();
    scanned.addProperty("loaded_tables", result.scanned().loadedTables());
    scanned.addProperty("table_files", result.scanned().tableFiles());
    scanned.addProperty("structure_templates", result.scanned().templates());
    scanned.addProperty("worldgen_files", result.scanned().worldgenFiles());
    scanned.addProperty("legacy_table_files", result.scanned().legacyTableFiles());
    root.add("scanned", scanned);

    JsonObject bans = new JsonObject();
    bans.addProperty("active", result.itemBans().active());
    bans.addProperty("filter_all_loot", result.itemBans().filterAllLoot());
    bans.add("sources", strings(result.itemBans().sources()));
    root.add("item_bans", bans);

    JsonObject integrated = new JsonObject();
    integrated.addProperty("enabled", result.integratedLoot().enabled());
    integrated.addProperty("extended_tables", result.integratedLoot().extended());
    integrated.addProperty("excluded_tables", result.integratedLoot().excluded());
    integrated.addProperty("rule_files", result.integratedLoot().ruleFiles());
    integrated.addProperty("active_rule_files", result.integratedLoot().activeRuleFiles());
    integrated.addProperty("pools", result.integratedLoot().pools());
    integrated.addProperty("container_tables_of_other_types", result.integratedLoot().otherTypeContainerTables());
    root.add("integrated_loot", integrated);

    JsonObject rolls = new JsonObject();
    rolls.addProperty("per_table", result.rolls().requested());
    rolls.addProperty("tables_rolled", result.rolls().tablesRolled());
    rolls.addProperty("rolls_done", result.rolls().rollsDone());
    rolls.addProperty("maps_suppressed", result.rolls().mapsSuppressed());
    rolls.addProperty("skipped_other_type", result.rolls().skippedOtherType());
    rolls.addProperty("skipped_cannot_produce", result.rolls().skippedNoProducing());
    rolls.addProperty("tables_sampled", result.tablesSampled());
    rolls.addProperty("emptied", result.lootRollsEmptied());
    rolls.add("slow_tables", array(result.rolls().slowTables(), roll -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", roll.table());
      json.addProperty("millis", roll.millis());
      return json;
    }));
    root.add("rolls", rolls);

    root.add("summary", array(result.summary(), row -> {
      JsonObject json = new JsonObject();
      json.addProperty("class", row.category());
      json.addProperty("severity", row.severity());
      json.addProperty("count", row.count());
      return json;
    }));

    JsonObject origins = new JsonObject();
    for (Map.Entry<String, OriginInfo> entry : result.origins().entrySet()) {
      PackOrigin origin = entry.getValue().origin();
      JsonObject json = new JsonObject();
      json.addProperty("type", origin.type().label());
      json.addProperty("id", origin.id());
      json.addProperty("name", origin.name());
      json.addProperty("version", origin.version());
      json.add("packs", strings(entry.getValue().packs()));
      origins.add(entry.getKey(), json);
    }
    root.add("origins", origins);

    JsonObject findings = new JsonObject();
    findings.add(VerifyResult.LOAD_FAILURE, array(result.loadFailures(), finding -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", finding.table());
      json.addProperty("origin", finding.origin());
      json.addProperty("file", finding.file());
      json.addProperty("kind", finding.kind());
      json.addProperty("error", finding.error());
      json.add("unknown_items", strings(finding.unknownItems()));
      json.addProperty("note", finding.note());
      return json;
    }));
    findings.add(VerifyResult.SKIPPED_BY_CONDITION, array(result.skippedByCondition(), finding -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", finding.table());
      json.addProperty("origin", finding.origin());
      json.addProperty("key", finding.key());
      json.addProperty("result", finding.result());
      return json;
    }));
    findings.add(VerifyResult.LEGACY_FOLDER, array(result.legacyFolders(), folder -> {
      JsonObject json = new JsonObject();
      json.addProperty("origin", folder.origin());
      json.addProperty("count", folder.count());
      json.addProperty("also_in_current_folder", folder.alsoInCurrentFolder());
      json.add("files", array(folder.files(), file -> {
        JsonObject entry = new JsonObject();
        entry.addProperty("table", file.table());
        entry.addProperty("in_current_folder", file.inCurrentFolder());
        return entry;
      }));
      return json;
    }));
    findings.add(VerifyResult.MISSING_REFERENCE, array(result.missingReferences(), finding -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", finding.table());
      json.addProperty("source", finding.source());
      json.addProperty("kind", finding.kind());
      json.addProperty("origin", finding.origin());
      json.addProperty("count", finding.count());
      json.add("examples", strings(finding.examples()));
      json.add("holders", strings(finding.holders()));
      json.addProperty("file_exists", finding.fileExists());
      return json;
    }));
    findings.add(VerifyResult.BANNED_ITEM, array(result.bannedItems(), finding -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", finding.table());
      json.addProperty("origin", finding.origin());
      json.add("items", strings(finding.items()));
      json.add("tags", array(finding.tags(), tag -> {
        JsonObject entry = new JsonObject();
        entry.addProperty("tag", tag.tag());
        entry.add("banned", strings(tag.banned()));
        entry.addProperty("size", tag.size());
        return entry;
      }));
      json.add("via", strings(finding.via()));
      return json;
    }));
    findings.add(VerifyResult.CANNOT_PRODUCE, array(result.cannotProduce(), finding -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", finding.table());
      json.addProperty("origin", finding.origin());
      json.addProperty("type", finding.type());
      json.addProperty("reason", finding.reason());
      json.addProperty("severity", finding.severity());
      json.add("used_by", strings(finding.usedBy()));
      return json;
    }));
    findings.add(VerifyResult.ROLL_ERROR, array(result.rollErrors(), finding -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", finding.table());
      json.addProperty("origin", finding.origin());
      json.addProperty("type", finding.type());
      json.addProperty("exception", finding.exception());
      json.addProperty("message", finding.message());
      return json;
    }));
    findings.add(VerifyResult.ALWAYS_EMPTY_IN_ROLLS, array(result.alwaysEmptyInRolls(), finding -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", finding.table());
      json.addProperty("origin", finding.origin());
      json.addProperty("rolls", finding.rolls());
      json.addProperty("severity", finding.severity());
      json.add("used_by", strings(finding.usedBy()));
      return json;
    }));

    JsonObject containers = new JsonObject();
    containers.add("coverage", array(result.coverage(), row -> {
      JsonObject json = new JsonObject();
      json.addProperty("origin", row.origin());
      json.addProperty("tables", row.tables());
      json.addProperty(VerifyResult.EXTENDED, row.extended());
      json.addProperty(VerifyResult.EXCLUDED, row.excluded());
      json.addProperty(VerifyResult.RULE_PROBLEM, row.ruleProblem());
      json.addProperty(VerifyResult.NOT_APPLICABLE, row.notApplicable());
      json.addProperty(VerifyResult.NO_RULE, row.noRule());
      return json;
    }));
    containers.add("tables", array(result.containerTables(), table -> {
      JsonObject json = new JsonObject();
      json.addProperty("table", table.table());
      json.addProperty("origin", table.origin());
      json.addProperty("type", table.type());
      json.addProperty("status", table.status());
      json.addProperty("rule_file", table.ruleFile());
      json.addProperty("detail", table.detail());
      json.add("used_by", strings(table.usedBy()));
      json.add("examples", strings(table.examples()));
      return json;
    }));
    findings.add(VerifyResult.NO_INTEGRATED_LOOT, containers);

    findings.add(VerifyResult.INTEGRATED_LOOT_DATA, array(result.integratedLootIssues(), issue -> {
      JsonObject json = new JsonObject();
      json.addProperty("kind", issue.kind());
      json.addProperty("severity", issue.severity());
      json.addProperty("id", issue.id());
      json.addProperty("detail", issue.detail());
      json.addProperty("origin", issue.origin());
      return json;
    }));
    findings.add(VerifyResult.INTERNAL_ERROR, array(result.internalErrors(), error -> {
      JsonObject json = new JsonObject();
      json.addProperty("stage", error.stage());
      json.addProperty("id", error.id());
      json.addProperty("error", error.error());
      return json;
    }));
    root.add("findings", findings);

    VerifyResult.Structures structures = result.structures();
    JsonObject section = new JsonObject();
    section.addProperty("jigsaw_structures", structures.jigsawStructures());
    section.addProperty("attributed_templates", structures.attributedTemplates());
    section.addProperty("structures_with_problems", structures.problems().size());
    section.addProperty("missing_tables", structures.missingTables());
    section.addProperty("empty_tables", structures.emptyTables());
    section.add("structures", array(structures.problems(), problem -> {
      JsonObject json = new JsonObject();
      json.addProperty("structure", problem.structure());
      json.addProperty("attributed", problem.attributed());
      json.addProperty("missing", problem.missing());
      json.addProperty("empty", problem.empty());
      json.add("containers", array(problem.containers(), container -> {
        JsonObject entry = new JsonObject();
        entry.addProperty("template", container.template());
        entry.addProperty("holder", container.holder());
        entry.addProperty("table", container.table());
        entry.addProperty("problem", container.problem());
        entry.addProperty("count", container.count());
        return entry;
      }));
      return json;
    }));
    root.add("structure_containers", section);
    return root;
  }

  private static JsonArray strings(List<String> values) {
    JsonArray array = new JsonArray();
    values.forEach(array::add);
    return array;
  }

  private static <T> JsonArray array(List<T> values, Function<T, JsonObject> element) {
    JsonArray array = new JsonArray();
    values.forEach(value -> array.add(element.apply(value)));
    return array;
  }
}
