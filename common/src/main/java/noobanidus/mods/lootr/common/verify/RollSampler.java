package noobanidus.mods.lootr.common.verify;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLoot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Rolls loot tables a few times each to see whether they throw or stay empty. Works in slices: every call of
 * {@link #runSlice} does as many rolls as fit into the given time and remembers where it stopped.
 * <br />
 * All of it runs on the server thread, slice after slice; another thread may read the outcomes once a slice has
 * reported that everything is done.
 * <br />
 * A roll goes through the `Consumer` overload of `getRandomItems`, which integrated loot never extends, and with
 * explorer maps switched off, so that no roll searches the world for a structure. Banned items are filtered exactly
 * as they are for players. The seeds are derived from the table id: the same data gives the same outcome.
 */
final class RollSampler {
  // A single roll this slow (a function searching the world, for example) ends the sampling of its table.
  private static final long SLOW_ROLL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

  /**
   * @param usedBy the structure containers that use the table.
   */
  record Candidate(ResourceLocation id, LootTable table, String type, String origin, List<String> usedBy) {
  }

  static final class Outcome {
    final Candidate candidate;
    int rolls;
    boolean produced;
    // The exception of the roll that failed; null if none did.
    String exception;
    String message = "";
    // How long the roll took that was too slow; -1 if none was.
    long slowMillis = -1;

    private Outcome(Candidate candidate) {
      this.candidate = candidate;
    }
  }

  private final List<Candidate> candidates;
  private final int rollsPerTable;
  private final List<Outcome> outcomes = new ArrayList<>();
  private Outcome current;
  private volatile int finished;
  private int rollsDone;
  private int mapsSuppressed;

  RollSampler(List<Candidate> candidates, int rollsPerTable) {
    this.candidates = List.copyOf(candidates);
    this.rollsPerTable = rollsPerTable;
  }

  int tables() {
    return candidates.size();
  }

  /**
   * @return how many tables are finished; safe to read from any thread.
   */
  int finishedTables() {
    return finished;
  }

  List<Outcome> outcomes() {
    return outcomes;
  }

  int rollsDone() {
    return rollsDone;
  }

  int mapsSuppressed() {
    return mapsSuppressed;
  }

  /**
   * Must be called on the server thread. Always completes the roll it started, so a slice can take longer than
   * the budget by one roll.
   *
   * @return true if every table has been rolled.
   */
  boolean runSlice(ServerLevel level, long budgetNanos) {
    long deadline = System.nanoTime() + budgetNanos;
    LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(level.getSharedSpawnPos())).create(LootContextParamSets.CHEST);
    while (outcomes.size() < candidates.size()) {
      if (current == null) {
        current = new Outcome(candidates.get(outcomes.size()));
      }
      long before = System.nanoTime();
      roll(current, params);
      long after = System.nanoTime();
      if (current.exception == null && current.rolls < rollsPerTable && after - before > SLOW_ROLL_NANOS) {
        current.slowMillis = TimeUnit.NANOSECONDS.toMillis(after - before);
      }
      if (current.exception != null || current.slowMillis >= 0 || current.rolls >= rollsPerTable) {
        outcomes.add(current);
        current = null;
        finished = outcomes.size();
      }
      if (after >= deadline) {
        break;
      }
    }
    return outcomes.size() >= candidates.size();
  }

  private void roll(Outcome outcome, LootParams params) {
    Candidate candidate = outcome.candidate;
    // A seed of 0 would make the game roll with the level's shared random.
    long seed = candidate.id().toString().hashCode() * 31L + outcome.rolls + 1;
    long usedSeed = seed == 0L ? 1L : seed;
    try {
      mapsSuppressed += IntegratedLoot.withoutMapCreation(() -> candidate.table().getRandomItems(params, usedSeed, stack -> {
        if (!stack.isEmpty()) {
          outcome.produced = true;
        }
      }));
      outcome.rolls++;
      rollsDone++;
    } catch (Exception | LinkageError | StackOverflowError e) {
      outcome.exception = e.getClass().getName();
      outcome.message = e.getMessage() == null ? "" : InternalErrors.oneLine(e.getMessage());
    }
  }
}
