package noobanidus.mods.lootr.common.verify;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSet;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import noobanidus.mods.lootr.common.api.PlatformAPI;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.FileKind;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootDefinitions.FileProblem;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.Decision;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.DroppedSource;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.IneligibleMatch;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.RuleFileInfo;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot.UnmatchedPattern;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import noobanidus.mods.lootr.common.verify.TableAnalyzer.Facts;
import noobanidus.mods.lootr.common.verify.TableAnalyzer.ItemLookup;
import noobanidus.mods.lootr.common.verify.VerifyResult.BannedItems;
import noobanidus.mods.lootr.common.verify.VerifyResult.BannedTag;
import noobanidus.mods.lootr.common.verify.VerifyResult.CannotProduce;
import noobanidus.mods.lootr.common.verify.VerifyResult.ContainerTable;
import noobanidus.mods.lootr.common.verify.VerifyResult.Coverage;
import noobanidus.mods.lootr.common.verify.VerifyResult.IntegratedLootIssue;
import noobanidus.mods.lootr.common.verify.VerifyResult.LegacyFile;
import noobanidus.mods.lootr.common.verify.VerifyResult.LegacyFolder;
import noobanidus.mods.lootr.common.verify.VerifyResult.LoadFailure;
import noobanidus.mods.lootr.common.verify.VerifyResult.MissingReference;
import noobanidus.mods.lootr.common.verify.VerifyResult.SkippedByCondition;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The part of a verification run that needs neither the level nor a roll: it reads resources and the loaded loot
 * tables and judges them. Runs on the run's own thread and its {@link Workers}, never on the server thread.
 * <br />
 * Every check is isolated: one that throws becomes an internal error and the others still run. The checks build
 * on each other only through data that is simply empty if an earlier check failed.
 */
final class VerifyScan {
  private static final Gson GSON = new Gson();
  private static final String TABLE_DIRECTORY = Registries.elementsDirPath(Registries.LOOT_TABLE);
  // The folder loot tables were read from before 1.21; the game ignores it now.
  private static final String LEGACY_TABLE_DIRECTORY = "loot_tables";
  private static final String TEMPLATE_DIRECTORY = "structure";
  private static final String WORLDGEN_DIRECTORY = "worldgen";
  private static final byte[] LOOT_TABLE_KEY = "\"loot_table\"".getBytes(StandardCharsets.US_ASCII);
  private static final String EMPTY_TABLE = BuiltInLootTables.EMPTY.location().toString();
  // Block entities and blocks that hold one stack or are brushed; matched without the namespace so that
  // replacements of other mods count as well.
  private static final Set<String> ONE_SLOT_HOLDERS = Set.of("decorated_pot", "brushable_block", "suspicious_sand", "suspicious_gravel");
  private static final List<String> ISSUE_ORDER = List.of(VerifyResult.MALFORMED_FILE, VerifyResult.UNUSABLE_POOL, VerifyResult.UNMATCHED_PATTERN, VerifyResult.INELIGIBLE_MATCH, VerifyResult.DROPPED_SOURCE, VerifyResult.INACTIVE_RULE_FILE);

  /**
   * What the scan found. Filled by the run's own thread only.
   */
  static final class Data {
    int tableFiles;
    int templates;
    int worldgenFiles;
    int legacyTableFiles;
    int encodeFallbacks;
    int otherTypeContainerTables;
    int rollSkippedOtherType;
    int rollSkippedNoProducing;
    final List<LoadFailure> loadFailures = new ArrayList<>();
    final List<SkippedByCondition> skippedByCondition = new ArrayList<>();
    final List<LegacyFolder> legacyFolders = new ArrayList<>();
    final List<MissingReference> missingReferences = new ArrayList<>();
    final List<BannedItems> bannedItems = new ArrayList<>();
    final List<CannotProduce> cannotProduce = new ArrayList<>();
    final List<ContainerTable> containerTables = new ArrayList<>();
    final List<Coverage> coverage = new ArrayList<>();
    final List<IntegratedLootIssue> integratedLootIssues = new ArrayList<>();
    final List<RollSampler.Candidate> rollCandidates = new ArrayList<>();
  }

  /**
   * The structure containers that use one loot table.
   */
  private static final class ContainerUse {
    private final TreeSet<String> holders = new TreeSet<>();
    private final TreeSet<String> examples = new TreeSet<>();

