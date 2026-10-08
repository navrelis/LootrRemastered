package noobanidus.mods.lootr.common.integratedloot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.GsonHelper;
import noobanidus.mods.lootr.common.api.LootrAPI;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The pool and rule files of all data packs as they were written, before they are resolved against the loaded
 * loot tables (see {@link IntegratedLootSnapshot}). Immutable.
 * <br />
 * Pools: `data/&lt;namespace&gt;/lootr/integrated_loot/pool/&lt;path&gt;.json`, rules:
 * `data/&lt;namespace&gt;/lootr/integrated_loot/rule/&lt;path&gt;.json`. A file that cannot be parsed is left out and
 * listed in {@link #problems()}; it never affects another file.
 */
public final class IntegratedLootDefinitions {
  public static final String POOL_DIRECTORY = "lootr/integrated_loot/pool";
  public static final String RULE_DIRECTORY = "lootr/integrated_loot/rule";
  public static final IntegratedLootDefinitions EMPTY = new IntegratedLootDefinitions(Map.of(), Map.of(), List.of());

  private static final Gson GSON = new Gson();

  private final Map<ResourceLocation, PoolDefinition> pools;
  private final Map<ResourceLocation, RuleFile> ruleFiles;
  private final List<FileProblem> problems;

  private IntegratedLootDefinitions(Map<ResourceLocation, PoolDefinition> pools, Map<ResourceLocation, RuleFile> ruleFiles, List<FileProblem> problems) {
    this.pools = Collections.unmodifiableMap(pools);
    this.ruleFiles = Collections.unmodifiableMap(ruleFiles);
    this.problems = List.copyOf(problems);
  }

  /**
   * @return the pool files by pool id, ordered by id.
   */
  public Map<ResourceLocation, PoolDefinition> pools() {
    return pools;
  }

  /**
   * @return the rule files by rule file id, ordered by id.
   */
  public Map<ResourceLocation, RuleFile> ruleFiles() {
    return ruleFiles;
  }

  /**
   * @return the files that were skipped because they are malformed.
   */
  public List<FileProblem> problems() {
    return problems;
  }

  public boolean isEmpty() {
    return pools.isEmpty() && ruleFiles.isEmpty() && problems.isEmpty();
  }

  /**
   * Reads and parses all pool and rule files. Does file IO, so it belongs into the preparation stage of a
   * resource reload. Logs one warning per skipped file.
   */
  public static IntegratedLootDefinitions load(ResourceManager manager) {
    List<FileProblem> problems = new ArrayList<>();
    Map<ResourceLocation, JsonElement> pools = scan(manager, POOL_DIRECTORY, FileKind.POOL, problems);
    Map<ResourceLocation, JsonElement> rules = scan(manager, RULE_DIRECTORY, FileKind.RULE, problems);
    IntegratedLootDefinitions definitions = parse(pools, rules, problems);
    for (FileProblem problem : definitions.problems()) {
      String directory = problem.kind() == FileKind.POOL ? POOL_DIRECTORY : RULE_DIRECTORY;
      LootrAPI.LOG.warn("Integrated loot: skipping malformed {} file '{}' (data/{}/{}/{}.json): {}", problem.kind().label(), problem.id(), problem.id().getNamespace(), directory, problem.id().getPath(), problem.message());
    }
    return definitions;
  }

  /**
   * Parses already read JSON. Pure: no IO and no logging.
   */
  public static IntegratedLootDefinitions parse(Map<ResourceLocation, JsonElement> pools, Map<ResourceLocation, JsonElement> rules) {
    return parse(pools, rules, List.of());
  }

  private static IntegratedLootDefinitions parse(Map<ResourceLocation, JsonElement> pools, Map<ResourceLocation, JsonElement> rules, List<FileProblem> readProblems) {
    List<FileProblem> problems = new ArrayList<>(readProblems);
    Map<ResourceLocation, PoolDefinition> parsedPools = decode(pools, PoolDefinition.CODEC, FileKind.POOL, problems);
    Map<ResourceLocation, RuleFile> parsedRules = decode(rules, RuleFile.CODEC, FileKind.RULE, problems);
    problems.sort((a, b) -> a.kind() != b.kind() ? a.kind().compareTo(b.kind()) : a.id().compareTo(b.id()));
    return new IntegratedLootDefinitions(parsedPools, parsedRules, problems);
  }

  private static <T> Map<ResourceLocation, T> decode(Map<ResourceLocation, JsonElement> files, Codec<T> codec, FileKind kind, List<FileProblem> problems) {
    Map<ResourceLocation, T> result = new TreeMap<>();
    for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
      DataResult<T> parsed;
      try {
        parsed = codec.parse(JsonOps.INSTANCE, file.getValue());
      } catch (RuntimeException e) {
        problems.add(new FileProblem(kind, file.getKey(), String.valueOf(e)));
        continue;
      }
      Optional<DataResult.Error<T>> error = parsed.error();
      if (error.isPresent()) {
        problems.add(new FileProblem(kind, file.getKey(), error.get().message()));
      } else {
        result.put(file.getKey(), parsed.getOrThrow());
      }
    }
    return result;
  }

  private static Map<ResourceLocation, JsonElement> scan(ResourceManager manager, String directory, FileKind kind, List<FileProblem> problems) {
    FileToIdConverter converter = FileToIdConverter.json(directory);
    Map<ResourceLocation, JsonElement> result = new TreeMap<>();
    for (Map.Entry<ResourceLocation, Resource> entry : converter.listMatchingResources(manager).entrySet()) {
      ResourceLocation id = converter.fileToId(entry.getKey());
      try (Reader reader = entry.getValue().openAsReader()) {
        result.put(id, GsonHelper.fromJson(GSON, reader, JsonElement.class));
      } catch (IOException | JsonParseException | IllegalArgumentException e) {
        problems.add(new FileProblem(kind, id, "not readable as JSON (" + e.getMessage() + ")"));
      }
    }
    return result;
  }

  public enum FileKind {
    POOL("pool"),
    RULE("rule");

    private final String label;

    FileKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * A pool or rule file that was skipped.
   */
  public record FileProblem(FileKind kind, ResourceLocation id, String message) {
  }

  public record SourceDefinition(ResourceLocation table, int weight) {
    public static final Codec<SourceDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("table").forGetter(SourceDefinition::table),
        ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(SourceDefinition::weight)
    ).apply(instance, SourceDefinition::new));
  }

  public record PoolDefinition(List<SourceDefinition> sources) {
    public static final Codec<PoolDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        SourceDefinition.CODEC.listOf().fieldOf("sources").forGetter(PoolDefinition::sources)
    ).apply(instance, PoolDefinition::new));

    public PoolDefinition {
      sources = List.copyOf(sources);
    }
  }

  /**
   * How many stacks an extension asks for: a number between `min` and `max`, both inclusive.
   */
  public record Rolls(int min, int max) {
    public static final Rolls ONE = new Rolls(1, 1);

    private static final Codec<Rolls> RANGE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ExtraCodecs.NON_NEGATIVE_INT.fieldOf("min").forGetter(Rolls::min),
        ExtraCodecs.NON_NEGATIVE_INT.fieldOf("max").forGetter(Rolls::max)
    ).apply(instance, Rolls::new));

    public static final Codec<Rolls> CODEC = Codec.either(ExtraCodecs.NON_NEGATIVE_INT, RANGE_CODEC)
        .xmap(either -> either.map(count -> new Rolls(count, count), range -> range), rolls -> rolls.isFixed() ? Either.left(rolls.min()) : Either.right(rolls))
        .validate(rolls -> rolls.min() <= rolls.max() ? DataResult.success(rolls) : DataResult.error(() -> "Rolls: min " + rolls.min() + " is greater than max " + rolls.max()));

    public boolean isFixed() {
      return min == max;
    }

    @Override
    public String toString() {
      return isFixed() ? Integer.toString(min) : min + "-" + max;
    }
  }

  /**
   * One entry of a rule file: either "extend the matching tables from this pool" or "never extend them".
   */
  public record RuleEntry(List<TablePattern> tables, boolean exclude, Optional<ResourceLocation> pool, Rolls rolls) {
    public static final Codec<RuleEntry> CODEC = RecordCodecBuilder.<RuleEntry>create(instance -> instance.group(
        ExtraCodecs.nonEmptyList(TablePattern.CODEC.listOf()).fieldOf("tables").forGetter(RuleEntry::tables),
        Codec.BOOL.optionalFieldOf("exclude", false).forGetter(RuleEntry::exclude),
        ResourceLocation.CODEC.optionalFieldOf("pool").forGetter(RuleEntry::pool),
        Rolls.CODEC.optionalFieldOf("rolls", Rolls.ONE).forGetter(RuleEntry::rolls)
    ).apply(instance, RuleEntry::new)).validate(RuleEntry::validate);

    public RuleEntry {
      tables = List.copyOf(tables);
    }

    private static DataResult<RuleEntry> validate(RuleEntry entry) {
      if (entry.exclude() && entry.pool().isPresent()) {
        return DataResult.error(() -> "An entry with \"exclude\": true must not name a pool");
      }
      if (!entry.exclude() && entry.pool().isEmpty()) {
        return DataResult.error(() -> "An entry needs either a \"pool\" or \"exclude\": true");
      }
      return DataResult.success(entry);
    }
  }

  public record RuleFile(List<String> requiredMods, int priority, List<RuleEntry> entries) {
    public static final Codec<RuleFile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.listOf().optionalFieldOf("required_mods", List.of()).forGetter(RuleFile::requiredMods),
        Codec.INT.optionalFieldOf("priority", 0).forGetter(RuleFile::priority),
        RuleEntry.CODEC.listOf().fieldOf("entries").forGetter(RuleFile::entries)
    ).apply(instance, RuleFile::new));

    public RuleFile {
      requiredMods = List.copyOf(requiredMods);
      entries = List.copyOf(entries);
    }
  }
}
