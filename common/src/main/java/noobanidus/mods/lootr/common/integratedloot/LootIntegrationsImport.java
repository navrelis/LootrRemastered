package noobanidus.mods.lootr.common.integratedloot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One-time take-over of the settings of the mod Loot Integrations from its configuration file. The file is
 * only ever read, never changed or deleted.
 */
public final class LootIntegrationsImport {
  public static final String FILE_NAME = "lootintegrations.json";

  private LootIntegrationsImport() {
  }

  /**
   * @param settings  the given settings with the values of the file applied.
   * @param takenOver one human-readable `option=value` text per value that was taken over.
   */
  public record Result(IntegratedLootSettings settings, List<String> takenOver) {
    public Result {
      takenOver = List.copyOf(takenOver);
    }
  }

  /**
   * Reads the file from the configuration directory.
   *
   * @return nothing if there is no directory, no such file or the file cannot be read; nothing is logged or
   * thrown in these cases.
   */
  public static Optional<Result> read(@Nullable Path configDirectory, IntegratedLootSettings base) {
    if (configDirectory == null) {
      return Optional.empty();
    }
    try {
      Path file = configDirectory.resolve(FILE_NAME);
      if (!Files.isRegularFile(file)) {
        return Optional.empty();
      }
      return Optional.of(map(base, Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Pure mapping of the file's content onto the settings. The content is lenient JSON (`//` comments are
   * allowed) in which every option is an object nested under its own name:
   * `{ "skipMapItems": { "desc:": "...", "skipMapItems": true } }`. Missing and malformed options keep the
   * given value; content that is no JSON object at all changes nothing.
   */
  public static Result map(IntegratedLootSettings base, @Nullable String json) {
    JsonObject root = parse(json);
    if (root == null) {
      return new Result(base, List.of());
    }
    IntegratedLootSettings settings = base;
    List<String> takenOver = new ArrayList<>();

    // Their weight is the bonus on top of the normal weight of 1.
    Integer bonus = nonNegativeInt(option(root, "moddedItemWeight"));
    if (bonus != null) {
      settings = settings.withModdedItemWeight((int) Math.min((long) bonus + 1, IntegratedLootSettings.MAX_MODDED_ITEM_WEIGHT));
      takenOver.add("modded_item_weight=" + settings.moddedItemWeight());
    }
    Boolean skipExisting = bool(option(root, "skipExistingItems"));
    if (skipExisting != null) {
      settings = settings.withSkipExistingItems(skipExisting);
      takenOver.add("skip_existing_items=" + skipExisting);
    }
    Boolean skipMaps = bool(option(root, "skipMapItems"));
    if (skipMaps != null) {
      settings = settings.withSkipMapItems(skipMaps);
      takenOver.add("skip_map_items=" + skipMaps);
    }
    Boolean debug = bool(option(root, "debugOutput"));
    if (debug != null) {
      settings = settings.withDebugLog(debug);
      takenOver.add("debug_log=" + debug);
    }
    return new Result(settings, takenOver);
  }

  @Nullable
  private static JsonObject parse(@Nullable String json) {
    if (json == null) {
      return null;
    }
    try {
      JsonReader reader = new JsonReader(new StringReader(json));
      reader.setLenient(true);
      JsonElement element = JsonParser.parseReader(reader);
      return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    } catch (JsonParseException | IllegalStateException | StackOverflowError e) {
      return null;
    }
  }

  @Nullable
  private static JsonPrimitive option(JsonObject root, String name) {
    JsonElement section = root.get(name);
    if (section == null || !section.isJsonObject()) {
      return null;
    }
    JsonElement value = section.getAsJsonObject().get(name);
    return value != null && value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
  }

  @Nullable
  private static Boolean bool(@Nullable JsonPrimitive value) {
    return value != null && value.isBoolean() ? value.getAsBoolean() : null;
  }

  @Nullable
  private static Integer nonNegativeInt(@Nullable JsonPrimitive value) {
    if (value == null || !value.isNumber()) {
      return null;
    }
    try {
      BigDecimal number = value.getAsBigDecimal();
      if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0 || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
        return null;
      }
      return number.intValueExact();
    } catch (NumberFormatException | ArithmeticException e) {
      return null;
    }
  }
}
