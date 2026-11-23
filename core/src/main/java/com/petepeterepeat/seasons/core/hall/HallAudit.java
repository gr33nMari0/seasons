// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.log.DebugArea;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Hall and zone interactions in the audit log: those refused, and those bypass holders made. The
 * same player doing the same thing in the same cell within {@link #WINDOW} is counted, not written
 * again: one entry when it starts, and one with the count once it stops, so a held mouse button
 * can't flood the log. Server thread only.
 */
public final class HallAudit {
  /** How long a repeat is counted into the entry before it. */
  public static final Duration WINDOW = Duration.ofSeconds(10);

  /** A refused interaction. */
  public static final String BLOCKED = "hall-blocked";

  /** An interaction allowed only by {@code seasons.hof.bypass}. */
  public static final String BYPASS = "hall-bypass";

  private final AuditLog audit;
  private final Clock clock;
  private final Map<Key, Run> runs = new HashMap<>();

  /**
   * @param action {@link #BLOCKED} or {@link #BYPASS}
   * @param target the cell's key, or {@code hall} outside any cell
   * @param what such as {@code break minecraft:chest}, which repeats must match
   */
  private record Key(String actor, String action, String target, String what) {}

  private static final class Run {
    private Instant last;
    private int repeats;

    private Run(Instant last) {
      this.last = last;
    }
  }

  private final DebugArea area;

  public HallAudit(AuditLog audit, Clock clock) {
    this(audit, clock, DebugArea.HALL);
  }

  /**
   * @param area the debug area each interaction is also logged under, every one, repeats included
   */
  public HallAudit(AuditLog audit, Clock clock, DebugArea area) {
    this.audit = audit;
    this.clock = clock;
    this.area = area;
  }

  /**
   * Records one interaction, unless it repeats one recorded within {@link #WINDOW}.
   *
   * @param what such as {@code break minecraft:chest}
   * @param where such as {@code 21, -60, 24}
   */
  public void record(String actor, String action, String target, String what, String where) {
    SeasonsLog.debug(area, "{} {} {}: {} at {}", actor, action, target, what, where);
    Instant now = clock.instant();
    flush(now);
    Key key = new Key(actor, action, target, what);
    Run run = runs.get(key);
    if (run != null) {
      run.last = now;
      run.repeats++;
      return;
    }
    audit.record(actor, action, target, result(action), what + " at " + where);
    runs.put(key, new Run(now));
  }

  /** Writes the count of every run that has stopped. */
  public void flush() {
    flush(clock.instant());
  }

  /** Writes the count of every run, stopped or not, as at shutdown. */
  public void flushAll() {
    flush(Instant.MAX);
  }

  private void flush(Instant now) {
    for (Iterator<Map.Entry<Key, Run>> it = runs.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<Key, Run> entry = it.next();
      Run run = entry.getValue();
      if (run.last.plus(WINDOW).isAfter(now)) {
        continue;
      }
      it.remove();
      if (run.repeats > 0) {
        Key key = entry.getKey();
        audit.record(
            key.actor(),
            key.action(),
            key.target(),
            result(key.action()),
            key.what() + ", " + run.repeats + " more times");
      }
    }
  }

  /** Allowed for a bypass action, such as {@code zone-bypass}; denied for any other. */
  private static String result(String action) {
    return action.endsWith("-bypass") ? "allowed" : "denied";
  }
}
