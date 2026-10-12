package noobanidus.mods.lootr.common.verify;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything one verification run found. Immutable; every list is sorted, so that two results of the same data
 * are equal line by line. {@link VerifyReport} renders it as Markdown and as JSON.
 * <br />
 * Findings name their origin by {@link PackOrigin#key()}; {@link #origins()} resolves the key.
 *
 * @param notes remarks about the run as a whole, e.g. that data packs were reloaded meanwhile.
 * @param files the report files; null if they were not or could not be written.
 */
public record VerifyResult(
    Instant startedAt,
    ZoneId zone,
    String modVersion,
    String gameVersion,
    long durationMillis,
    List<String> notes,
    Scanned scanned,
    BanInfo itemBans,
    IntegratedInfo integratedLoot,
    RollInfo rolls,
    Map<String, OriginInfo> origins,
    List<LoadFailure> loadFailures,
    List<SkippedByCondition> skippedByCondition,
    List<LegacyFolder> legacyFolders,
    List<MissingReference> missingReferences,
    List<BannedItems> bannedItems,
    List<CannotProduce> cannotProduce,
    List<RollError> rollErrors,
    List<AlwaysEmpty> alwaysEmptyInRolls,
    List<ContainerTable> containerTables,
    List<Coverage> coverage,
    List<IntegratedLootIssue> integratedLootIssues,
    List<InternalError> internalErrors,
    @Nullable ReportFiles files) {

  public static final String ERROR = "error";
  public static final String WARNING = "warning";
  public static final String INFO = "info";

  // The finding classes.
  public static final String LOAD_FAILURE = "load_failure";
  public static final String SKIPPED_BY_CONDITION = "skipped_by_condition";
  public static final String LEGACY_FOLDER = "legacy_folder";
  public static final String MISSING_REFERENCE = "missing_reference";
  public static final String BANNED_ITEM = "banned_item";
  public static final String CANNOT_PRODUCE = "cannot_produce";
  public static final String ROLL_ERROR = "roll_error";
  public static final String ALWAYS_EMPTY_IN_ROLLS = "always_empty_in_rolls";
  public static final String NO_INTEGRATED_LOOT = "no_integrated_loot";
  public static final String INTEGRATED_LOOT_DATA = "integrated_loot_data";
  public static final String INTERNAL_ERROR = "internal_error";

  // Values of LoadFailure#kind.
  public static final String UNKNOWN_ITEM = "unknown_item";
  public static final String INVALID_JSON = "invalid_json";
  public static final String OTHER = "other";

  // Values of ContainerTable#status.
  public static final String EXTENDED = "extended";
  public static final String EXCLUDED = "excluded";
  public static final String RULE_PROBLEM = "rule_problem";
  public static final String NOT_APPLICABLE = "not_applicable";
  public static final String NO_RULE = "no_rule";

  // Values of IntegratedLootIssue#kind.
  public static final String MALFORMED_FILE = "malformed_file";
  public static final String INACTIVE_RULE_FILE = "inactive_rule_file";
  public static final String UNMATCHED_PATTERN = "unmatched_pattern";
  public static final String DROPPED_SOURCE = "dropped_source";
  public static final String UNUSABLE_POOL = "unusable_pool";
  public static final String INELIGIBLE_MATCH = "ineligible_match";

  // The thresholds of lootRollsEmptied().
  public static final int EMPTIED_MIN_TABLES = 20;
  public static final int EMPTIED_PERCENT = 90;

  /**
   * @param loadedTables     loot tables in the registry.
   * @param tableFiles       loot table ids found as files under `loot_table/`.
   * @param templates        structure templates under `structure/`.
   * @param worldgenFiles    JSON files under `worldgen/`.
   * @param legacyTableFiles files under the ignored folder `loot_tables/`.
   */
  public record Scanned(int loadedTables, int tableFiles, int templates, int worldgenFiles, int legacyTableFiles) {
  }

  public record BanInfo(boolean active, boolean filterAllLoot, List<String> sources) {
  }

  /**
   * @param otherTypeContainerTables loot tables that structure containers use but whose type cannot be extended
   *                                 (e.g. archaeology); they are not part of the coverage figures.
   */
  public record IntegratedInfo(boolean enabled, int extended, int excluded, int ruleFiles, int activeRuleFiles, int pools, int otherTypeContainerTables) {
  }

  /**
   * @param requested          rolls per table; 0 means that nothing was rolled.
   * @param skippedOtherType   candidates that were not rolled because their type needs more than a position.
   * @param skippedNoProducing candidates that were not rolled because they are listed as `cannot_produce` anyway.
   * @param slowTables         tables whose rolls were cut short because one roll took very long.
   */
  public record RollInfo(int requested, int tablesRolled, int rollsDone, int mapsSuppressed, int skippedOtherType, int skippedNoProducing, List<SlowRoll> slowTables) {
  }

  public record SlowRoll(String table, long millis) {
  }

  /**
   * @param packs the ids of the packs that were attributed to this origin.
   */
  public record OriginInfo(PackOrigin origin, List<String> packs) {
  }

  /**
   * @param file         the path of the file inside its pack.
   * @param kind         {@link #UNKNOWN_ITEM}, {@link #INVALID_JSON} or {@link #OTHER}.
   * @param error        what parsing the file again with the game's codec says; empty if that succeeded.
   * @param unknownItems for `unknown_item`: the item ids the file names that are not registered.
   */
  public record LoadFailure(String table, String origin, String file, String kind, String error, List<String> unknownItems, String note) {
  }

  /**
   * @param key    the JSON key that carries the conditions, e.g. `fabric:load_conditions`.
   * @param result `false` if the conditions evaluate to false, `unknown` if they could not be evaluated here.
   */
  public record SkippedByCondition(String table, String origin, String key, String result) {
  }

  /**
   * Loot tables of one origin under `loot_tables/`, the folder the game read before 1.21.
   *
   * @param alsoInCurrentFolder how many of them exist under `loot_table/` as well.
   */
  public record LegacyFolder(String origin, int count, int alsoInCurrentFolder, List<LegacyFile> files) {
  }

  public record LegacyFile(String table, boolean inCurrentFolder) {
  }

  /**
   * References to one missing loot table from one origin.
   *
   * @param source     `loot_table`, `structure` or `worldgen`.
   * @param kind       what refers to it, e.g. `nested_table`, `container`, `entity_death` or `processor_list`.
   * @param count      how many references there are.
   * @param examples   up to five referencing loot tables, templates or worldgen files.
   * @param holders    the block entities, entities or blocks that carry the reference (structures and worldgen).
   * @param fileExists true if there is a file for the table: it exists but was not loaded.
   */
  public record MissingReference(String table, String source, String kind, String origin, int count, List<String> examples, List<String> holders, boolean fileExists) {
  }

  /**
   * @param items the banned items the table names itself.
   * @param tags  the item tags it names that have banned members.
   * @param via   the loot tables it refers to (nested table entries) that lead to a banned item, directly or through
   *              other nested tables; empty if the table does not reach one that way.
   */
  public record BannedItems(String table, String origin, List<String> items, List<BannedTag> tags, List<String> via) {
  }

  /**
   * @param size how many items the tag holds.
   */
  public record BannedTag(String tag, List<String> banned, int size) {
  }

  /**
   * @param reason   `no_pools`, `only_empty_entries`, `only_banned_items`, `only_unproductive_references` or
   *                 `no_producing_entry`.
   * @param severity warning if a structure container uses the table, info otherwise.
   * @param usedBy   the containers that use it.
   */
  public record CannotProduce(String table, String origin, String type, String reason, String severity, List<String> usedBy) {
  }

  public record RollError(String table, String origin, String type, String exception, String message) {
  }

  /**
   * @param usedBy the structure containers that use it.
   */
  public record AlwaysEmpty(String table, String origin, int rolls, List<String> usedBy) {
    /**
     * @return warning if a structure container uses the table, info otherwise: a few rolls cannot tell a rare drop
     * from none, least of all for a table that is only a part of other tables.
     */
    public String severity() {
      return usedBy.isEmpty() ? INFO : WARNING;
    }
  }

  /**
   * A loot table of an extendable type that a structure container uses.
   *
   * @param status   {@link #EXTENDED}, {@link #EXCLUDED}, {@link #RULE_PROBLEM}, {@link #NOT_APPLICABLE} or {@link #NO_RULE}.
   * @param ruleFile the rule file that decided; empty without a decision.
   * @param detail   the pool, or the problem.
   * @param usedBy   the containers that use it.
   * @param examples up to five templates or worldgen files that use it.
   */
  public record ContainerTable(String table, String origin, String type, String status, String ruleFile, String detail, List<String> usedBy, List<String> examples) {
  }

  public record Coverage(String origin, int tables, int extended, int excluded, int ruleProblem, int notApplicable, int noRule) {
  }

  /**
   * @param kind   {@link #MALFORMED_FILE}, {@link #INACTIVE_RULE_FILE}, {@link #UNMATCHED_PATTERN},
   *               {@link #DROPPED_SOURCE}, {@link #UNUSABLE_POOL} or {@link #INELIGIBLE_MATCH}.
   * @param id     the pool or rule file.
   * @param origin the origin of that file; empty if the file was not found.
   */
  public record IntegratedLootIssue(String kind, String severity, String id, String detail, String origin) {
  }

  /**
   * Something the verification itself could not do; the rest of the run is unaffected.
   *
   * @param stage what was being processed, e.g. a finding class or `structure_template`.
   * @param id    the file or loot table, or empty if the whole stage failed.
   */
  public record InternalError(String stage, String id, String error) {
  }

  public record ReportFiles(Path markdown, Path json, Path latestMarkdown, Path latestJson) {
  }

  public record SummaryRow(String category, String severity, int count) {
  }

  /**
   * @return one row per finding class and severity, always all of them and always in the same order.
   */
  public List<SummaryRow> summary() {
    List<SummaryRow> rows = new ArrayList<>();
    rows.add(new SummaryRow(LOAD_FAILURE, ERROR, loadFailures.size()));
    rows.add(new SummaryRow(SKIPPED_BY_CONDITION, INFO, skippedByCondition.size()));
    rows.add(new SummaryRow(LEGACY_FOLDER, WARNING, legacyFolders.stream().mapToInt(LegacyFolder::count).sum()));
    rows.add(new SummaryRow(MISSING_REFERENCE, ERROR, missingReferences.size()));
    rows.add(new SummaryRow(BANNED_ITEM, INFO, bannedItems.size()));
    rows.add(new SummaryRow(CANNOT_PRODUCE, WARNING, count(cannotProduce.stream().map(CannotProduce::severity).toList(), WARNING)));
    rows.add(new SummaryRow(CANNOT_PRODUCE, INFO, count(cannotProduce.stream().map(CannotProduce::severity).toList(), INFO)));
    rows.add(new SummaryRow(ROLL_ERROR, WARNING, rollErrors.size()));
    rows.add(new SummaryRow(ALWAYS_EMPTY_IN_ROLLS, WARNING, count(alwaysEmptyInRolls.stream().map(AlwaysEmpty::severity).toList(), WARNING)));
    rows.add(new SummaryRow(ALWAYS_EMPTY_IN_ROLLS, INFO, count(alwaysEmptyInRolls.stream().map(AlwaysEmpty::severity).toList(), INFO)));
    rows.add(new SummaryRow(NO_INTEGRATED_LOOT, INFO, count(containerTables.stream().map(ContainerTable::status).toList(), NO_RULE)));
    List<String> issueSeverities = integratedLootIssues.stream().map(IntegratedLootIssue::severity).toList();
    rows.add(new SummaryRow(INTEGRATED_LOOT_DATA, ERROR, count(issueSeverities, ERROR)));
    rows.add(new SummaryRow(INTEGRATED_LOOT_DATA, WARNING, count(issueSeverities, WARNING)));
    rows.add(new SummaryRow(INTEGRATED_LOOT_DATA, INFO, count(issueSeverities, INFO)));
    rows.add(new SummaryRow(INTERNAL_ERROR, ERROR, internalErrors.size()));
    return rows;
  }

  private static int count(List<String> values, String wanted) {
    return (int) values.stream().filter(wanted::equals).count();
  }

  /**
   * @return how many findings of the severity there are in total.
   */
  public int total(String severity) {
    return summary().stream().filter(row -> row.severity().equals(severity)).mapToInt(SummaryRow::count).sum();
  }

  /**
   * @return how many loot tables were rolled as often as asked for: all but those with a roll that threw or took
   * very long.
   */
  public int tablesSampled() {
    return rolls.tablesRolled() - rollErrors.size() - rolls.slowTables().size();
  }

  /**
   * Only loot tables with entries that can produce are rolled. If nearly all of them stay empty all the same, the
   * tables are not at fault: something takes the loot out of the rolls.
   *
   * @return true if at least {@link #EMPTIED_MIN_TABLES} loot tables were sampled and at least
   * {@link #EMPTIED_PERCENT} percent of them were empty in every roll.
   */
  public boolean lootRollsEmptied() {
    int sampled = tablesSampled();
    return sampled >= EMPTIED_MIN_TABLES && alwaysEmptyInRolls.size() * 100L >= (long) sampled * EMPTIED_PERCENT;
  }

  /**
   * @return the readable name of an origin key.
   */
  public String originLabel(String key) {
    OriginInfo info = origins.get(key);
    return info == null ? key : info.origin().label();
  }

  VerifyResult withFiles(@Nullable ReportFiles written, List<String> newNotes) {
    return new VerifyResult(startedAt, zone, modVersion, gameVersion, durationMillis, List.copyOf(newNotes), scanned, itemBans, integratedLoot, rolls, origins, loadFailures, skippedByCondition, legacyFolders, missingReferences, bannedItems, cannotProduce, rollErrors, alwaysEmptyInRolls, containerTables, coverage, integratedLootIssues, internalErrors, written);
  }
}
