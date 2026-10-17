package noobanidus.mods.lootr.common.locate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.api.data.ILootrSavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything `/lootr check` says about one container, as data.
 */
public final class ContainerCheck {
  /**
   * How far a player reaches with `/lootr check` when it takes the container the player is looking at.
   */
  public static final double REACH = 8.0;

  private ContainerCheck() {
  }

  /**
   * @param uuid                the info UUID of a Lootr container; null for a vanilla one.
   * @param sample              the sample rolls; only for a table with the status OK, else null.
   * @param structures          the ids of the structures that have a piece at the position of the container.
   * @param openers             how many players actually opened a Lootr container; 0 for a vanilla one.
   * @param playerHasInventory  whether the player has an inventory in the container; null if there is no player or the
   *                            container is a vanilla one.
   */
  public record Report(
      ContainerInfo container,
      @Nullable UUID uuid,
      LootTableStatus.Result status,
      @Nullable LootTableStatus.Sample sample,
      LootTableStatus.Integrated integrated,
      List<ResourceLocation> structures,
      int openers,
      @Nullable Boolean playerHasInventory) {
  }

  /**
   * Must be called on the server thread with the level the container is in.
   *
   * @param player the player the inventory question is about; may be null.
   */
  public static Report run(ServerLevel level, ContainerInfo container, @Nullable ServerPlayer player) {
    LootTableStatus.Result status = LootTableStatus.of(level.getServer(), container.table());
    LootTableStatus.Sample sample = status.kind() == LootTableStatus.Kind.OK && container.table() != null
        ? LootTableStatus.sample(level, container.table(), container.center())
        : null;
    ILootrInfoProvider provider = container.provider();
    UUID uuid = provider == null ? null : provider.getInfoUUID();
    int openers = 0;
    Boolean hasInventory = null;
    if (provider != null) {
      ILootrSavedData data = LootrAPI.getExistingData(provider.getInfoKey());
      if (data != null) {
        Set<UUID> actual = data.getActualOpeners();
        openers = actual == null ? 0 : actual.size();
      }
      if (player != null) {
        hasInventory = data != null && data.getInventory(LootrAPI.resolveServerPlayerTeam(player)) != null;
      }
    }
    return new Report(container, uuid, status, sample, LootTableStatus.integrated(container.table()), structuresAt(level, container.pos()), openers, hasInventory);
  }

  /**
   * The ids of the structures that have a piece at the position, sorted. The position has to be in a loaded chunk.
   */
  public static List<ResourceLocation> structuresAt(ServerLevel level, BlockPos pos) {
    if (!level.hasChunkAt(pos)) {
      return List.of();
    }
    Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
    List<ResourceLocation> result = new ArrayList<>();
    for (Map.Entry<Structure, ?> entry : level.structureManager().getAllStructuresAt(pos).entrySet()) {
      StructureStart start = level.structureManager().getStructureWithPieceAt(pos, entry.getKey());
      if (start.isValid()) {
        ResourceLocation id = registry.getKey(entry.getKey());
        if (id != null && !result.contains(id)) {
          result.add(id);
        }
      }
    }
    result.sort(null);
    return result;
  }

  /**
   * The container the player looks at: the nearer of the block entity and the entity (a Lootr minecart or item
   * frame) that the line of sight hits within the reach.
   *
   * @return null if the player looks at no container.
   */
  @Nullable
  public static ContainerInfo lookingAt(ServerPlayer player, double reach) {
    ServerLevel level = player.serverLevel();
    Vec3 eye = player.getEyePosition();
    Vec3 look = player.getViewVector(1.0F);
    Vec3 end = eye.add(look.scale(reach));
    BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    double blockDistance = block.getType() == HitResult.Type.MISS ? reach * reach : eye.distanceToSqr(block.getLocation());
    AABB search = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
    EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, end, search, candidate -> !candidate.isSpectator() && candidate.isPickable() && ContainerLocator.describe(candidate) != null, reach * reach);
    if (entity != null && eye.distanceToSqr(entity.getLocation()) <= blockDistance) {
      return ContainerLocator.describe(entity.getEntity());
    }
    if (block.getType() == HitResult.Type.BLOCK) {
      BlockEntity blockEntity = level.getBlockEntity(block.getBlockPos());
      return ContainerLocator.describe(blockEntity);
    }
    return null;
  }
}
