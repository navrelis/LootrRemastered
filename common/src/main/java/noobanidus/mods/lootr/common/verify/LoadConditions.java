package noobanidus.mods.lootr.common.verify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Evaluates data pack load conditions of a resource after the fact: Fabric API's `fabric:load_conditions` with its
 * built-in condition types, and the NeoForge form `neoforge:conditions` (`forge:conditions`) with the common
 * types. A condition of a type that is not known here is {@link Outcome#UNKNOWN}, and so is everything that
 * depends on it.
 * <br />
 * Pure: reads registries and the mod list, nothing else. Safe on any thread once data is loaded.
 */
final class LoadConditions {
  static final String FABRIC_KEY = "fabric:load_conditions";
  static final List<String> NEOFORGE_KEYS = List.of("neoforge:conditions", "forge:conditions");

  enum Outcome {
    TRUE,
    FALSE,
    UNKNOWN;

    Outcome not() {
      return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
    }

    static Outcome of(boolean value) {
      return value ? TRUE : FALSE;
    }
  }

  /**
   * @param key the JSON key the conditions were found under.
   */
  record Found(String key, Outcome outcome) {
  }

  private final RegistryAccess registries;
  private final Predicate<String> modLoaded;
  private final FeatureFlagSet enabledFeatures;

  LoadConditions(RegistryAccess registries, Predicate<String> modLoaded, FeatureFlagSet enabledFeatures) {
    this.registries = registries;
    this.modLoaded = modLoaded;
    this.enabledFeatures = enabledFeatures;
  }

  /**
   * @return the conditions the file carries and what they evaluate to; null if it carries none. If both forms
   * are present, the first one that is not true is returned.
   */
  @Nullable
  Found find(JsonObject json) {
    Found result = null;
    if (json.has(FABRIC_KEY)) {
      result = new Found(FABRIC_KEY, fabricList(json.get(FABRIC_KEY)));
      if (result.outcome() != Outcome.TRUE) {
        return result;
      }
    }
    for (String key : NEOFORGE_KEYS) {
      if (json.has(key)) {
        Found found = new Found(key, neoforgeList(json.get(key)));
        if (found.outcome() != Outcome.TRUE) {
          return found;
        }
        if (result == null) {
          result = found;
        }
      }
    }
    return result;
  }

  // ----- Fabric -----

  // Either one condition or an array of conditions that must all hold.
  private Outcome fabricList(JsonElement element) {
    if (element.isJsonArray()) {
      return all(element.getAsJsonArray(), this::fabric);
    }
    return fabric(element);
  }

  private Outcome fabric(JsonElement element) {
    if (!element.isJsonObject()) {
      return Outcome.UNKNOWN;
    }
    JsonObject json = element.getAsJsonObject();
    String type = normalise(string(json, "condition"));
    if (type == null) {
      return Outcome.UNKNOWN;
    }
    return switch (type) {
      case "fabric:true" -> Outcome.TRUE;
      case "fabric:not" -> json.has("value") ? fabric(json.get("value")).not() : Outcome.UNKNOWN;
      case "fabric:and" -> json.has("values") && json.get("values").isJsonArray() ? all(json.getAsJsonArray("values"), this::fabric) : Outcome.UNKNOWN;
      case "fabric:or" -> json.has("values") && json.get("values").isJsonArray() ? any(json.getAsJsonArray("values"), this::fabric) : Outcome.UNKNOWN;
      case "fabric:all_mods_loaded" -> strings(json, "values") == null ? Outcome.UNKNOWN : Outcome.of(strings(json, "values").stream().allMatch(modLoaded));
      case "fabric:any_mods_loaded" -> strings(json, "values") == null ? Outcome.UNKNOWN : Outcome.of(strings(json, "values").stream().anyMatch(modLoaded));
      // Fabric API answers this one from the tags of the data load in progress, which loot tables are read before;
      // what it answered then cannot be told from the tags that are bound now.
      case "fabric:tags_populated" -> Outcome.UNKNOWN;
      case "fabric:registry_contains" -> registryContains(json);
      case "fabric:features_enabled" -> featuresEnabled(json);
      default -> Outcome.UNKNOWN;
    };
  }

  private Outcome registryContains(JsonObject json) {
    List<String> values = strings(json, "values");
    Optional<? extends Registry<?>> registry = registry(json);
    if (values == null || registry == null) {
      return Outcome.UNKNOWN;
    }
    if (registry.isEmpty()) {
      return Outcome.of(values.isEmpty());
    }
    for (String value : values) {
      ResourceLocation id = ResourceLocation.tryParse(value);
      if (id == null || !registry.get().containsKey(id)) {
        return Outcome.FALSE;
      }
    }
    return Outcome.TRUE;
  }

  // null: the registry id is malformed. Empty: there is no such registry.
  @Nullable
  private Optional<? extends Registry<?>> registry(JsonObject json) {
    String name = json.has("registry") ? string(json, "registry") : Registries.ITEM.location().toString();
    ResourceLocation id = name == null ? null : ResourceLocation.tryParse(name);
    if (id == null) {
      return null;
    }
    return registries.registry(ResourceKey.createRegistryKey(id));
  }

  private Outcome featuresEnabled(JsonObject json) {
    List<String> features = strings(json, "features");
    if (features == null) {
      return Outcome.UNKNOWN;
    }
    boolean[] unknown = {false};
    List<ResourceLocation> ids = features.stream().map(ResourceLocation::tryParse).toList();
    if (ids.contains(null)) {
      return Outcome.UNKNOWN;
    }
    FeatureFlagSet set = FeatureFlags.REGISTRY.fromNames(ids, id -> unknown[0] = true);
    return Outcome.of(!unknown[0] && set.isSubsetOf(enabledFeatures));
  }

  // ----- NeoForge -----

  private Outcome neoforgeList(JsonElement element) {
    if (element.isJsonArray()) {
      return all(element.getAsJsonArray(), this::neoforge);
    }
    return neoforge(element);
  }

  private Outcome neoforge(JsonElement element) {
    if (!element.isJsonObject()) {
      return Outcome.UNKNOWN;
    }
    JsonObject json = element.getAsJsonObject();
    String type = normalise(string(json, "type"));
    if (type == null) {
      return Outcome.UNKNOWN;
    }
    int colon = type.indexOf(':');
    String namespace = type.substring(0, colon);
    if (!namespace.equals("neoforge") && !namespace.equals("forge")) {
      return Outcome.UNKNOWN;
    }
    return switch (type.substring(colon + 1)) {
      case "true", "always" -> Outcome.TRUE;
      case "false", "never" -> Outcome.FALSE;
      case "not" -> json.has("value") ? neoforge(json.get("value")).not() : Outcome.UNKNOWN;
      case "and" -> json.has("values") && json.get("values").isJsonArray() ? all(json.getAsJsonArray("values"), this::neoforge) : Outcome.UNKNOWN;
      case "or" -> json.has("values") && json.get("values").isJsonArray() ? any(json.getAsJsonArray("values"), this::neoforge) : Outcome.UNKNOWN;
      case "mod_loaded" -> string(json, "modid") == null ? Outcome.UNKNOWN : Outcome.of(modLoaded.test(string(json, "modid")));
      case "item_exists" -> itemExists(string(json, "item"));
      case "tag_empty" -> tagEmpty(string(json, "tag"));
      default -> Outcome.UNKNOWN;
    };
  }

  private Outcome itemExists(@Nullable String item) {
    ResourceLocation id = item == null ? null : ResourceLocation.tryParse(item);
    if (id == null) {
      return Outcome.UNKNOWN;
    }
    return Outcome.of(registries.registryOrThrow(Registries.ITEM).containsKey(id));
  }

  private Outcome tagEmpty(@Nullable String tag) {
    ResourceLocation id = tag == null ? null : ResourceLocation.tryParse(tag);
    if (id == null) {
      return Outcome.UNKNOWN;
    }
    return Outcome.of(registries.registryOrThrow(Registries.ITEM).getTag(TagKey.create(Registries.ITEM, id)).map(tagged -> tagged.size() == 0).orElse(true));
  }

  // ----- helpers -----

  private interface Evaluator {
    Outcome evaluate(JsonElement element);
  }

  private static Outcome all(JsonArray array, Evaluator evaluator) {
    Outcome result = Outcome.TRUE;
    for (JsonElement element : array) {
      Outcome outcome = evaluator.evaluate(element);
      if (outcome == Outcome.FALSE) {
        return Outcome.FALSE;
      }
      if (outcome == Outcome.UNKNOWN) {
        result = Outcome.UNKNOWN;
      }
    }
    return result;
  }

  private static Outcome any(JsonArray array, Evaluator evaluator) {
    Outcome result = Outcome.FALSE;
    for (JsonElement element : array) {
      Outcome outcome = evaluator.evaluate(element);
      if (outcome == Outcome.TRUE) {
        return Outcome.TRUE;
      }
      if (outcome == Outcome.UNKNOWN) {
        result = Outcome.UNKNOWN;
      }
    }
    return result;
  }

  @Nullable
  private static String string(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
  }

  @Nullable
  private static List<String> strings(JsonObject json, String key) {
    JsonElement element = json.get(key);
    if (element == null || !element.isJsonArray()) {
      return null;
    }
    List<String> result = new ArrayList<>();
    for (JsonElement value : element.getAsJsonArray()) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        return null;
      }
      result.add(value.getAsString());
    }
    return result;
  }

  // "all_mods_loaded" and "minecraft:x" style ids both become "namespace:path".
  @Nullable
  private static String normalise(@Nullable String id) {
    ResourceLocation parsed = id == null ? null : ResourceLocation.tryParse(id);
    return parsed == null ? null : parsed.toString();
  }
}
