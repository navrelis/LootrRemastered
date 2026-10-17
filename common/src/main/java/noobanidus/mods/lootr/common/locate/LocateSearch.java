package noobanidus.mods.lootr.common.locate;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The searches behind `/lootr locate table` and `/lootr locate structure`, as data. The commands only turn the
 * results into text.
 */
public final class LocateSearch {
  /**
   * The radius, in chunks, of `locate table` if none is given, and the largest one.
   */
  public static final int DEFAULT_TABLE_RADIUS = 8;
  public static final int MAX_TABLE_RADIUS = 32;
  /**
   * The radius, in chunks, in which `locate structure` looks for a structure: the default and the largest one, as for
   * the vanilla command.
   */
  public static final int DEFAULT_STRUCTURE_RADIUS = 100;
  public static final int MAX_STRUCTURE_RADIUS = 100;
  /**
   * How many containers `locate table` lists.
   */
  public static final int TABLE_LIMIT = 10;
  /**
   * How many chunks of a structure `locate structure` loads at most.
   */
  public static final int MAX_STRUCTURE_CHUNKS = 64;

  private LocateSearch() {
  }

  // ----- locate table -----

  /**
   * @param nearest        the nearest containers with the loot table, at most {@link #TABLE_LIMIT}, nearest first.
   * @param total          how many containers with the loot table were found.
   * @param chunksScanned  how many chunks were looked at.
   * @param chunksSkipped  how many chunks were not looked at because they are not generated (or not readable).
   * @param entitiesMissed chunks whose entities did not arrive in time.
   */
  public record TableResult(
      ResourceKey<LootTable> table,
      LootTableStatus.Result status,
      List<ContainerInfo> nearest,
      int total,
      int chunksScanned,
      int chunksSkipped,
      int entitiesMissed) {
  }

  /**
   * Looks for the containers with the loot table in the chunks around the position; chunks that are not generated are
   * left alone. The listener is called once on the server thread, possibly before this method returns.
   *
   * @param radius in chunks.
   */
  public static ChunkScan table(ServerLevel level, Vec3 origin, ResourceKey<LootTable> table, int radius, Consumer<TableResult> listener) {
    ChunkPos center = new ChunkPos(BlockPos.containing(origin));
    LootTableStatus.Result status = LootTableStatus.of(level.getServer(), table);
    return ChunkScan.start(level, ChunkScan.square(center, radius), info -> ContainerLocator.hasTable(info, table), scan -> {
      List<ContainerInfo> sorted = ContainerLocator.sortByDistance(scan.containers(), origin);
      listener.accept(new TableResult(table, status, List.copyOf(sorted.subList(0, Math.min(TABLE_LIMIT, sorted.size()))), sorted.size(), scan.chunksScanned(), scan.chunksSkipped(), scan.entitiesMissed()));
    });
  }

  // ----- locate structure -----

  /**
   * @param bounds the bounding box of the structure start; null if it could not be read.
   */
  public record StructureHit(ResourceLocation id, BlockPos pos, @Nullable BoundingBox bounds) {
  }

  /**
   * The nearest of the structures, found the way the vanilla command finds it. Structure starts of chunks that are not
   * generated are generated for it, as for the vanilla command; no other chunk is.
   *
   * @param radius in chunks.
   * @return null if there is none within the radius.
   */
  @Nullable
  public static StructureHit findStructure(ServerLevel level, HolderSet<Structure> structures, BlockPos from, int radius) {
    Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator().findNearestMapStructure(level, structures, from, radius, false);
    if (found == null) {
      return null;
    }
    BlockPos pos = found.getFirst();
    Structure structure = found.getSecond().value();
    ResourceLocation id = found.getSecond().unwrapKey().map(ResourceKey::location).orElseGet(() -> level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getKey(structure));
    return new StructureHit(id, pos, boundsOf(level, structure, pos));
  }

  @Nullable
  private static BoundingBox boundsOf(ServerLevel level, Structure structure, BlockPos pos) {
    ChunkPos chunkPos = new ChunkPos(pos);
    // The start exists (the search found it), so this loads or generates nothing beyond what the search did.
    ChunkAccess chunk = level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS, true);
    StructureStart start = chunk == null ? null : chunk.getStartForStructure(structure);
    return start != null && start.isValid() ? start.getBoundingBox() : null;
  }

  /**
   * @param container  a container inside the structure.
   * @param status     the status of its loot table.
   */
  public record StructureContainer(ContainerInfo container, LootTableStatus.Result status) {
  }

  /**
   * @param containers    every container inside the bounding box in the chunks that were looked at, nearest first.
   * @param chunksTotal   the chunks the bounding box spans.
   * @param chunksCapped  how many of them were to be looked at (at most {@link #MAX_STRUCTURE_CHUNKS}).
   * @param chunksScanned how many chunks were looked at.
   * @param chunksSkipped how many of the chunks to look at are not generated yet.
   * @param missing       containers whose loot table is missing.
   * @param empty         containers whose loot table is empty.
   */
  public record StructureResult(
      StructureHit hit,
      List<StructureContainer> containers,
      int chunksTotal,
      int chunksCapped,
      int chunksScanned,
      int chunksSkipped,
      int entitiesMissed,
      int missing,
      int empty) {
    public boolean generated() {
      return chunksScanned > 0;
    }
  }

  /**
   * Lists the containers inside the bounding box of the structure, in the chunks of the box that are already
   * generated (at most {@link #MAX_STRUCTURE_CHUNKS}, the nearest to the start first). The listener is called once
   * on the server thread, possibly before this method returns.
   *
   * @return null if the hit has no bounding box.
   */
  @Nullable
  public static ChunkScan structureContents(ServerLevel level, StructureHit hit, Vec3 origin, Consumer<StructureResult> listener) {
    BoundingBox bounds = hit.bounds();
    if (bounds == null) {
      return null;
    }
    ChunkPos startChunk = new ChunkPos(hit.pos());
    List<ChunkPos> all = new ArrayList<>();
    for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
      for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
        all.add(new ChunkPos(x, z));
      }
    }
    all.sort(Comparator.comparingLong((ChunkPos pos) -> {
      long dx = pos.x - startChunk.x;
      long dz = pos.z - startChunk.z;
      return dx * dx + dz * dz;
    }).thenComparingLong(ChunkPos::toLong));
    List<ChunkPos> chunks = all.size() > MAX_STRUCTURE_CHUNKS ? new ArrayList<>(all.subList(0, MAX_STRUCTURE_CHUNKS)) : all;
    return ChunkScan.start(level, chunks, info -> bounds.isInside(info.pos()), scan -> {
      LootTableStatus.Cache statuses = new LootTableStatus.Cache(level.getServer());
      List<StructureContainer> containers = new ArrayList<>();
      int missing = 0;
      int empty = 0;
      for (ContainerInfo info : ContainerLocator.sortByDistance(scan.containers(), origin)) {
        LootTableStatus.Result status = statuses.get(info.table());
        containers.add(new StructureContainer(info, status));
        if (status.kind() == LootTableStatus.Kind.MISSING) {
          missing++;
        } else if (status.kind() == LootTableStatus.Kind.EMPTY) {
          empty++;
        }
      }
      listener.accept(new StructureResult(hit, List.copyOf(containers), all.size(), chunks.size(), scan.chunksScanned(), scan.chunksSkipped(), scan.entitiesMissed(), missing, empty));
    });
  }
}
