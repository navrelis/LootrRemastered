package noobanidus.mods.lootr.common.locate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import noobanidus.mods.lootr.common.api.LootrAPI;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Looks for containers in a list of chunks without ever generating one. Chunks that are loaded are read as they
 * are. A chunk that is not loaded is only loaded if the chunk storage has it as fully generated; the storage is asked
 * ahead on its own thread, so the server thread never waits for the disk to decide. The work is spread over the ticks:
 * one slice of a few milliseconds per tick, the loads of one slice are bounded by that time.
 * <br />
 * The entities of a freshly loaded chunk arrive a few ticks later, so the chunks that this scan loaded are held with a
 * ticket of its own until their entities are in; the scan finishes then, or after a few seconds.
 * <br />
 * Everything runs on the server thread. {@link #start} does the first slice itself, so a scan of chunks that are all
 * loaded is finished when it returns.
 */
public final class ChunkScan {
  private static final long SLICE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(8);
  private static final long SLICE_INTERVAL_MILLIS = 50;
  // How many chunks ahead the storage is asked about.
  private static final int LOOKAHEAD = 64;
  // After this many slices the entities that have not arrived are given up on.
  private static final int ENTITY_WAIT_SLICES = 100;
  private static final String FULL_STATUS = "minecraft:full";

  // The ticket that keeps a chunk of this scan loaded; it also runs out by itself.
  private static final TicketType<ChunkPos> TICKET = TicketType.create("lootr_scan", Comparator.comparingLong(ChunkPos::toLong), ENTITY_WAIT_SLICES + 20);

  /**
   * @param chunksScanned chunks that were read.
   * @param chunksSkipped chunks that were left alone: not generated (or not fully), or not readable.
   * @param entitiesMissed chunks whose entities did not arrive in time; minecarts in them are not in the result.
   */
  public record Result(List<ContainerInfo> containers, int chunksScanned, int chunksSkipped, int entitiesMissed) {
  }

  public interface Listener {
    /**
     * Called on the server thread, once.
     */
    void finished(Result result);
  }

  private enum Probe {
    FULL,
    NOT_FULL,
    FAILED
  }

  private final MinecraftServer server;
  private final ServerLevel level;
  private final List<ChunkPos> chunks;
  private final Predicate<ContainerInfo> filter;
  private final Listener listener;
  private final Map<ChunkPos, CompletableFuture<Probe>> reads = new HashMap<>();
  private final Set<ChunkPos> ticketed = new HashSet<>();
  private final List<ChunkPos> pendingEntities = new ArrayList<>();
  private final List<ContainerInfo> found = new ArrayList<>();
  private int next;
  private int scanned;
  private int skipped;
  private int slicesWaitingForEntities;
  private boolean finished;

  private ChunkScan(ServerLevel level, List<ChunkPos> chunks, Predicate<ContainerInfo> filter, Listener listener) {
    this.server = level.getServer();
    this.level = level;
    this.chunks = List.copyOf(chunks);
    this.filter = filter;
    this.listener = listener;
  }

  /**
   * Must be called on the server thread.
   *
   * @param chunks the chunks to look at, in the order they are looked at.
   * @param filter says which containers are results.
   */
  public static ChunkScan start(ServerLevel level, List<ChunkPos> chunks, Predicate<ContainerInfo> filter, Listener listener) {
    ChunkScan scan = new ChunkScan(level, chunks, filter, listener);
    scan.slice();
    return scan;
  }

  /**
   * @return the chunks of the square around the centre with the radius (in chunks), the nearest first.
   */
  public static List<ChunkPos> square(ChunkPos center, int radius) {
    List<ChunkPos> result = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
    for (int x = center.x - radius; x <= center.x + radius; x++) {
      for (int z = center.z - radius; z <= center.z + radius; z++) {
        result.add(new ChunkPos(x, z));
      }
    }
    result.sort(Comparator.comparingLong((ChunkPos pos) -> distanceSqr(center, pos)).thenComparingLong(ChunkPos::toLong));
    return result;
  }

  private static long distanceSqr(ChunkPos a, ChunkPos b) {
    long dx = a.x - b.x;
    long dz = a.z - b.z;
    return dx * dx + dz * dz;
  }

  public boolean isFinished() {
    return finished;
  }

  // ----- slices -----

  private void slice() {
    if (finished) {
      return;
    }
    if (!server.isSameThread() || !server.isRunning()) {
      // The server is stopping: the level is out of bounds, and nobody will read an answer.
      finished = true;
      return;
    }
    try {
      long deadline = System.nanoTime() + SLICE_BUDGET_NANOS;
      askAhead();
      while (next < chunks.size()) {
        if (!process(chunks.get(next))) {
          break;
        }
        next++;
        if (System.nanoTime() >= deadline) {
          break;
        }
      }
      collectEntities();
    } catch (RuntimeException | LinkageError e) {
      LootrAPI.LOG.error("Scanning chunks for containers failed.", e);
      // Whatever is left cannot be trusted; end with what was found.
      skipped += chunks.size() - next;
      next = chunks.size();
      pendingEntities.clear();
    }
    if (next >= chunks.size()) {
      if (!pendingEntities.isEmpty()) {
        slicesWaitingForEntities++;
      }
      if (pendingEntities.isEmpty() || slicesWaitingForEntities >= ENTITY_WAIT_SLICES) {
        finish();
        return;
      }
    }
    CompletableFuture.delayedExecutor(SLICE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS).execute(() -> server.execute(this::slice));
  }

  private void askAhead() {
    ServerChunkCache cache = level.getChunkSource();
    int end = Math.min(chunks.size(), next + LOOKAHEAD);
    for (int i = next; i < end; i++) {
      ChunkPos pos = chunks.get(i);
      if (!reads.containsKey(pos) && cache.getChunkNow(pos.x, pos.z) == null) {
        reads.put(pos, read(pos));
      }
    }
  }

  private CompletableFuture<Probe> read(ChunkPos pos) {
    try {
      return level.getChunkSource().chunkMap.read(pos).handle((tag, error) -> error != null ? Probe.FAILED : probe(tag));
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(Probe.FAILED);
    }
  }

  private static Probe probe(Optional<CompoundTag> tag) {
    if (tag.isEmpty() || !tag.get().contains("Status")) {
      return Probe.NOT_FULL;
    }
    ResourceLocation status = ResourceLocation.tryParse(tag.get().getString("Status"));
    return status != null && status.toString().equals(FULL_STATUS) ? Probe.FULL : Probe.NOT_FULL;
  }

  /**
   * @return false if the chunk has to wait for the storage.
   */
  private boolean process(ChunkPos pos) {
    ServerChunkCache cache = level.getChunkSource();
    LevelChunk loaded = cache.getChunkNow(pos.x, pos.z);
    if (loaded != null) {
      reads.remove(pos);
      scan(loaded);
      return true;
    }
    CompletableFuture<Probe> read = reads.computeIfAbsent(pos, this::read);
    if (!read.isDone()) {
      return false;
    }
    reads.remove(pos);
    if (read.getNow(Probe.FAILED) != Probe.FULL) {
      skipped++;
      return true;
    }
    cache.addRegionTicket(TICKET, pos, 0, pos);
    ticketed.add(pos);
    ChunkAccess chunk = cache.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
    if (chunk instanceof LevelChunk levelChunk) {
      scan(levelChunk);
    } else {
      skipped++;
    }
    return true;
  }

  private void scan(LevelChunk chunk) {
    ContainerLocator.scanBlockEntities(chunk, filter, found);
    scanned++;
    ChunkPos pos = chunk.getPos();
    if (level.areEntitiesLoaded(pos.toLong())) {
      ContainerLocator.scanEntities(level, pos, filter, found);
    } else {
      pendingEntities.add(pos);
    }
  }

  private void collectEntities() {
    pendingEntities.removeIf(pos -> {
      if (!level.areEntitiesLoaded(pos.toLong())) {
        return false;
      }
      ContainerLocator.scanEntities(level, pos, filter, found);
      return true;
    });
  }

  private void finish() {
    finished = true;
    ServerChunkCache cache = level.getChunkSource();
    for (ChunkPos pos : ticketed) {
      cache.removeRegionTicket(TICKET, pos, 0, pos);
    }
    ticketed.clear();
    Result result = new Result(List.copyOf(found), scanned, skipped, pendingEntities.size());
    try {
      listener.finished(result);
    } catch (RuntimeException e) {
      LootrAPI.LOG.warn("A listener of a chunk scan failed.", e);
    }
  }
}
