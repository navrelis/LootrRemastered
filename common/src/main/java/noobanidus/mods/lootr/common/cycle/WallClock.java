package noobanidus.mods.lootr.common.cycle;

import org.jetbrains.annotations.ApiStatus;

import java.util.function.LongSupplier;

/**
 * The real-time clock of the personal cycle, in epoch milliseconds. All cycle code reads the time through here, so
 * that tests can move time without waiting.
 */
public final class WallClock {
  private static final LongSupplier SYSTEM = System::currentTimeMillis;
  private static volatile LongSupplier source = SYSTEM;

  private WallClock() {
  }

  public static long now() {
    return source.getAsLong();
  }

  /**
   * Replaces the clock; null restores the system clock. For tests only.
   */
  @ApiStatus.Internal
  public static void setForTesting(LongSupplier clock) {
    source = clock == null ? SYSTEM : clock;
  }
}
