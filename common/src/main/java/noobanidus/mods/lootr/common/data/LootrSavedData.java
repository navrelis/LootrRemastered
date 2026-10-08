package noobanidus.mods.lootr.common.data;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.*;
import noobanidus.mods.lootr.common.itemban.ItemBans;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class LootrSavedData extends SavedData implements ILootrSavedData {
  private boolean hasBeenOpened;
  private ILootrInfo info;
  private final Map<UUID, LootrInventory> inventories = new HashMap<>();
  private final Set<UUID> openers = new ObjectLinkedOpenHashSet<>();
  private final Set<UUID> actualOpeners = new ObjectLinkedOpenHashSet<>();

  protected LootrSavedData(ILootrInfo info) {
    this(info, false);
  }

  protected LootrSavedData(ILootrInfo info, boolean noCopy) {
    if (noCopy) {
      this.info = info;
    } else {
      this.info = BaseLootrInfo.copy(info);
    }
  }

  public static Supplier<LootrSavedData> fromInfo(ILootrInfo info) {
    return () -> new LootrSavedData(info);
  }

  public static LootrSavedData load(CompoundTag compound, HolderLookup.Provider provider) {
    ILootrInfo info = ILootrInfo.loadInfoFromTag(compound, provider);
    LootrSavedData data = new LootrSavedData(info, true);
    data.inventories.clear();
    data.openers.clear();
    data.actualOpeners.clear();

    ListTag compounds = compound.getList("inventories", Tag.TAG_COMPOUND);

    for (int i = 0; i < compounds.size(); i++) {
      CompoundTag thisTag = compounds.getCompound(i);
      CompoundTag itemTag = thisTag.getCompound("chest");
      NonNullList<ItemStack> items = info.buildInitialInventory();
      ContainerHelper.loadAllItems(itemTag, items, provider);
      UUID uuid = thisTag.getUUID("uuid");
      data.inventories.put(uuid, new LootrInventory(data, items));
    }

    if (compound.contains("openers")) {
      ListTag openers = compound.getList("openers", Tag.TAG_INT_ARRAY);
      for (Tag opener : openers) {
        data.openers.add(NbtUtils.loadUUID(opener));
      }
    }
    if (compound.contains("actualOpeners")) {
      ListTag openers = compound.getList("actualOpeners", Tag.TAG_INT_ARRAY);
      for (Tag opener : openers) {
        data.actualOpeners.add(NbtUtils.loadUUID(opener));
      }
    }
    if (compound.contains("hasBeenOpened")) {
      data.hasBeenOpened = compound.getBoolean("hasBeenOpened");
    }
    return data;
  }


  @Override
  public ILootrInfo getRedirect() {
    return info;
  }

  @Override
  public Set<UUID> getVisualOpeners() {
    return openers;
  }

  @Override
  public boolean addVisualOpener(Player player) {
    boolean result = ILootrSavedData.super.addVisualOpener(player);
    if (result) {
      setDirty();
    }
    return result;
  }

  @Override
  public boolean removeVisualOpener(Player player) {
    boolean result = ILootrSavedData.super.removeVisualOpener(player);
    if (result) {
      setDirty();
    }
    return result;
  }

  @Override
  public boolean addActualOpener(Player player) {
    boolean result = ILootrSavedData.super.addActualOpener(player);
    if (result) {
      setDirty();
    }
    return result;
  }

  private void removeOpener(UUID uuid) {
    Set<UUID> visualOpeners = getVisualOpeners();
    if (visualOpeners != null) {
      if (visualOpeners.remove(uuid)) {
        setDirty();
      }
    }
  }

  @Override
  public Set<UUID> getActualOpeners() {
    return actualOpeners;
  }

  @Override
  public void markChanged() {
    setDirty();
  }

  @Override
  public void markDataChanged() {
    markChanged();
  }

  @Override
  @Nullable
  public LootrInventory getInventory(UUID id) {
    LootrInventory inventory = inventories.get(id);
    if (inventory != null) {
      inventory.setInfo(this);
      // Every stored per-player inventory is handed out through here (opening, commands, pots, brushables,
      // item frames, minecarts), so this is where inventories saved before an item was banned are cleaned.
      if (ItemBans.isScrubbingSavedInventories()) {
        removeBannedItems(inventory, id);
      }
    }
    return inventory;
  }

  // Deleting a banned stack is permanent by design: it is not kept anywhere and does not come back when the
  // ban is lifted. Nothing is re-rolled. `LootrInventory::setItem` marks this data as changed, so it is saved.
  private void removeBannedItems(LootrInventory inventory, UUID id) {
    int removed = ItemBans.scrub(inventory);
    if (removed > 0) {
      LootrAPI.LOG.debug("Removed {} banned item stack(s) from the inventory of {} in Lootr container {}.", removed, id, getInfoUUID());
    }
  }

  @Override
  public LootrInventory createInventory(ILootrInfoProvider provider, ServerPlayer player, LootFiller filler) {
    if (provider.canPlayerOpen(player)) {
      LootrInventory result = new LootrInventory(this, provider.buildInitialInventory());
      if (!LootrAPI.isFakePlayer(player)) {
        filler.unpackLootTable(provider, player, result);
        // A new inventory is handed out without passing `getInventory`; this also covers custom fillers.
        if (ItemBans.isActive()) {
          removeBannedItems(result, LootrAPI.resolveServerPlayerTeam(player));
        }
      }
      inventories.put(LootrAPI.resolveServerPlayerTeam(player), result);
      hasBeenOpened = true;
      setDirty();
      return result;
    } else {
      provider.informPlayerCannotOpen(player);
      return null;
    }
  }

  @Override
  @Nullable
  public LootrInventory createInventory(ILootrInfoProvider provider, UUID playerId, LootFiller filler) {
    ServerPlayer player = LootrAPI.getPlayer(playerId);
    if (player == null) {
      // Loot fillers need the player (luck, advancements). Nothing is created or stored for an offline player.
      LootrAPI.LOG.debug("Not creating an inventory in Lootr container {} for {}: the player is not online.", getInfoUUID(), playerId);
      return null;
    }
    LootrInventory result = new LootrInventory(this, provider.buildInitialInventory());
    filler.unpackLootTable(provider, player, result);
    if (ItemBans.isActive()) {
      removeBannedItems(result, playerId);
    }
    inventories.put(playerId, result);
    hasBeenOpened = true;
    setDirty();
    return result;
  }

  @Override
  public CompoundTag save(CompoundTag compound, HolderLookup.Provider provider) {
    this.info.saveInfoToTag(compound, provider);

    ListTag compounds = new ListTag();
    for (Map.Entry<UUID, LootrInventory> entry : inventories.entrySet()) {
      CompoundTag thisTag = new CompoundTag();
      thisTag.putUUID("uuid", entry.getKey());
      thisTag.put("chest", entry.getValue().saveToTag(provider));
      compounds.add(thisTag);
    }
    compound.put("inventories", compounds);
    ListTag openers = new ListTag();
    for (UUID opener : this.openers) {
      openers.add(NbtUtils.createUUID(opener));
    }
    compound.put("openers", openers);
    ListTag actualOpeners = new ListTag();
    for (UUID opener : this.actualOpeners) {
      actualOpeners.add(NbtUtils.createUUID(opener));
    }
    compound.put("actualOpeners", actualOpeners);

    compound.putBoolean("hasBeenOpened", hasBeenOpened);

    return compound;
  }

  @Override
  public void update(ILootrInfo info) {
    BaseLootrInfo infoCopy = BaseLootrInfo.copy(info);
    if (!infoCopy.equals(this.info)) {
      if (isSavedInfoDifferent(this.info, infoCopy)) {
        markChanged();
      }
      // Always the copy: the live provider (a block entity or an entity) must not be kept alive by the cache.
      this.info = infoCopy;
    }
  }

  // Compares what `ILootrInfo.loadInfoFromTag` reads back. The deprecated type fields and the key are not
  // among it: loaded info has no deprecated types and derives its key, so comparing them would make every
  // loaded container look changed.
  private static boolean isSavedInfoDifferent(ILootrInfo saved, ILootrInfo current) {
    if (!Objects.equals(saved.getInfoNewType(), current.getInfoNewType()) || !saved.getInfoUUID().equals(current.getInfoUUID())) {
      return true;
    }
    if (!saved.getInfoPos().equals(current.getInfoPos()) || !saved.getInfoDimension().equals(current.getInfoDimension()) || saved.getInfoContainerSize() != current.getInfoContainerSize()) {
      return true;
    }
    if (!Objects.equals(saved.getInfoLootTable(), current.getInfoLootTable()) || (current.getInfoLootTable() != null && saved.getInfoLootSeed() != current.getInfoLootSeed())) {
      return true;
    }
    if (!Objects.equals(saved.getInfoDisplayName(), current.getInfoDisplayName())) {
      return true;
    }
    NonNullList<ItemStack> savedReference = saved.isInfoReferenceInventory() ? saved.getInfoReferenceInventory() : null;
    NonNullList<ItemStack> currentReference = current.isInfoReferenceInventory() ? current.getInfoReferenceInventory() : null;
    if (savedReference == null || currentReference == null) {
      return savedReference != currentReference;
    }
    return savedReference != currentReference && !ItemStack.listMatches(savedReference, currentReference);
  }

  @Override
  public void refresh() {
    closeOpenMenus(null);
    inventories.clear();
    hasBeenOpened = false;
    markChanged();
  }

  // A menu that stays open on an inventory that is no longer stored (after a refresh, or after the inventory
  // was cleared) keeps showing it: what is put into it is lost and what is taken out of it comes on top of
  // the new loot. Closing it makes the next opening create the new inventory. `only` limits this to the
  // menus that show that one inventory; null means every inventory of this container.
  private void closeOpenMenus(@Nullable LootrInventory only) {
    MinecraftServer server = LootrAPI.getServer();
    if (server == null || inventories.isEmpty()) {
      return;
    }
    for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
      if (!player.hasContainerOpen()) {
        continue;
      }
      for (Slot slot : player.containerMenu.slots) {
        if (slot.container instanceof LootrInventory inventory && (only == null ? inventory.getInfo() == this : inventory == only)) {
          player.closeContainer();
          player.displayClientMessage(Component.translatable("lootr.message.refreshed_while_open"), true);
          break;
        }
      }
    }
  }

  // This is triggered in createInventory and reset in refresh.
  @Override
  public boolean hasBeenOpened() {
    return hasBeenOpened;
  }

  // Openers are content as well: without any inventory they still say who sees the container as opened and
  // who was counted for it (statistic, advancements, `lootr:loot_count`). A refresh leaves the actual openers.
  public boolean canBeCulled() {
    if (!inventories.isEmpty() || !openers.isEmpty() || !actualOpeners.isEmpty()) {
      return false;
    }

    return !hasBeenOpened();
  }

  @Override
  public boolean isPhysicallyOpen() {
    return false;
  }

  @Override
  public boolean clearInventories(UUID id) {
    LootrInventory inventory = inventories.get(id);
    if (inventory != null) {
      closeOpenMenus(inventory);
      inventories.remove(id);
      removeOpener(id);
      setDirty();
      return true;
    }

    return false;
  }

  @Override
  public void save(File pFile, HolderLookup.Provider provider) {
    if (isDirty()) {
      // Data without content that has no file needs none: nobody opened the container, and what it knows
      // about the container comes from the container again. An existing file is still brought up to date.
      if (canBeCulled() && !pFile.exists()) {
        setDirty(false);
        return;
      }
      pFile.getParentFile().mkdirs();
    }
    super.save(pFile, provider);
  }
}
