package noobanidus.mods.lootr.common.locate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.api.ILootrType;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.adapter.ILootrDataAdapter;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.entity.ILootrEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds the containers with a loot table: Lootr containers, and vanilla containers (anything Lootr has an adapter
 * for) that still have a loot table because Lootr has not converted them yet. All of it works on chunks that are
 * loaded and must run on the server thread; loading chunks is {@link ChunkScan}'s job.
 */
public final class ContainerLocator {
  private ContainerLocator() {
  }

  /**
   * @return the container the block entity is, or null if it is no container with a loot table and no Lootr container.
   */
  @Nullable
  public static ContainerInfo describe(@Nullable BlockEntity blockEntity) {
    if (blockEntity == null) {
      return null;
    }
    ILootrBlockEntity lootr = LootrAPI.resolveBlockEntity(blockEntity);
    if (lootr != null) {
      return lootr(lootr, false);
    }
    ILootrDataAdapter<BlockEntity> adapter = LootrAPI.getAdapter(blockEntity);
    if (adapter == null || blockEntity.getLevel() == null) {
      return null;
    }
    ResourceKey<LootTable> table = adapter.getLootTable(blockEntity);
    if (table == null) {
      return null;
    }
    ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
    return new ContainerInfo(ContainerInfo.Kind.VANILLA, String.valueOf(type), blockEntity.getLevel().dimension(), blockEntity.getBlockPos(), false, table, adapter.getLootSeed(blockEntity), null);
  }

  /**
   * @return the container the entity is, or null if it is no container with a loot table and no Lootr container.
   */
  @Nullable
  public static ContainerInfo describe(@Nullable Entity entity) {
    if (entity == null) {
      return null;
    }
    if (entity instanceof ILootrEntity lootr) {
      return lootr(lootr, true);
    }
    ILootrDataAdapter<Entity> adapter = LootrAPI.getAdapter(entity);
    if (adapter == null) {
      return null;
    }
    ResourceKey<LootTable> table = adapter.getLootTable(entity);
    if (table == null) {
      return null;
    }
    ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    return new ContainerInfo(ContainerInfo.Kind.VANILLA, String.valueOf(type), entity.level().dimension(), entity.blockPosition(), true, table, adapter.getLootSeed(entity), null);
  }

  private static ContainerInfo lootr(ILootrInfoProvider provider, boolean entity) {
    ILootrType type = provider.getInfoNewType();
    // A container with a custom inventory has no loot table of its own, whatever its info says (item frames
    // report a placeholder table).
    ResourceKey<LootTable> table = provider.isInfoReferenceInventory() ? null : provider.getInfoLootTable();
    return new ContainerInfo(ContainerInfo.Kind.LOOTR, type == null ? "lootr:unknown" : type.getName(), provider.getInfoDimension(), provider.getInfoPos(), entity, table, provider.getInfoLootSeed(), provider);
  }

  /**
   * The container at a position of a loaded chunk: its block entity, or else an entity within the block. Never
   * loads a chunk.
   */
  @Nullable
  public static ContainerInfo at(ServerLevel level, BlockPos pos) {
    if (!level.hasChunkAt(pos)) {
      return null;
    }
    ContainerInfo info = describe(level.getBlockEntity(pos));
    if (info != null) {
      return info;
    }
    List<ContainerInfo> entities = new ArrayList<>();
    for (Entity entity : level.getEntities((Entity) null, new AABB(pos), candidate -> true)) {
      ContainerInfo described = describe(entity);
      if (described != null) {
        entities.add(described);
      }
    }
    return entities.isEmpty() ? null : entities.get(0);
  }

  /**
   * Adds the containers of the block entities of a loaded chunk that the filter accepts.
   */
  public static void scanBlockEntities(LevelChunk chunk, Predicate<ContainerInfo> filter, List<ContainerInfo> into) {
    for (BlockEntity blockEntity : List.copyOf(chunk.getBlockEntities().values())) {
      ContainerInfo info = describe(blockEntity);
      if (info != null && filter.test(info)) {
        into.add(info);
      }
    }
  }

  /**
   * Adds the containers among the entities of a chunk that the filter accepts. Only meaningful once the entities
   * of the chunk are loaded ({@link ServerLevel#areEntitiesLoaded}).
   */
  public static void scanEntities(ServerLevel level, ChunkPos chunk, Predicate<ContainerInfo> filter, List<ContainerInfo> into) {
    AABB box = new AABB(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(), chunk.getMaxBlockX() + 1, level.getMaxBuildHeight(), chunk.getMaxBlockZ() + 1);
    for (Entity entity : level.getEntities((Entity) null, box, candidate -> true)) {
      // An entity whose box reaches into the chunk belongs to the chunk of its position.
      if (!new ChunkPos(entity.blockPosition()).equals(chunk)) {
        continue;
      }
      ContainerInfo info = describe(entity);
      if (info != null && filter.test(info)) {
        into.add(info);
      }
    }
  }

  /**
   * The containers of the chunks that are loaded right now, block entities and entities; chunks that are not loaded
   * are left out and counted in the return value of {@link Scanned#notLoaded()}. Loads nothing.
   */
  public static Scanned scanLoaded(ServerLevel level, Iterable<ChunkPos> chunks, Predicate<ContainerInfo> filter) {
    List<ContainerInfo> found = new ArrayList<>();
    int scanned = 0;
    int notLoaded = 0;
    for (ChunkPos pos : chunks) {
      LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
      if (chunk == null) {
        notLoaded++;
        continue;
      }
      scanBlockEntities(chunk, filter, found);
      scanEntities(level, pos, filter, found);
      scanned++;
    }
    return new Scanned(found, scanned, notLoaded);
  }

  public record Scanned(List<ContainerInfo> containers, int chunksScanned, int notLoaded) {
  }

  /**
   * @return the containers ordered by their distance from the point, nearest first.
   */
  public static List<ContainerInfo> sortByDistance(List<ContainerInfo> containers, Vec3 from) {
    List<ContainerInfo> sorted = new ArrayList<>(containers);
    sorted.sort(Comparator.comparingDouble((ContainerInfo info) -> info.distanceSqr(from)).thenComparing(info -> info.pos().asLong()));
    return sorted;
  }

  /**
   * @return whether the container has the loot table.
   */
  public static boolean hasTable(ContainerInfo info, ResourceKey<LootTable> table) {
    return info.table() != null && info.table().equals(table);
  }
}
