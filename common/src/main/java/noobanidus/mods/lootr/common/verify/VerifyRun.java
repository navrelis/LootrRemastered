package noobanidus.mods.lootr.common.verify;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.PlatformAPI;
import noobanidus.mods.lootr.common.integratedloot.IntegratedLootSnapshot;
import noobanidus.mods.lootr.common.verify.VerifyResult.AlwaysEmpty;
import noobanidus.mods.lootr.common.verify.VerifyResult.BanInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.IntegratedInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.RollError;
import noobanidus.mods.lootr.common.verify.VerifyResult.RollInfo;
import noobanidus.mods.lootr.common.verify.VerifyResult.Scanned;
import noobanidus.mods.lootr.common.verify.VerifyResult.SlowRoll;
import noobanidus.mods.lootr.common.verify.VerifyResult.Structures;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * One verification run. It has a thread of its own that does everything in order: the scan (with the help of a few
 * {@link Workers}), the sample rolls, the report files. The server thread is only used for the rolls, which need
 * the level: the run hands it one short slice at a time as an ordinary server task, which the server runs in the
 * pause after a tick. The run waits for a slice before it hands over the next one, one tick length later at the
 * earliest, so there is never more than one slice per tick and nothing queues up on a server that is behind.
 */
final class VerifyRun {
  // At most this much of the server thread per slice, and at most one slice per tick length.
  private static final long SLICE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(8);
  private static final long SLICE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
  private static final long SERVER_POLL_MILLIS = 250;

  private final MinecraftServer server;
  private final VerifyOptions options;
  private final LootVerifier.Listener listener;
  // Called on the server thread when the run ends, with its result or null, before the listener hears of it.
  private final BiConsumer<VerifyRun, VerifyResult> onEnd;
  private final Thread thread;
  private volatile boolean cancelled = false;
  private VerifyContext context;

  VerifyRun(MinecraftServer server, VerifyOptions options, LootVerifier.Listener listener, BiConsumer<VerifyRun, VerifyResult> onEnd) {
    this.server = server;
    this.options = options;
    this.listener = listener;
    this.onEnd = onEnd;
    this.thread = new Thread(this::run, "Lootr-Verify");
    this.thread.setDaemon(true);
    this.thread.setPriority(Thread.NORM_PRIORITY - 1);
  }

  /**
   * Must be called on the server thread: takes what the run works on from the server, then lets the run's thread go.
   */
  void start() {
    context = VerifyContext.capture(server, options);
    thread.start();
  }

  int loadedTables() {
    return context.tables().size();
  }

  /**
   * Abandons the run: nothing is written and the listener is not called. Safe from any thread.
   */
  void cancel() {
    cancelled = true;
    thread.interrupt();
  }

  private void run() {
    VerifyResult result = null;
    String failure = null;
    try (Workers workers = new Workers(() -> cancelled, this::progress)) {
      Origins origins = new Origins();
      InternalErrors errors = new InternalErrors();
      VerifyScan.Data data = new VerifyScan(context, workers, origins, errors).run();
      RollSampler sampler = new RollSampler(data.rollCandidates, options.rolls());
      try {
        sampleRolls(sampler, workers);
      } catch (CancellationException e) {
        throw e;
      } catch (Exception | LinkageError | StackOverflowError e) {
        errors.add("sample_rolls", "", e);
      }
      workers.checkCancelled();
      workers.stage("Writing the report");
      result = assemble(data, sampler, origins, errors);
      try {
        result = VerifyReport.write(result, options.outputDirectory());
      } catch (IOException | RuntimeException e) {
        LootrAPI.LOG.error("Loot table verification: the report could not be written to {}.", options.outputDirectory(), e);
        List<String> notes = new ArrayList<>(result.notes());
        notes.add("The report files could not be written: " + InternalErrors.describe(e));
        result = result.withFiles(null, notes);
      }
    } catch (CancellationException e) {
      LootrAPI.LOG.info("Loot table verification was cancelled.");
      return;
    } catch (Throwable t) {
      LootrAPI.LOG.error("Loot table verification failed.", t);
      failure = InternalErrors.describe(t);
    }
    finish(result, failure);
  }

  // ----- the server thread -----

  private void sampleRolls(RollSampler sampler, Workers workers) {
    if (sampler.tables() == 0) {
      return;
    }
    workers.stage("Rolling loot tables", sampler.tables());
    boolean done = false;
    while (!done) {
      long started = System.nanoTime();
      done = onServerThread(() -> sampler.runSlice(server.overworld(), SLICE_BUDGET_NANOS));
      workers.steps(sampler.finishedTables());
      workers.reportProgress();
      long wait = SLICE_INTERVAL_NANOS - (System.nanoTime() - started);
      if (!done && wait > 0) {
        try {
          TimeUnit.NANOSECONDS.sleep(wait);
        } catch (InterruptedException e) {
          throw new CancellationException();
        }
      }
    }
  }

