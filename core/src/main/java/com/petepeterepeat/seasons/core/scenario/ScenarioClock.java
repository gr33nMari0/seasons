// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock a scenario can move forward, so reminders, countdowns, windows and rate limits come due
 * without waiting them out. Seasons gets one only on a scenario server (docs/adr/0003).
 */
public final class ScenarioClock extends Clock {
  private final Clock base;
  private final Offset offset;

  /** Shared by a clock and its copies in other zones. */
  private static final class Offset {
    private volatile Duration value = Duration.ZERO;
  }

  public ScenarioClock(Clock base) {
    this(base, new Offset());
  }

  private ScenarioClock(Clock base, Offset offset) {
    this.base = base;
    this.offset = offset;
  }

  /** Moves the clock forward by {@code by}, and returns the new time. */
  public Instant advance(Duration by) {
    if (by.isNegative()) {
      throw new IllegalArgumentException("The scenario clock only moves forward");
    }
    synchronized (offset) {
      offset.value = offset.value.plus(by);
    }
    return instant();
  }

  /** Back to real time, as on a fresh server. */
  public void reset() {
    offset.value = Duration.ZERO;
  }

  /** How far ahead of real time the clock is. */
  public Duration offset() {
    return offset.value;
  }

  @Override
  public ZoneId getZone() {
    return base.getZone();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new ScenarioClock(base.withZone(zone), offset);
  }

  @Override
  public Instant instant() {
    return base.instant().plus(offset.value);
  }
}
