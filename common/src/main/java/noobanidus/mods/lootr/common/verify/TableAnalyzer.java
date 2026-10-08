package noobanidus.mods.lootr.common.verify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Reads a loot table in its JSON form (the loaded table encoded with the game's codec, or the file where that is
 * not possible) and says what can be said without rolling it: which loot tables it refers to, which banned items
 * it names and whether any of its entries can produce an item at all. Conditions and functions are not evaluated.
 * <br />
 * Stateless apart from the caches of {@link ItemLookup}, which are thread-safe.
 */
final class TableAnalyzer {
  private static final String ITEM = "minecraft:item";
  private static final String TAG = "minecraft:tag";
  private static final String LOOT_TABLE = "minecraft:loot_table";
  private static final String EMPTY = "minecraft:empty";
  private static final Set<String> COMPOSITES = Set.of("minecraft:alternatives", "minecraft:group", "minecraft:sequence");
  private static final String SET_LOOT_TABLE = "minecraft:set_loot_table";

  private TableAnalyzer() {
  }

  /**
   * @param kind  one of the loot table kinds of {@link ReferenceIndex}.
   * @param table the referenced id as written.
   */
  record Reference(String kind, String table) {
  }

  /**
   * @param size   how many items the tag holds.
   * @param banned the ids of the banned ones.
   */
  record TagFacts(int size, List<String> banned) {
  }

  static final class Facts {
    int pools;
    // True if an entry can produce without the help of another loot table.
    boolean producesDirectly;
    // The loot tables referred to by nested table entries that count for "can produce", as written.
    final Set<String> nested = new LinkedHashSet<>();
    final List<Reference> references = new ArrayList<>();
    final TreeSet<String> bannedItems = new TreeSet<>();
    final TreeMap<String, TagFacts> bannedTags = new TreeMap<>();
    final TreeSet<String> unknownItems = new TreeSet<>();
    // What the entries that cannot produce are; they say why a table that cannot produce cannot.
    private boolean sawEmpty;
    private boolean sawBanned;
    private boolean sawUnknownItem;
    private boolean sawNested;

    /**
     * @return why the table cannot produce, given that it cannot: `no_pools`, `only_empty_entries`,
     * `only_banned_items`, `only_unproductive_references` or, for a mixture, `no_producing_entry`.
     */
    String reason() {
      if (pools == 0) {
        return "no_pools";
      }
      if (!sawUnknownItem && sawEmpty && !sawBanned && !sawNested) {
        return "only_empty_entries";
      }
      if (!sawUnknownItem && !sawEmpty && sawBanned && !sawNested) {
        return "only_banned_items";
      }
      if (!sawUnknownItem && !sawEmpty && !sawBanned && sawNested) {
        return "only_unproductive_references";
      }
      return "no_producing_entry";
    }
  }

  /**
   * What the analysis needs to know about items and item tags, with every answer computed once.
   */
  static final class ItemLookup {
    enum State {
      UNKNOWN,
      AIR,
      BANNED,
      USABLE
    }

    private final Predicate<ItemStack> banned;
    private final Map<ResourceLocation, State> items = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, Optional<TagFacts>> tags = new ConcurrentHashMap<>();

    /**
     * @param banned the ban check; null if no ban source is active.
     */
    ItemLookup(@Nullable Predicate<ItemStack> banned) {
      this.banned = banned;
    }

    State item(ResourceLocation id) {
      return items.computeIfAbsent(id, key -> {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(key);
        if (item.isEmpty()) {
          return State.UNKNOWN;
        }
        if (item.get() == Items.AIR) {
          return State.AIR;
        }
        return isBanned(item.get()) ? State.BANNED : State.USABLE;
      });
    }

    private boolean isBanned(Item item) {
      return banned != null && banned.test(new ItemStack(item));
    }

