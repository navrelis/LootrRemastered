package noobanidus.mods.lootr.neoforge.impl;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforgespi.language.IModInfo;
import noobanidus.mods.lootr.common.api.DataToCopy;
import noobanidus.mods.lootr.common.api.IPlatformAPI;
import noobanidus.mods.lootr.common.api.client.ContainerStatus;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.entity.ILootrEntity;
import noobanidus.mods.lootr.common.impl.DefaultPlatformAPIImpl;
import noobanidus.mods.lootr.common.mixin.accessor.AccessorMixinBaseContainerBlockEntity;
import noobanidus.mods.lootr.common.verify.PackOrigin;
import noobanidus.mods.lootr.neoforge.network.toClient.*;
import noobanidus.mods.lootr.neoforge.network.toServer.PacketRequestUpdate;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

@SuppressWarnings("deprecation")
public class PlatformAPIImpl extends DefaultPlatformAPIImpl implements IPlatformAPI {
  private static final String MOD_PACK_PREFIX = "mod/";

  @Override
  public void performEntityOpen(ILootrEntity entity, ServerPlayer player) {
    PacketDistributor.sendToPlayer(player, new PacketOpenCart(entity.asEntity().getId()));
  }

  @Override
  public void performEntityOpen(ILootrEntity entity) {
    PacketDistributor.sendToPlayersTrackingEntity(entity.asEntity(), new PacketOpenCart(entity.asEntity().getId()));
  }

  @Override
  public void performEntityClose(ILootrEntity entity, ServerPlayer player) {
    PacketDistributor.sendToPlayer(player, new PacketCloseCart(entity.asEntity().getId()));
  }

  @Override
  public void performEntityClose(ILootrEntity entity) {
    PacketDistributor.sendToPlayersTrackingEntity(entity.asEntity(), new PacketCloseCart(entity.asEntity().getId()));
  }

  @Override
  public void performBlockOpen(ILootrBlockEntity blockEntity, ServerPlayer player) {
    PacketDistributor.sendToPlayer(player, new PacketOpenContainer(blockEntity.asBlockEntity().getBlockPos()));
  }

  @Override
  public void performBlockOpen(ILootrBlockEntity blockEntity) {
    PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) blockEntity.getInfoLevel(), new ChunkPos(blockEntity.asBlockEntity()
        .getBlockPos()), new PacketOpenContainer(blockEntity.asBlockEntity().getBlockPos()));
  }

  @Override
  public void performBlockClose(ILootrBlockEntity blockEntity, ServerPlayer player) {
    PacketDistributor.sendToPlayer(player, new PacketCloseContainer(blockEntity.asBlockEntity().getBlockPos()));
  }

  @Override
  public void performBlockClose(ILootrBlockEntity blockEntity) {
    PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) blockEntity.getInfoLevel(), new ChunkPos(blockEntity.asBlockEntity()
        .getBlockPos()), new PacketCloseContainer(blockEntity.asBlockEntity().getBlockPos()));
  }

  @Override
  public DataToCopy copySpecificData(BlockEntity oldBlockEntity) {
    LockCode code = LockCode.NO_LOCK;
    if (oldBlockEntity instanceof BaseContainerBlockEntity baseContainer) {
      code = ((AccessorMixinBaseContainerBlockEntity) baseContainer).getLockKey();
    }
    return new DataToCopy(oldBlockEntity.getPersistentData(), code);
  }

  @Override
  public void restoreSpecificData(DataToCopy data, BlockEntity newBlockEntity) {
    if (data != DataToCopy.EMPTY) {
      newBlockEntity.getPersistentData().merge(data.data());
    }
    if (newBlockEntity instanceof BaseContainerBlockEntity baseContainer) {
      ((AccessorMixinBaseContainerBlockEntity) baseContainer).setLockKey(data.lockCode());
    }
  }

  @Override
  @Deprecated
  public void copyEntityData(AbstractMinecartContainer entity1, AbstractMinecartContainer entity2) {
    super.copyEntityData(entity1, entity2);
    entity2.getPersistentData().merge(entity1.getPersistentData());
  }

  @Override
  public void refreshPlayerSection(ServerPlayer player) {
    PacketDistributor.sendToPlayer(player, PacketRefreshSection.INSTANCE);
  }

  @Override
  public void performPotBreak(ILootrBlockEntity blockEntity, ServerPlayer player) {
    PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) blockEntity.getInfoLevel(), new ChunkPos(blockEntity.asBlockEntity()
        .getBlockPos()), new PacketPerformBreakEffect(player.getId(), blockEntity.asBlockEntity().getBlockPos()));
  }

  @Override
  public void performRequestSync(GlobalPos pos) {
    PacketDistributor.sendToServer(new PacketRequestUpdate(pos));
  }

  @Override
  public void syncAfterTeamChange(Player player) {
    var data = getSyncData(player);
    PacketDistributor.sendToPlayer((ServerPlayer) player, new PacketAreaEntitySync(data.getFirst(), data.getSecond()));
  }

  @Override
  public void alertContainerStatus(ServerPlayer player, ContainerStatus status, ContainerStatus.Type type, int remainingDuration) {
    PacketDistributor.sendToPlayer(player, new PacketContainerStatus(status, type, remainingDuration));
  }

  @Override
  public boolean isModLoaded(String modId) {
    return ModList.get().isLoaded(modId);
  }

  @Override
  public Path getConfigDirectory() {
    return FMLPaths.CONFIGDIR.get();
  }

  @Override
  public Path getGameDirectory() {
    return FMLPaths.GAMEDIR.get();
  }

  @Override
  @Nullable
  public PackOrigin describePack(String packId, @Nullable PackResources pack) {
    if (packId == null) {
      return null;
    }
    // NeoForge names the pack of a mod "mod/<mod id>" (the packs of all mods without an own pack entry live in
    // "mod_data" and "mod_resources") and a built-in pack of a mod "mod/<mod id>:<path>"; vanilla's own pack is "vanilla".
    if ("vanilla".equals(packId)) {
      return describeMod("minecraft");
    }
    if (!packId.startsWith(MOD_PACK_PREFIX)) {
      return null;
    }
    String modId = packId.substring(MOD_PACK_PREFIX.length());
    int colon = modId.indexOf(':');
    return describeMod(colon == -1 ? modId : modId.substring(0, colon));
  }

  @Override
  @Nullable
  public PackOrigin describeMod(String modId) {
    return ModList.get().getModContainerById(modId).map(container -> origin(container.getModInfo())).orElse(null);
  }

  private static PackOrigin origin(IModInfo info) {
    return PackOrigin.mod(info.getModId(), info.getDisplayName(), info.getVersion().toString());
  }
}
