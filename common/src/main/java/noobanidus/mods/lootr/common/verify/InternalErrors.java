package noobanidus.mods.lootr.common.verify;

import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.verify.VerifyResult.InternalError;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects what the verification itself failed to do. Thread-safe. One stage records at most
 * {@link #MAX_PER_STAGE} failures and counts the rest, so that a pack that went away during a data reload does not
 * produce one finding per file.
 */
final class InternalErrors {
  static final int MAX_PER_STAGE = 20;
  private static final int MAX_TEXT = 400;

  private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
  private final Queue<InternalError> errors = new ConcurrentLinkedQueue<>();

  /**
   * @param id the file or loot table that was being processed; empty if the whole stage failed.
   */
  void add(String stage, String id, Throwable failure) {
    int count = counts.computeIfAbsent(stage, ignored -> new AtomicInteger()).incrementAndGet();
    if (count == 1) {
      // The first failure of a stage goes to the log with its stack trace; the report only has the message.
      LootrAPI.LOG.warn("Loot table verification: stage '{}' failed{}; the run continues.", stage, id.isEmpty() ? "" : " for " + id, failure);
    }
    if (count <= MAX_PER_STAGE) {
      errors.add(new InternalError(stage, id, describe(failure)));
    }
  }

  /**
   * @return the recorded failures ordered by stage and id, plus one line per stage that had more than were recorded.
   */
  List<InternalError> sorted() {
    List<InternalError> result = new ArrayList<>(errors);
    new TreeMap<>(counts).forEach((stage, count) -> {
      if (count.get() > MAX_PER_STAGE) {
        result.add(new InternalError(stage, "", (count.get() - MAX_PER_STAGE) + " more failures of this stage were not recorded"));
      }
    });
    result.sort(Comparator.comparing(InternalError::stage).thenComparing(InternalError::id).thenComparing(InternalError::error));
    return result;
  }

  static String describe(Throwable failure) {
    String message = failure.getMessage();
    return oneLine(message == null || message.isBlank() ? failure.getClass().getName() : failure.getClass().getName() + ": " + message);
  }

  /**
   * @return the text on one line and of a bounded length, as it goes into a report.
   */
  static String oneLine(String text) {
    String line = text.replaceAll("\\s+", " ").trim();
    return line.length() <= MAX_TEXT ? line : line.substring(0, MAX_TEXT) + "...";
  }
}