    private void add(ReferenceIndex.Uses uses) {
      holders.addAll(uses.holders());
      examples.addAll(uses.examples());
      while (examples.size() > ReferenceIndex.MAX_EXAMPLES) {
        examples.pollLast();
      }
    }
  }

  private final VerifyContext context;
  private final Workers workers;
  private final Origins origins;
  private final InternalErrors errors;
  private final ItemLookup items;
  private final Data data = new Data();

  private final ReferenceIndex index = new ReferenceIndex();
  private final Map<ResourceLocation, Facts> facts = new ConcurrentHashMap<>();
  private Map<ResourceLocation, Resource> tableFiles = Map.of();
  private final Map<String, ContainerUse> containerUses = new TreeMap<>();
  private final Set<String> notProducing = new HashSet<>();

  VerifyScan(VerifyContext context, Workers workers, Origins origins, InternalErrors errors) {
    this.context = context;
    this.workers = workers;
    this.origins = origins;
    this.errors = errors;
    this.items = new ItemLookup(context.bansActive() ? ItemBans::isBanned : null);
  }

  /**
   * @throws CancellationException if the run was cancelled.
   */
  Data run() {
    check("loot_table_files", this::listTableFiles);
    check("loaded_tables", this::analyseLoadedTables);
    check(VerifyResult.LOAD_FAILURE, this::loadFailures);
    check(VerifyResult.LEGACY_FOLDER, this::legacyFolders);
    check("structure_templates", this::scanTemplates);
    check("worldgen_files", this::scanWorldgen);
    check(VerifyResult.MISSING_REFERENCE, this::missingReferences);
    check(VerifyResult.BANNED_ITEM, this::bannedItems);
    check(VerifyResult.CANNOT_PRODUCE, this::cannotProduce);
    check(VerifyResult.NO_INTEGRATED_LOOT, this::containerTables);
    check(VerifyResult.INTEGRATED_LOOT_DATA, this::integratedLootData);
    check("roll_candidates", this::rollCandidates);
    return data;
  }

  private void check(String stage, Runnable check) {
    workers.checkCancelled();
    try {
      check.run();
    } catch (CancellationException e) {
      throw e;
    } catch (Exception | LinkageError | StackOverflowError e) {
      errors.add(stage, "", e);
    }
  }

  // ----- the loaded tables and their files -----

  private void listTableFiles() {
    workers.stage("Listing loot table files");
    FileToIdConverter converter = FileToIdConverter.json(TABLE_DIRECTORY);
    Map<ResourceLocation, Resource> files = new TreeMap<>();
    converter.listMatchingResources(context.resources()).forEach((file, resource) -> files.put(converter.fileToId(file), resource));
    tableFiles = files;
    data.tableFiles = files.size();
  }

  private String originOfTable(ResourceLocation id) {
    Resource file = tableFiles.get(id);
    return file != null ? origins.of(file) : origins.ofNamespace(id.getNamespace());
  }

  // Reads every loaded table in its encoded form, so that entries which mods added in code are seen as well.
  private void analyseLoadedTables() {
    RegistryOps<JsonElement> ops = context.registries().createSerializationContext(JsonOps.INSTANCE);
    AtomicInteger fallbacks = new AtomicInteger();
    workers.forEach("Reading the loaded loot tables", context.tables().entrySet(), entry -> {
      ResourceLocation id = entry.getKey();
      try {
        JsonElement json = encode(entry.getValue(), ops);
        if (json == null) {
          Resource file = tableFiles.get(id);
          if (file == null) {
            throw new IllegalStateException("The loot table cannot be encoded and has no file");
          }
          json = readJson(file);
          fallbacks.incrementAndGet();
        }
        Facts tableFacts = TableAnalyzer.analyse(json, items);
        String origin = originOfTable(id);
        for (TableAnalyzer.Reference reference : tableFacts.references) {
          index.add(ReferenceIndex.SOURCE_LOOT_TABLE, reference.kind(), reference.table(), origin, id.toString(), "");
        }
        facts.put(id, tableFacts);
      } catch (Exception | LinkageError | StackOverflowError e) {
        errors.add("loaded_tables", id.toString(), e);
      }
    });
    data.encodeFallbacks = fallbacks.get();
  }