  /**
   * Runs the action as a task of the server and waits for it.
   *
   * @throws CancellationException if the run was cancelled or the server stops before the task ran.
   */
  private <T> T onServerThread(Supplier<T> action) {
    CompletableFuture<T> future = new CompletableFuture<>();
    server.execute(() -> {
      // A server that has stopped runs the task on the calling thread instead; the level is out of bounds there.
      if (cancelled || !server.isSameThread()) {
        future.cancel(false);
        return;
      }
      try {
        future.complete(action.get());
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });
    while (true) {
      try {
        return future.get(SERVER_POLL_MILLIS, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        if (cancelled || !server.isRunning()) {
          throw new CancellationException();
        }
      } catch (InterruptedException e) {
        throw new CancellationException();
      } catch (ExecutionException e) {
        throw new IllegalStateException(e.getCause());
      }
    }
  }

  private void progress(String message) {
    server.execute(() -> {
      if (!cancelled && server.isSameThread()) {
        notifyListener(() -> listener.progress(message));
      }
    });
  }

  private void finish(@Nullable VerifyResult result, @Nullable String failure) {
    server.execute(() -> {
      if (cancelled || !server.isSameThread()) {
        return;
      }
      onEnd.accept(this, result);
      notifyListener(() -> {
        if (result != null) {
          listener.finished(result);
        } else {
          listener.failed(failure == null ? "unknown reason" : failure);
        }
      });
    });
  }

  private static void notifyListener(Runnable call) {
    try {
      call.run();
    } catch (RuntimeException e) {
      LootrAPI.LOG.warn("Loot table verification: a listener failed.", e);
    }
  }

  // ----- the result -----

  private boolean isLoaded(String table) {
    ResourceLocation id = ResourceLocation.tryParse(table);
    return id != null && (context.tables().containsKey(id) || id.equals(BuiltInLootTables.EMPTY.location()));
  }

  private VerifyResult assemble(VerifyScan.Data data, RollSampler sampler, Origins origins, InternalErrors errors) {
    List<RollError> rollErrors = new ArrayList<>();
    List<AlwaysEmpty> alwaysEmpty = new ArrayList<>();
    List<SlowRoll> slowRolls = new ArrayList<>();
    for (RollSampler.Outcome outcome : sampler.outcomes()) {
      RollSampler.Candidate candidate = outcome.candidate;
      String table = candidate.id().toString();
      if (outcome.exception != null) {
        rollErrors.add(new RollError(table, candidate.origin(), candidate.type(), outcome.exception, outcome.message));
      } else if (outcome.slowMillis >= 0) {
        slowRolls.add(new SlowRoll(table, outcome.slowMillis));
      } else if (!outcome.produced) {
        alwaysEmpty.add(new AlwaysEmpty(table, candidate.origin(), outcome.rolls, candidate.usedBy()));
      }
    }

    List<String> notes = new ArrayList<>();
    if (server.getResourceManager() != context.resources()) {
      notes.add("Data packs were reloaded while the verification ran. The report describes the data from before the reload and may be incomplete; run it again.");
    }
    if (data.encodeFallbacks > 0) {
      notes.add(data.encodeFallbacks + " loaded loot tables could not be encoded and were read from their files instead; entries that mods add to them in code are not seen.");
    }
    if (sampler.outcomes().size() < sampler.tables()) {
      notes.add("The sample rolls stopped early: " + sampler.outcomes().size() + " of " + sampler.tables() + " loot tables were rolled.");
    }

    Set<String> cannotProduce = new HashSet<>();
    data.cannotProduce.forEach(finding -> cannotProduce.add(finding.table()));
    Set<String> emptyInRolls = new HashSet<>();
    alwaysEmpty.forEach(finding -> emptyInRolls.add(finding.table()));
    Structures structures = StructureProblems.build(data.jigsawStructures, data.attributedTemplates, data.structureTemplates, data.templateContainers, table -> {
      if (!isLoaded(table)) {
        return VerifyResult.MISSING_REFERENCE;
      }
      return cannotProduce.contains(table) ? VerifyResult.CANNOT_PRODUCE : emptyInRolls.contains(table) ? VerifyResult.ALWAYS_EMPTY_IN_ROLLS : null;
    });

    IntegratedLootSnapshot snapshot = context.snapshot();
    PackOrigin mod = PlatformAPI.describeMod(LootrAPI.MODID);
    return new VerifyResult(
        context.startedAt(),
        options.clock().getZone(),
        mod == null || mod.version().isEmpty() ? "unknown" : mod.version(),
        SharedConstants.getCurrentVersion().getName(),
        Duration.between(context.startedAt(), options.clock().instant()).toMillis(),
        List.copyOf(notes),
        new Scanned(context.tables().size(), data.tableFiles, data.templates, data.worldgenFiles, data.legacyTableFiles),
        new BanInfo(context.bansActive(), context.filterAllLoot(), context.banSources()),
        new IntegratedInfo(context.integratedEnabled(), snapshot.extensionCount(), snapshot.exclusionCount(), snapshot.ruleFiles().size(), snapshot.activeRuleFiles().size(), snapshot.pools().size(), data.otherTypeContainerTables),
        new RollInfo(options.rolls(), sampler.outcomes().size(), sampler.rollsDone(), sampler.mapsSuppressed(), data.rollSkippedOtherType, data.rollSkippedNoProducing, List.copyOf(slowRolls)),
        origins.all(),
        List.copyOf(data.loadFailures),
        List.copyOf(data.skippedByCondition),
        List.copyOf(data.legacyFolders),
        List.copyOf(data.missingReferences),
        List.copyOf(data.bannedItems),
        List.copyOf(data.cannotProduce),
        List.copyOf(rollErrors),
        List.copyOf(alwaysEmpty),
        List.copyOf(data.containerTables),
        List.copyOf(data.coverage),
        List.copyOf(data.integratedLootIssues),
        structures,
        errors.sorted(),
        null);
  }
}