    /**
     * @return the tag's facts, or nothing if there is no such item tag.
     */
    Optional<TagFacts> tag(ResourceLocation id) {
      return tags.computeIfAbsent(id, key -> {
        Optional<HolderSet.Named<Item>> tag = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, key));
        if (tag.isEmpty()) {
          return Optional.empty();
        }
        List<String> bannedIds = new ArrayList<>();
        int size = 0;
        for (Holder<Item> holder : tag.get()) {
          size++;
          if (isBanned(holder.value())) {
            bannedIds.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString());
          }
        }
        bannedIds.sort(null);
        return Optional.of(new TagFacts(size, List.copyOf(bannedIds)));
      });
    }
  }

  static Facts analyse(JsonElement table, ItemLookup items) {
    Facts facts = new Facts();
    if (table.isJsonObject()) {
      JsonElement pools = table.getAsJsonObject().get("pools");
      facts.pools = pools != null && pools.isJsonArray() ? pools.getAsJsonArray().size() : 0;
      facts.producesDirectly = tableProduces(table.getAsJsonObject(), items, facts);
    }
    collect(table, false, items, facts);
    return facts;
  }

  // ----- can it produce -----

  private static boolean tableProduces(JsonObject table, ItemLookup items, Facts facts) {
    JsonElement pools = table.get("pools");
    if (pools == null || !pools.isJsonArray()) {
      return false;
    }
    boolean produces = false;
    for (JsonElement pool : pools.getAsJsonArray()) {
      if (pool.isJsonObject()) {
        // No short cut: every entry is visited so that all nested references are collected.
        produces |= anyProduces(pool.getAsJsonObject().get("entries"), items, facts);
      }
    }
    return produces;
  }

  private static boolean anyProduces(@Nullable JsonElement entries, ItemLookup items, Facts facts) {
    if (entries == null || !entries.isJsonArray() || entries.getAsJsonArray().isEmpty()) {
      facts.sawEmpty = true;
      return false;
    }
    boolean produces = false;
    for (JsonElement entry : entries.getAsJsonArray()) {
      // An entry that is no object is nothing this class understands: never flagged.
      produces |= !entry.isJsonObject() || entryProduces(entry.getAsJsonObject(), items, facts);
    }
    return produces;
  }

  private static boolean entryProduces(JsonObject entry, ItemLookup items, Facts facts) {
    String type = normalise(string(entry, "type"));
    if (type == null) {
      return true;
    }
    if (type.equals(ITEM)) {
      ResourceLocation id = id(string(entry, "name"));
      ItemLookup.State state = id == null ? ItemLookup.State.UNKNOWN : items.item(id);
      switch (state) {
        case AIR -> facts.sawEmpty = true;
        case BANNED -> facts.sawBanned = true;
        case UNKNOWN -> facts.sawUnknownItem = true;
        case USABLE -> {
        }
      }
      return state == ItemLookup.State.USABLE;
    }
    if (type.equals(TAG)) {
      ResourceLocation id = id(string(entry, "name"));
      Optional<TagFacts> tag = id == null ? Optional.empty() : items.tag(id);
      if (tag.isEmpty() || tag.get().size() == 0) {
        facts.sawEmpty = true;
        return false;
      }
      if (tag.get().size() == tag.get().banned().size()) {
        facts.sawBanned = true;
        return false;
      }
      return true;
    }
    if (type.equals(LOOT_TABLE)) {
      JsonElement value = entry.get("value");
      if (value != null && value.isJsonObject()) {
        return tableProduces(value.getAsJsonObject(), items, facts);
      }
      String name = string(entry, "value");
      if (name == null) {
        return true;
      }
      facts.nested.add(normaliseOrRaw(name));
      facts.sawNested = true;
      return false;
    }
    if (type.equals(EMPTY)) {
      facts.sawEmpty = true;
      return false;
    }
    if (COMPOSITES.contains(type)) {
      return anyProduces(entry.get("children"), items, facts);
    }
    // Dynamic entries and entry types of other mods cannot be judged.
    return true;
  }

  // ----- references and banned items -----

  private static void collect(JsonElement element, boolean entryList, ItemLookup items, Facts facts) {
    if (element.isJsonArray()) {
      for (JsonElement child : element.getAsJsonArray()) {
        collect(child, entryList, items, facts);
      }
      return;
    }
    if (!element.isJsonObject()) {
      return;
    }
    JsonObject json = element.getAsJsonObject();
    if (entryList) {
      collectEntry(json, items, facts);
    }
    if (SET_LOOT_TABLE.equals(normalise(string(json, "function")))) {
      String name = string(json, "name");
      if (name != null && !name.isEmpty()) {
        facts.references.add(new Reference(ReferenceIndex.SET_LOOT_TABLE, normaliseOrRaw(name)));
      }
    }
    for (Map.Entry<String, JsonElement> child : json.entrySet()) {
      String key = child.getKey();
      if (key.equals("loot_table")) {
        // The item component `minecraft:container_loot`, e.g. in `set_components`.
        String name = string(json, key);
        if (name != null && !name.isEmpty()) {
          facts.references.add(new Reference(ReferenceIndex.COMPONENT, normaliseOrRaw(name)));
        }
      }
      collect(child.getValue(), key.equals("entries") || key.equals("children"), items, facts);
    }
  }

  private static void collectEntry(JsonObject entry, ItemLookup items, Facts facts) {
    String type = normalise(string(entry, "type"));
    if (ITEM.equals(type)) {
      String name = string(entry, "name");
      ResourceLocation id = id(name);
      ItemLookup.State state = id == null ? ItemLookup.State.UNKNOWN : items.item(id);
      if (state == ItemLookup.State.UNKNOWN && name != null) {
        facts.unknownItems.add(normaliseOrRaw(name));
      } else if (state == ItemLookup.State.BANNED) {
        facts.bannedItems.add(id.toString());
      }
    } else if (TAG.equals(type)) {
      ResourceLocation id = id(string(entry, "name"));
      Optional<TagFacts> tag = id == null ? Optional.empty() : items.tag(id);
      if (tag.isPresent() && !tag.get().banned().isEmpty()) {
        facts.bannedTags.put(id.toString(), tag.get());
      }
    } else if (LOOT_TABLE.equals(type)) {
      String name = string(entry, "value");
      if (name != null && !name.isEmpty()) {
        facts.references.add(new Reference(ReferenceIndex.NESTED_TABLE, normaliseOrRaw(name)));
      }
    }
  }

  // ----- helpers -----

  @Nullable
  private static String string(JsonObject json, String key) {
    JsonElement element = json.get(key);
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : null;
  }

  @Nullable
  private static ResourceLocation id(@Nullable String text) {
    return text == null ? null : ResourceLocation.tryParse(text);
  }

  @Nullable
  private static String normalise(@Nullable String text) {
    ResourceLocation id = id(text);
    return id == null ? null : id.toString();
  }

  static String normaliseOrRaw(String text) {
    ResourceLocation id = ResourceLocation.tryParse(text);
    return id == null ? text : id.toString();
  }
}
