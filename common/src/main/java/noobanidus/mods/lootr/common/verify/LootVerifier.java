package noobanidus.mods.lootr.common.verify;

import net.minecraft.server.MinecraftServer;
import noobanidus.mods.lootr.common.api.LootrAPI;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loot table verification: checks the loot tables, structure templates and worldgen files of all data packs and
 * the integrated loot data against each other and writes a report (`/lootr verify`, or the system property
 * {@link #VERIFY_PROPERTY} at start-up). What is checked is described on {@link VerifyResult}.
 * <br />
 * At most one run is active at a time. A run reads files on background threads and rolls loot tables on the
 * server thread in short slices, see {@link VerifyRun}; it changes nothing in the game.
 */
public final class LootVerifier {
  /**
   * `true` runs a verification once the server has started; `exit` also stops a dedicated server afterwards.
   */
  public static final String VERIFY_PROPERTY = "lootr.verify";
  /**
   * The number of sample rolls per loot table for the run at start-up.
   */
  public static final String ROLLS_PROPERTY = "lootr.verify.rolls";

  private static final AtomicReference<VerifyRun> CURRENT = new AtomicReference<>();
  @Nullable
  private static volatile VerifyResult lastResult = null;

  private LootVerifier() {
  }

  /**
   * Hears about a run. Every method is called on the server thread.
   */
  public interface Listener {
    /**
     * Called every few seconds while the run works, with a line that says what it is doing.
     */
    default void progress(String message) {
    }

    void finished(VerifyResult result);

    /**
     * Called instead of {@link #finished} if the run broke off; the cause is in the log.
     */
    void failed(String reason);
  }

  /**
   * Starts a run. Must be called on the server thread; returns at once.
   *
   * @return false if a run is active already; the listener is not used then.
   */
  public static boolean start(MinecraftServer server, VerifyOptions options, Listener listener) {
    if (!server.isSameThread()) {
      throw new IllegalStateException("A loot table verification must be started on the server thread");
    }
    VerifyRun run = new VerifyRun(server, options, listener, LootVerifier::ended);
    if (!CURRENT.compareAndSet(null, run)) {
      return false;
    }
    try {
      run.start();
    } catch (RuntimeException | Error e) {
      CURRENT.compareAndSet(run, null);
      throw e;
    }
    LootrAPI.LOG.info("Loot table verification started: {} loaded loot tables, {} sample rolls per table.", run.loadedTables(), options.rolls());
    return true;
  }

  private static void ended(VerifyRun run, @Nullable VerifyResult result) {
    if (result != null) {
      lastResult = result;
    }
    CURRENT.compareAndSet(run, null);
  }

  public static boolean isRunning() {
    return CURRENT.get() != null;
  }

  /**
   * @return the result of the last run that finished since the server started.
   */
  public static Optional<VerifyResult> lastResult() {
    return Optional.ofNullable(lastResult);
  }

  // ----- server lifecycle -----

  /**
   * @param run        whether a verification runs once the server has started.
   * @param stopServer whether the server is stopped when that run has ended.
   * @param rolls      the sample rolls per loot table of that run.
   * @param warnings   what is wrong with the property values; they are ignored where they are wrong.
   */
  public record StartupPlan(boolean run, boolean stopServer, int rolls, List<String> warnings) {
    public StartupPlan {
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * Decides what happens at server start. Pure.
   *
   * @param verify          the value of {@link #VERIFY_PROPERTY}; null if it is not set.
   * @param rolls           the value of {@link #ROLLS_PROPERTY}; null if it is not set.
   * @param dedicatedServer whether the server is a dedicated one. Only a dedicated server is ever stopped.
   */
  public static StartupPlan startupPlan(@Nullable String verify, @Nullable String rolls, boolean dedicatedServer) {
    List<String> warnings = new ArrayList<>();
    String mode = verify == null ? "" : verify.trim().toLowerCase(Locale.ROOT);
    boolean exit = mode.equals("exit");
    if (!exit && !mode.equals("true")) {
      if (!mode.isEmpty() && !mode.equals("false")) {
        warnings.add("Ignoring -D" + VERIFY_PROPERTY + "=" + verify + ": expected 'true' or 'exit'.");
      }
      return new StartupPlan(false, false, 0, warnings);
    }
    int count = 0;
    if (rolls != null && !rolls.isBlank()) {
      try {
        count = Integer.parseInt(rolls.trim());
        if (count < 0 || count > VerifyOptions.MAX_ROLLS) {
          int limited = Math.max(0, Math.min(VerifyOptions.MAX_ROLLS, count));
          warnings.add("-D" + ROLLS_PROPERTY + "=" + rolls + " is outside 0-" + VerifyOptions.MAX_ROLLS + ": using " + limited + ".");
          count = limited;
        }
      } catch (NumberFormatException e) {
        warnings.add("Ignoring -D" + ROLLS_PROPERTY + "=" + rolls + ": not a whole number. No loot table is rolled.");
      }
    }
    return new StartupPlan(true, exit && dedicatedServer, count, warnings);
  }

  /**
   * Called by the platform on the server thread once the server has started.
   */
  public static void onServerStarted(MinecraftServer server) {
    StartupPlan plan = startupPlan(System.getProperty(VERIFY_PROPERTY), System.getProperty(ROLLS_PROPERTY), server.isDedicatedServer());
    plan.warnings().forEach(warning -> LootrAPI.LOG.warn("Loot table verification: {}", warning));
    if (!plan.run()) {
      return;
    }
    start(server, VerifyOptions.of(plan.rolls()), new Listener() {
      @Override
      public void progress(String message) {
        LootrAPI.LOG.info("Loot table verification: {}", message);
      }

      @Override
      public void finished(VerifyResult result) {
        VerifyReport.summary(result).forEach(LootrAPI.LOG::info);
        stop();
      }

      @Override
      public void failed(String reason) {
        LootrAPI.LOG.error("Loot table verification failed: {}", reason);
        stop();
      }

      private void stop() {
        if (plan.stopServer()) {
          LootrAPI.LOG.info("Stopping the server: -D{}=exit.", VERIFY_PROPERTY);
          server.halt(false);
        }
      }
    });
  }

  /**
   * Called by the platform on the server thread when the server begins to stop: a run that is still active is
   * abandoned.
   */
  public static void onServerStopping(MinecraftServer server) {
    VerifyRun run = CURRENT.getAndSet(null);
    if (run != null) {
      run.cancel();
    }
    lastResult = null;
  }
}