  @Nullable
  private static JsonElement encode(LootTable table, RegistryOps<JsonElement> ops) {
    try {
      return LootTable.DIRECT_CODEC.encodeStart(ops, table).result().orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  // Reads the way the game does, so that a file the game rejects is rejected here with the same message.
  private static JsonElement readJson(Resource resource) throws IOException {
    try (Reader reader = resource.openAsReader()) {
      JsonElement json = GsonHelper.fromJson(GSON, reader, JsonElement.class);
      if (json == null) {
        throw new JsonParseException("The file is empty");
      }
      return json;
    }
  }

  // ----- A: files that did not become a loot table -----

  private void loadFailures() {
    workers.stage("Checking loot table files that were not loaded");
    LoadConditions conditions = new LoadConditions(context.registries(), PlatformAPI::isModLoaded, context.features());
    RegistryOps<JsonElement> ops = new TagTolerantLookup(context.registries()).createSerializationContext(JsonOps.INSTANCE);
    for (Map.Entry<ResourceLocation, Resource> entry : tableFiles.entrySet()) {
      ResourceLocation id = entry.getKey();
      if (context.tables().containsKey(id)) {
        continue;
      }
      workers.checkCancelled();
      try {
        classify(id, entry.getValue(), conditions, ops);
      } catch (Exception | LinkageError | StackOverflowError e) {
        errors.add(VerifyResult.LOAD_FAILURE, id.toString(), e);
      }
    }
  }

  private void classify(ResourceLocation id, Resource resource, LoadConditions conditions, RegistryOps<JsonElement> ops) throws IOException {
    String table = id.toString();
    String origin = origins.of(resource);
    String file = "data/" + id.getNamespace() + "/" + TABLE_DIRECTORY + "/" + id.getPath() + ".json";
    JsonElement json;
    try {
      json = readJson(resource);
    } catch (JsonParseException | IllegalArgumentException e) {
      data.loadFailures.add(new LoadFailure(table, origin, file, VerifyResult.INVALID_JSON, InternalErrors.oneLine(String.valueOf(e.getMessage())), List.of(), ""));
      return;
    }

    LoadConditions.Found found = json.isJsonObject() ? conditions.find(json.getAsJsonObject()) : null;
    if (found != null && found.outcome() == LoadConditions.Outcome.FALSE) {
      data.skippedByCondition.add(new SkippedByCondition(table, origin, found.key(), "false"));
      return;
    }

    String error;
    try {
      DataResult<LootTable> parsed = LootTable.DIRECT_CODEC.parse(ops, json);
      error = parsed.error().map(DataResult.Error::message).orElse("");
    } catch (RuntimeException e) {
      error = InternalErrors.describe(e);
    }
    if (error.isEmpty()) {
      if (found != null && found.outcome() == LoadConditions.Outcome.UNKNOWN) {
        data.skippedByCondition.add(new SkippedByCondition(table, origin, found.key(), "unknown"));
      } else {
        data.loadFailures.add(new LoadFailure(table, origin, file, VerifyResult.OTHER, "", List.of(), "The file parses without an error now: a mod rejected it while loading, or the reason no longer applies."));
      }
      return;
    }
    List<String> unknownItems = List.copyOf(TableAnalyzer.analyse(json, items).unknownItems);
    String note = "";
    if (found != null) {
      note = "The file carries " + found.key() + (found.outcome() == LoadConditions.Outcome.TRUE ? ", which hold." : ", which could not be evaluated here.");
    }
    data.loadFailures.add(new LoadFailure(table, origin, file, unknownItems.isEmpty() ? VerifyResult.OTHER : VerifyResult.UNKNOWN_ITEM, InternalErrors.oneLine(error), unknownItems, note));
  }

  /**
   * The registries as the game presents them while it parses loot tables: every tag exists, because tags are
   * loaded later. Without it a table that names a tag unknown today would fail here for a reason the game never had.
   */
  private record TagTolerantLookup(RegistryAccess registries) implements HolderLookup.Provider {
    @Override
    public Stream<ResourceKey<? extends Registry<?>>> listRegistries() {
      return registries.listRegistries();
    }

    @Override
    public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
      return registries.registry(key).map(Registry::asTagAddingLookup);
    }
  }

  // ----- B: the folder the game no longer reads -----

  private void legacyFolders() {
    workers.stage("Looking for loot tables in the old folder");
    FileToIdConverter converter = FileToIdConverter.json(LEGACY_TABLE_DIRECTORY);
    Map<String, Map<String, LegacyFile>> byOrigin = new TreeMap<>();
    int files = 0;
    for (Map.Entry<ResourceLocation, List<Resource>> entry : converter.listMatchingResourceStacks(context.resources()).entrySet()) {
      ResourceLocation id = converter.fileToId(entry.getKey());
      boolean inCurrentFolder = tableFiles.containsKey(id) || context.tables().containsKey(id);
      for (Resource resource : entry.getValue()) {
        byOrigin.computeIfAbsent(origins.of(resource), ignored -> new TreeMap<>()).put(id.toString(), new LegacyFile(id.toString(), inCurrentFolder));
        files++;
      }
    }
    data.legacyTableFiles = files;
    byOrigin.forEach((origin, found) -> data.legacyFolders.add(new LegacyFolder(origin, found.size(), (int) found.values().stream().filter(LegacyFile::inCurrentFolder).count(), List.copyOf(found.values()))));
  }

  // ----- C: references -----

  private void scanTemplates() {
    workers.stage("Listing structure templates");
    Map<ResourceLocation, Resource> templates = context.resources().listResources(TEMPLATE_DIRECTORY, file -> file.getPath().endsWith(".nbt"));
    data.templates = templates.size();
    workers.forEach("Reading structure templates", templates.entrySet(), entry -> {
      String id = fileId(entry.getKey(), TEMPLATE_DIRECTORY, ".nbt");
      try {
        Resource resource = entry.getValue();
        CompoundTag root;
        try (InputStream stream = resource.open()) {
          root = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        }
        String origin = origins.of(resource);
        ReferenceScanner.scanTemplate(root, (kind, holder, table) -> index.add(ReferenceIndex.SOURCE_STRUCTURE, kind, TableAnalyzer.normaliseOrRaw(table), origin, id, holder));
      } catch (Exception | LinkageError | StackOverflowError e) {
        errors.add("structure_templates", id, e);
      }
    });
  }

  private void scanWorldgen() {
    workers.stage("Listing worldgen files");
    Map<ResourceLocation, Resource> files = context.resources().listResources(WORLDGEN_DIRECTORY, file -> file.getPath().endsWith(".json"));
    data.worldgenFiles = files.size();
    workers.forEach("Reading worldgen files", files.entrySet(), entry -> {
      String id = fileId(entry.getKey(), WORLDGEN_DIRECTORY, ".json");
      try {
        Resource resource = entry.getValue();
        byte[] bytes;
        try (InputStream stream = resource.open()) {
          bytes = stream.readAllBytes();
        }
        // Nearly all worldgen files name no loot table; those are not parsed at all.
        if (!contains(bytes, LOOT_TABLE_KEY)) {
          return;
        }
        JsonElement json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        // The kind is the registry folder, e.g. `processor_list`.
        String path = id.substring(id.indexOf(':') + 1);
        int slash = path.indexOf('/');
        String origin = origins.of(resource);
        ReferenceScanner.scanWorldgen(json, slash == -1 ? path : path.substring(0, slash), (kind, holder, table) -> index.add(ReferenceIndex.SOURCE_WORLDGEN, kind, TableAnalyzer.normaliseOrRaw(table), origin, id, holder));
      } catch (Exception | LinkageError | StackOverflowError e) {
        errors.add("worldgen_files", id, e);
      }
    });
  }

  // `namespace:directory/a/b.ext` becomes `namespace:a/b`.
  private static String fileId(ResourceLocation file, String directory, String extension) {
    String path = file.getPath();
    return file.getNamespace() + ":" + path.substring(directory.length() + 1, path.length() - extension.length());
  }

  private static boolean contains(byte[] bytes, byte[] needle) {
    outer:
    for (int i = 0, last = bytes.length - needle.length; i <= last; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (bytes[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  private boolean isLoaded(@Nullable ResourceLocation id) {
    return id != null && (context.tables().containsKey(id) || id.toString().equals(EMPTY_TABLE));
  }

  private void missingReferences() {
    workers.stage("Checking loot table references");
    for (Map.Entry<ReferenceIndex.Key, ReferenceIndex.Uses> entry : index.sorted().entrySet()) {
      ReferenceIndex.Key key = entry.getKey();
      ReferenceIndex.Uses uses = entry.getValue();
      if (ReferenceIndex.isContainerUse(key)) {
        containerUses.computeIfAbsent(key.table(), ignored -> new ContainerUse()).add(uses);
      }
      ResourceLocation id = ResourceLocation.tryParse(key.table());
      if (!isLoaded(id)) {
        data.missingReferences.add(new MissingReference(key.table(), key.source(), key.kind(), key.origin(), uses.count(), List.copyOf(uses.examples()), List.copyOf(uses.holders()), id != null && tableFiles.containsKey(id)));
      }
    }
  }

  // ----- D: banned items -----

  private void bannedItems() {
    if (!context.bansActive()) {
      return;
    }
    workers.stage("Listing banned items");
    Map<ResourceLocation, Facts> sorted = new TreeMap<>(facts);
    // A table also reaches a banned item through the loaded tables it refers to by nested table entries. Tables
    // that name one themselves are the start; the tables that refer to a table that reaches one are added from
    // there, backwards along the references, each at most once. This is one pass over all references however
    // deep the chains are, and it ends on cycles because a table is queued only the first time it is reached.
    Map<ResourceLocation, Set<String>> nested = new HashMap<>();
    Map<ResourceLocation, List<ResourceLocation>> referrers = new HashMap<>();
    Set<ResourceLocation> reaching = new HashSet<>();
    Deque<ResourceLocation> queue = new ArrayDeque<>();
    for (Map.Entry<ResourceLocation, Facts> entry : sorted.entrySet()) {
      ResourceLocation id = entry.getKey();
      Facts tableFacts = entry.getValue();
      if (namesBannedItem(tableFacts)) {
        reaching.add(id);
        queue.add(id);
      }
      for (TableAnalyzer.Reference reference : tableFacts.references) {
        if (!reference.kind().equals(ReferenceIndex.NESTED_TABLE)) {
          continue;
        }
        // References to tables that were not loaded are missing references, and a table is no use to itself.
        ResourceLocation target = ResourceLocation.tryParse(reference.table());
        if (target == null || target.equals(id) || !facts.containsKey(target)) {
          continue;
        }
        nested.computeIfAbsent(id, ignored -> new TreeSet<>()).add(target.toString());
        referrers.computeIfAbsent(target, ignored -> new ArrayList<>()).add(id);
      }
    }
    for (ResourceLocation current = queue.poll(); current != null; current = queue.poll()) {
      for (ResourceLocation referrer : referrers.getOrDefault(current, List.of())) {
        if (reaching.add(referrer)) {
          queue.add(referrer);
        }
      }
    }
    for (Map.Entry<ResourceLocation, Facts> entry : sorted.entrySet()) {
      Facts tableFacts = entry.getValue();
      List<String> via = new ArrayList<>();
      for (String target : nested.getOrDefault(entry.getKey(), Set.of())) {
        if (reaching.contains(ResourceLocation.parse(target))) {
          via.add(target);
        }
      }
      if (!namesBannedItem(tableFacts) && via.isEmpty()) {
        continue;
      }
      List<BannedTag> tags = new ArrayList<>();
      tableFacts.bannedTags.forEach((tag, tagFacts) -> tags.add(new BannedTag(tag, tagFacts.banned(), tagFacts.size())));
      data.bannedItems.add(new BannedItems(entry.getKey().toString(), originOfTable(entry.getKey()), List.copyOf(tableFacts.bannedItems), tags, List.copyOf(via)));
    }
  }

  private static boolean namesBannedItem(Facts tableFacts) {
    return !tableFacts.bannedItems.isEmpty() || !tableFacts.bannedTags.isEmpty();
  }

  // ----- E: tables that cannot produce anything (the static part) -----

  private void cannotProduce() {
    workers.stage("Judging what the loot tables can produce");
    // A table produces if one of its entries does, or one of the tables it refers to. Tables that could not be
    // read count as producing: nothing is known against them.
    Set<String> producing = new HashSet<>();
    Map<String, Facts> pending = new TreeMap<>();
    for (ResourceLocation id : context.tables().keySet()) {
      Facts tableFacts = facts.get(id);
      if (tableFacts == null || tableFacts.producesDirectly) {
        producing.add(id.toString());
      } else {
        pending.put(id.toString(), tableFacts);
      }
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (Iterator<Map.Entry<String, Facts>> iterator = pending.entrySet().iterator(); iterator.hasNext(); ) {
        Map.Entry<String, Facts> entry = iterator.next();
        if (entry.getValue().nested.stream().anyMatch(producing::contains)) {
          producing.add(entry.getKey());
          iterator.remove();
          changed = true;
        }
      }
    }
    for (Map.Entry<String, Facts> entry : pending.entrySet()) {
      notProducing.add(entry.getKey());
      // The built-in empty table is empty on purpose.
      if (entry.getKey().equals(EMPTY_TABLE)) {
        continue;
      }
      ResourceLocation id = ResourceLocation.parse(entry.getKey());
      ContainerUse use = containerUses.get(entry.getKey());
      data.cannotProduce.add(new CannotProduce(entry.getKey(), originOfTable(id), typeName(context.tables().get(id).getParamSet()), entry.getValue().reason(), use == null ? VerifyResult.INFO : VerifyResult.WARNING, use == null ? List.of() : List.copyOf(use.holders)));
    }
  }

  // ----- F: integrated loot coverage of the structure containers -----

  private void containerTables() {
    workers.stage("Checking the integrated loot coverage");
    IntegratedLootSnapshot snapshot = context.snapshot();
    // tables, extended, excluded, rule problem, not applicable, no rule
    Map<String, int[]> counts = new TreeMap<>();
    for (Map.Entry<String, ContainerUse> entry : containerUses.entrySet()) {
      ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
      LootTable table = id == null ? null : context.tables().get(id);
      if (table == null) {
        continue;
      }
      if (!IntegratedLootSnapshot.isEligible(table.getParamSet())) {
        data.otherTypeContainerTables++;
        continue;
      }
      ContainerUse use = entry.getValue();
      Optional<Decision> decision = snapshot.decisionFor(ResourceKey.create(Registries.LOOT_TABLE, id));
      String status;
      String ruleFile = "";
      String detail = "";
      int column;
      if (decision.isPresent()) {
        Decision decided = decision.get();
        ruleFile = decided.ruleFile().toString();
        if (decided.exclusion()) {
          status = VerifyResult.EXCLUDED;
          detail = "entry " + decided.entryIndex() + ", pattern " + decided.pattern();
          column = 2;
        } else if (decided.active()) {
          status = VerifyResult.EXTENDED;
          detail = "pool " + decided.poolId().map(ResourceLocation::toString).orElse("?") + ", rolls " + decided.rolls();
          column = 1;
        } else {
          status = VerifyResult.RULE_PROBLEM;
          detail = decided.problem().orElse("its pool is not usable");
          column = 3;
        }
      } else if (!use.holders.isEmpty() && use.holders.stream().allMatch(VerifyScan::isOneSlotHolder)) {
        status = VerifyResult.NOT_APPLICABLE;
        detail = "only one-slot or brushable containers use it";
        column = 4;
      } else {
        status = VerifyResult.NO_RULE;
        column = 5;
      }
      String origin = originOfTable(id);
      int[] row = counts.computeIfAbsent(origin, ignored -> new int[6]);
      row[0]++;
      row[column]++;
      data.containerTables.add(new ContainerTable(entry.getKey(), origin, typeName(table.getParamSet()), status, ruleFile, detail, List.copyOf(use.holders), List.copyOf(use.examples)));
    }
    counts.forEach((origin, row) -> data.coverage.add(new Coverage(origin, row[0], row[1], row[2], row[3], row[4], row[5])));
  }

  private static boolean isOneSlotHolder(String holder) {
    return ONE_SLOT_HOLDERS.contains(holder.substring(holder.indexOf(':') + 1));
  }

  // ----- G: what integrated loot says about its own data -----

  private void integratedLootData() {
    workers.stage("Collecting the integrated loot diagnostics");
    IntegratedLootSnapshot snapshot = context.snapshot();
    List<IntegratedLootIssue> issues = data.integratedLootIssues;
    for (FileProblem problem : snapshot.malformedFiles()) {
      issues.add(issue(VerifyResult.MALFORMED_FILE, VerifyResult.ERROR, problem.kind(), problem.id(), problem.message()));
    }
    for (ResourceLocation pool : snapshot.unusablePools()) {
      issues.add(issue(VerifyResult.UNUSABLE_POOL, VerifyResult.WARNING, FileKind.POOL, pool, "none of its source tables is loaded"));
    }
    for (UnmatchedPattern pattern : snapshot.unmatchedPatterns()) {
      issues.add(issue(VerifyResult.UNMATCHED_PATTERN, VerifyResult.WARNING, FileKind.RULE, pattern.ruleFile(), "entry " + pattern.entryIndex() + ": pattern " + pattern.pattern() + " matches no loaded loot table"));
    }
    for (IneligibleMatch match : snapshot.ineligibleMatches()) {
      issues.add(issue(VerifyResult.INELIGIBLE_MATCH, VerifyResult.WARNING, FileKind.RULE, match.ruleFile(), "entry " + match.entryIndex() + " (pattern " + match.pattern() + ") matches " + match.table().location() + " of type " + match.type() + ", which is never extended"));
    }
    for (DroppedSource source : snapshot.droppedSources()) {
      issues.add(issue(VerifyResult.DROPPED_SOURCE, VerifyResult.INFO, FileKind.POOL, source.pool(), "source table " + source.table() + " is not loaded"));
    }
    for (RuleFileInfo file : snapshot.inactiveRuleFiles()) {
      issues.add(issue(VerifyResult.INACTIVE_RULE_FILE, VerifyResult.INFO, FileKind.RULE, file.id(), describe(file)));
    }
    issues.sort(Comparator.<IntegratedLootIssue>comparingInt(issue -> ISSUE_ORDER.indexOf(issue.kind())).thenComparing(IntegratedLootIssue::id).thenComparing(IntegratedLootIssue::detail));
  }

  private IntegratedLootIssue issue(String kind, String severity, FileKind fileKind, ResourceLocation id, String detail) {
    String directory = fileKind == FileKind.POOL ? IntegratedLootDefinitions.POOL_DIRECTORY : IntegratedLootDefinitions.RULE_DIRECTORY;
    String origin = context.resources().getResource(id.withPath(directory + "/" + id.getPath() + ".json")).map(origins::of).orElse("");
    return new IntegratedLootIssue(kind, severity, fileKind.label() + " " + id, InternalErrors.oneLine(detail), origin);
  }

  // Why a rule file is inactive is whatever the snapshot records about it. The record is printed as it is, so
  // that a reason added to it later shows up here without this class knowing about it.
  private static String describe(RuleFileInfo file) {
    StringJoiner joiner = new StringJoiner(", ");
    try {
      for (RecordComponent component : RuleFileInfo.class.getRecordComponents()) {
        Object value = component.getAccessor().invoke(file);
        if (component.getName().equals("id") || value instanceof Collection<?> collection && collection.isEmpty()) {
          continue;
        }
        joiner.add(component.getName() + " " + value);
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      return file.toString();
    }
    return joiner.toString();
  }

  // ----- E: which tables the sample rolls are for -----

  private void rollCandidates() {
    if (context.options().rolls() == 0) {
      return;
    }
    for (Map.Entry<ResourceLocation, LootTable> entry : context.tables().entrySet()) {
      ResourceLocation id = entry.getKey();
      ContainerUse use = containerUses.get(id.toString());
      if (use == null && !isChestTable(id.getPath())) {
        continue;
      }
      LootContextParamSet type = entry.getValue().getParamSet();
      // A roll here knows a position and nothing else, which is all that chest, vault and archaeology tables
      // require. A table without a type formally requires every parameter there is; it is rolled like a chest's,
      // because that is how a container rolls it.
      if (type != LootTable.DEFAULT_PARAM_SET && !Set.of(LootContextParams.ORIGIN).containsAll(type.getRequired())) {
        data.rollSkippedOtherType++;
        continue;
      }
      if (notProducing.contains(id.toString())) {
        data.rollSkippedNoProducing++;
        continue;
      }
      data.rollCandidates.add(new RollSampler.Candidate(id, entry.getValue(), typeName(type), originOfTable(id), use == null ? List.of() : List.copyOf(use.holders)));
    }
  }

  private static boolean isChestTable(String path) {
    return path.startsWith("chests/") || path.contains("/chests/");
  }

  static String typeName(LootContextParamSet type) {
    return LootContextParamSets.CODEC.encodeStart(JsonOps.INSTANCE, type).result().map(json -> json.isJsonPrimitive() ? json.getAsString() : json.toString()).orElse("unknown");
  }
}
