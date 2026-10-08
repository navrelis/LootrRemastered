package noobanidus.mods.lootr.common.itemban;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import noobanidus.mods.lootr.common.api.LootrAPI;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bans from Lootr's own configuration: `namespace:path` item ids and `#namespace:path` item tags.
 * <br />
 * The entries are parsed lazily on first use and the result is cached until {@link #setEntries(List)}
 * (configuration loaded or saved) or {@link #invalidate()} (data packs reloaded) is called.
 */
public final class ConfigBanSource implements ItemBanSource {
  public static final String NAME = "lootr_config";

  private volatile List<String> entries = List.of();
  // null means "not parsed yet"; replaced as a whole, never mutated after publication
  private volatile Parsed parsed;

  public synchronized void setEntries(List<String> newEntries) {
    this.entries = List.copyOf(newEntries);
    this.parsed = null;
  }

  public synchronized void invalidate() {
    this.parsed = null;
  }

  public boolean hasEntries() {
    return !entries.isEmpty();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean isBanned(ItemStack stack) {
    Parsed current = parsed;
    if (current == null) {
      current = parse();
    }
    if (current.items.contains(stack.getItem())) {
      return true;
    }
    // Tag membership is checked live, so the answer follows the currently loaded tags.
    for (TagKey<Item> tag : current.tags) {
      if (stack.is(tag)) {
        return true;
      }
    }
    return false;
  }

  private synchronized Parsed parse() {
    Parsed current = parsed;
    if (current != null) {
      return current;
    }
    Set<Item> items = new HashSet<>();
    List<TagKey<Item>> tags = new ArrayList<>();
    for (String raw : entries) {
      String entry = raw.trim();
      boolean isTag = entry.startsWith("#");
      ResourceLocation id = ResourceLocation.tryParse(isTag ? entry.substring(1) : entry);
      if (entry.isEmpty() || id == null) {
        LootrAPI.LOG.warn("Ignoring invalid entry '{}' in the Lootr configuration list 'extra_banned_items': expected 'namespace:path' or '#namespace:path'.", raw);
        continue;
      }
      if (isTag) {
        TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
        if (BuiltInRegistries.ITEM.getTag(tag).isEmpty()) {
          LootrAPI.LOG.warn("The item tag '{}' in the Lootr configuration list 'extra_banned_items' does not exist at the moment and bans nothing.", entry);
        }
        tags.add(tag);
      } else {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
        if (item.isEmpty() || item.get() == Items.AIR) {
          LootrAPI.LOG.warn("Ignoring unknown item '{}' in the Lootr configuration list 'extra_banned_items'.", raw);
          continue;
        }
        items.add(item.get());
      }
    }
    current = new Parsed(Set.copyOf(items), List.copyOf(tags));
    parsed = current;
    return current;
  }

  private record Parsed(Set<Item> items, List<TagKey<Item>> tags) {
  }
}
