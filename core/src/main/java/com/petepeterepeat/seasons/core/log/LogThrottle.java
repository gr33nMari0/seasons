// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.log;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Keeps a warning that can repeat from flooding the log: the first of a kind is written, the
 * repeats within {@code window} are only counted, and the next one written after the window says
 * how many were left out. Kinds are whatever keys the caller picks, such as a player's name or a
 * webhook. Thread-safe.
 */
public final class LogThrottle {
  private final Clock clock;
  private final Duration window;
  private final Map<String, Entry> kinds = new HashMap<>();

  private static final class Entry {
    Instant written;
    int skipped;

    Entry(Instant written) {
      this.written = written;
    }
  }

  public LogThrottle(Clock clock, Duration window) {
    this.clock = clock;
    this.window = window;
  }

  /**
   * Whether to write this occurrence of {@code kind}: empty to leave it out, or how many of the
   * same kind were left out since the last one written.
   */
  public synchronized OptionalInt admit(String kind) {
    Instant now = clock.instant();
    Entry entry = kinds.get(kind);
    if (entry == null) {
      kinds.put(kind, new Entry(now));
      return OptionalInt.of(0);
    }
    if (now.isBefore(entry.written.plus(window))) {
      entry.skipped++;
      return OptionalInt.empty();
    }
    int skipped = entry.skipped;
    entry.written = now;
    entry.skipped = 0;
    return OptionalInt.of(skipped);
  }

  /**
   * What to add to a written line for the repeats left out before it: nothing, or for example " (37
   * more like it since the last one logged)".
   */
  public static String repeats(int skipped) {
    return skipped == 0 ? "" : " (" + skipped + " more like it since the last one logged)";
  }
}
