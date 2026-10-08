package noobanidus.mods.lootr.common.verify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Map;

/**
 * Finds loot table ids in a structure template and in a worldgen JSON file. Both are walked generically and
 * recognised by key, so that block entities and entities of other mods are covered as long as they use the usual
 * keys. Pure and stateless.
 */
final class ReferenceScanner {
  private static final String UNKNOWN = "unknown";

  interface Sink {
    /**
     * @param kind   one of the kinds of {@link ReferenceIndex}.
     * @param holder the id of the block entity, entity, item or block the reference belongs to, or `unknown`.
     * @param table  the loot table id as written.
     */
    void reference(String kind, String holder, String table);
  }

  private ReferenceScanner() {
  }

  // ----- structure templates -----

  /**
   * @param root the root of a structure template (`size`, `palette` or `palettes`, `blocks`, `entities`).
   */
  static void scanTemplate(CompoundTag root, Sink sink) {
    String[] palette = paletteNames(root);
    for (String key : root.getAllKeys()) {
      Tag tag = root.get(key);
      if (key.equals("palette") || key.equals("palettes") || key.equals("size")) {
        continue;
      }
      if (key.equals("blocks") && tag instanceof ListTag blocks) {
        for (Tag element : blocks) {
          if (!(element instanceof CompoundTag block) || !block.contains("nbt")) {
            continue;
          }
          // A block entity usually names itself; if it does not, the block does.
          int state = block.getInt("state");
          walk(block.get("nbt"), state >= 0 && state < palette.length ? palette[state] : UNKNOWN, "", false, sink);
        }
      } else {
        walk(tag, UNKNOWN, key, key.equals("entities"), sink);
      }
    }
  }

  private static String[] paletteNames(CompoundTag root) {
    ListTag palette = null;
    if (root.contains("palette", Tag.TAG_LIST)) {
      palette = root.getList("palette", Tag.TAG_COMPOUND);
    } else if (root.contains("palettes", Tag.TAG_LIST)) {
      ListTag palettes = root.getList("palettes", Tag.TAG_LIST);
      if (!palettes.isEmpty()) {
        palette = palettes.getList(0);
      }
    }
    if (palette == null) {
      return new String[0];
    }
    String[] names = new String[palette.size()];
    for (int i = 0; i < names.length; i++) {
      String name = palette.getCompound(i).getString("Name");
      names[i] = name.isEmpty() ? UNKNOWN : name;
    }
    return names;
  }

  // `entity` is true below the template's entity list: a `LootTable` there belongs to a container entity.
  private static void walk(Tag tag, String holder, String parentKey, boolean entity, Sink sink) {
    if (tag instanceof CompoundTag compound) {
      if (compound.contains("id", Tag.TAG_STRING)) {
        holder = compound.getString("id");
      }
      for (String key : compound.getAllKeys()) {
        Tag value = compound.get(key);
        if (value instanceof StringTag string) {
          String kind = switch (key) {
            case "LootTable" -> entity ? ReferenceIndex.ENTITY_CONTAINER : ReferenceIndex.CONTAINER;
            case "DeathLootTable" -> ReferenceIndex.ENTITY_DEATH;
            case "loot_table" -> lootTableKind(parentKey, holder);
            case "override_loot_table_to_display" -> ReferenceIndex.VAULT_DISPLAY;
            case "items_to_drop_when_ominous" -> ReferenceIndex.TRIAL_SPAWNER;
            default -> null;
          };
          if (kind != null && !string.getAsString().isEmpty()) {
            sink.reference(kind, holder, string.getAsString());
          }
        } else if (key.equals("loot_tables_to_eject") && value instanceof ListTag list) {
          // A weighted list: entries of the form {data: "<id>", weight: n}.
          for (Tag element : list) {
            String table = element instanceof CompoundTag entry ? entry.getString("data") : element instanceof StringTag string ? string.getAsString() : "";
            if (!table.isEmpty()) {
              sink.reference(ReferenceIndex.TRIAL_SPAWNER, holder, table);
            }
          }
        } else {
          walk(value, holder, key, entity, sink);
        }
      }
    } else if (tag instanceof ListTag list) {
      for (Tag element : list) {
        walk(element, holder, parentKey, entity, sink);
      }
    }
  }

  private static String lootTableKind(String parentKey, String holder) {
    if (parentKey.equals("minecraft:container_loot")) {
      return ReferenceIndex.CONTAINER_ITEM;
    }
    if (parentKey.equals("equipment")) {
      return ReferenceIndex.EQUIPMENT;
    }
    if (parentKey.equals("config") && holder.endsWith("vault")) {
      return ReferenceIndex.VAULT;
    }
    return ReferenceIndex.OTHER;
  }

  // ----- worldgen JSON -----

  /**
   * Collects the string values under keys named `loot_table`. The holder is the block a processor rule places
   * (`output_state.Name` next to the `block_entity_modifier`), where there is one.
   *
   * @param kind the kind to report, e.g. `processor_list`.
   */
  static void scanWorldgen(JsonElement element, String kind, Sink sink) {
    walk(element, UNKNOWN, kind, sink);
  }

  private static void walk(JsonElement element, String holder, String kind, Sink sink) {
    if (element.isJsonObject()) {
      JsonObject json = element.getAsJsonObject();
      JsonElement state = json.get("output_state");
      if (state != null && state.isJsonObject() && isString(state.getAsJsonObject().get("Name"))) {
        holder = state.getAsJsonObject().get("Name").getAsString();
      }
      for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
        JsonElement value = entry.getValue();
        if (entry.getKey().equals("loot_table") && isString(value)) {
          if (!value.getAsString().isEmpty()) {
            sink.reference(kind, holder, value.getAsString());
          }
        } else if (entry.getKey().equals("loot_table") && value.isJsonArray() && allStrings(value.getAsJsonArray())) {
          for (JsonElement table : value.getAsJsonArray()) {
            sink.reference(kind, holder, table.getAsString());
          }
        } else {
          walk(value, holder, kind, sink);
        }
      }
    } else if (element.isJsonArray()) {
      for (JsonElement child : element.getAsJsonArray()) {
        walk(child, holder, kind, sink);
      }
    }
  }

  private static boolean isString(JsonElement element) {
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
  }

  private static boolean allStrings(JsonArray array) {
    for (JsonElement element : array) {
      if (!isString(element) || element.getAsString().isEmpty()) {
        return false;
      }
    }
    return !array.isEmpty();
  }
}
