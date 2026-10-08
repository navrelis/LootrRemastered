package noobanidus.mods.lootr.common.itemban;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of the `item_bans` configuration section. The platform pushes a new snapshot into
 * {@link ItemBans#applySettings(ItemBanSettings)} whenever its configuration is loaded or saved.
 *
 * @param respectItemObliterator Use the mod Item Obliterator as a ban source when it is installed.
 * @param filterAllLoot          Drop banned stacks from every loot table roll, not only from Lootr's own fills.
 * @param scrubSavedInventories  Remove banned stacks from already saved per-player inventories when they are accessed.
 * @param extraBannedItems       Additional bans: `namespace:path` item ids or `#namespace:path` item tags.
 */
public record ItemBanSettings(boolean respectItemObliterator, boolean filterAllLoot, boolean scrubSavedInventories, List<String> extraBannedItems) {
  public static final ItemBanSettings DEFAULT = new ItemBanSettings(true, true, true, List.of());

  public ItemBanSettings {
    extraBannedItems = extraBannedItems == null ? List.of() : extraBannedItems.stream().filter(Objects::nonNull).toList();
  }

  public ItemBanSettings withRespectItemObliterator(boolean value) {
    return new ItemBanSettings(value, filterAllLoot, scrubSavedInventories, extraBannedItems);
  }

  public ItemBanSettings withFilterAllLoot(boolean value) {
    return new ItemBanSettings(respectItemObliterator, value, scrubSavedInventories, extraBannedItems);
  }

  public ItemBanSettings withScrubSavedInventories(boolean value) {
    return new ItemBanSettings(respectItemObliterator, filterAllLoot, value, extraBannedItems);
  }

  public ItemBanSettings withExtraBannedItems(List<String> value) {
    return new ItemBanSettings(respectItemObliterator, filterAllLoot, scrubSavedInventories, value);
  }
}
