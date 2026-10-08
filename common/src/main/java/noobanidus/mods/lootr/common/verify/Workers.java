package noobanidus.mods.lootr.common.verify;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The few background threads a verification run reads files with, and the progress of what they are doing.
 * {@link #forEach} is called by the run's own thread, which waits there and reports the progress now and then.
 */
final class Workers implements AutoCloseable {
  private static final int MAX_THREADS = 4;
  private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
  private static final long POLL_MILLIS = 200;

  private final int threads;
  private final ExecutorService pool;
  private final BooleanSupplier cancelled;
  private final Consumer<String> progressSink;

  private final AtomicInteger done = new AtomicInteger();
  private volatile String stage = "Starting";
  private volatile int total = 0;
  // Only touched by the run's own thread.
  private long lastProgress = System.nanoTime();

  /**
   * @param progressSink receives a line about the current stage at most once per interval.
   */
  Workers(BooleanSupplier cancelled, Consumer<String> progressSink) {
    this.cancelled = cancelled;
    this.progressSink = progressSink;
    // Half the cores at most: the server and its chunk workers keep running.
    this.threads = Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors() / 2));
    AtomicInteger number = new AtomicInteger();
    this.pool = Executors.newFixedThreadPool(threads, task -> {
      Thread thread = new Thread(task, "Lootr-Verify-Worker-" + number.incrementAndGet());
      thread.setDaemon(true);
      thread.setPriority(Thread.NORM_PRIORITY - 1);
      return thread;
    });
  }

  /**
   * Names what the run is doing now, for stages that are not a {@link #forEach}.
   */
  void stage(String name) {
    stage = name;
    total = 0;
    done.set(0);
  }

  /**
   * Names a stage with a known number of steps; whoever works on it counts them with {@link #steps(int)}.
   */
  void stage(String name, int steps) {
    stage(name);
    total = steps;
  }

  /**
   * Sets how many steps of the current stage are done.
   */
  void steps(int finished) {
    done.set(finished);
  }

  /**
   * Sends a progress line if the last one is old enough. To be called by the run's own thread only.
   */
  void reportProgress() {
    long now = System.nanoTime();
    if (now - lastProgress < PROGRESS_INTERVAL_NANOS) {
      return;
    }
    lastProgress = now;
    int steps = total;
    progressSink.accept(steps > 0 ? stage + " (" + Math.min(done.get(), steps) + " of " + steps + ")" : stage);
  }

  /**
   * @throws CancellationException if the run was cancelled.
   */
  void checkCancelled() {
    if (cancelled.getAsBoolean()) {
      throw new CancellationException();
    }
  }

  /**
   * Runs the action for every item on the background threads and returns when all are done. The action must be
   * thread-safe and should handle its own failures; one that escapes stops the loop and is thrown here.
   *
   * @throws CancellationException if the run was cancelled.
   */
  <T> void forEach(String name, Collection<T> items, Consumer<T> action) {
    List<T> list = List.copyOf(items);
    stage(name, list.size());
    AtomicInteger next = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      pool.execute(() -> {
        try {
          int index;
          while (!cancelled.getAsBoolean() && failure.get() == null && (index = next.getAndIncrement()) < list.size()) {
            action.accept(list.get(index));
            done.incrementAndGet();
          }
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        } finally {
          latch.countDown();
        }
      });
    }
    boolean finished = false;
    while (!finished) {
      try {
        finished = latch.await(POLL_MILLIS, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        // Only a cancelled run is interrupted; its workers stop after the item they are at.
        continue;
      }
      if (!finished) {
        reportProgress();
      }
    }
    checkCancelled();
    Throwable failed = failure.get();
    if (failed instanceof Error error) {
      throw error;
    }
    if (failed != null) {
      throw new IllegalStateException(failed);
    }
  }

  @Override
  public void close() {
    pool.shutdownNow();
  }
}
