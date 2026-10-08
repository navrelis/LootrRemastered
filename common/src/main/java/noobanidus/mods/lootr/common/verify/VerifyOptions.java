package noobanidus.mods.lootr.common.verify;

import noobanidus.mods.lootr.common.api.PlatformAPI;

import java.nio.file.Path;
import java.time.Clock;

/**
 * What one verification run does.
 *
 * @param rolls           sample rolls per rolled loot table, 0 to {@link #MAX_ROLLS}; 0 switches the rolls off.
 * @param outputDirectory where the report files are written; created if missing.
 * @param clock           the source of the report's time stamp (and of its time zone).
 */
public record VerifyOptions(int rolls, Path outputDirectory, Clock clock) {
  public static final int MAX_ROLLS = 100;

  public VerifyOptions {
    rolls = Math.max(0, Math.min(MAX_ROLLS, rolls));
    if (outputDirectory == null) {
      throw new IllegalArgumentException("outputDirectory must not be null");
    }
    if (clock == null) {
      clock = Clock.systemDefaultZone();
    }
  }

  /**
   * @return options that write to `logs/lootr` in the game directory.
   */
  public static VerifyOptions of(int rolls) {
    return new VerifyOptions(rolls, defaultOutputDirectory(), Clock.systemDefaultZone());
  }

  public static Path defaultOutputDirectory() {
    Path game = PlatformAPI.getGameDirectory();
    return (game == null ? Path.of("") : game).resolve("logs").resolve("lootr");
  }
}
