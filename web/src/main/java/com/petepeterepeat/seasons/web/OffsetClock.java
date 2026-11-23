// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock that a scenario moves forward (docs/adr/0003), as Seasons' own scenario clock: real time
 * plus an offset that only grows. Only on a scenario server.
 */
final class OffsetClock extends Clock {
  private final Clock base;
  private final AtomicLong offsetMillis;

  OffsetClock(Clock base) {
    this(base, new AtomicLong());
  }

  private OffsetClock(Clock base, AtomicLong offsetMillis) {
    this.base = base;
    this.offsetMillis = offsetMillis;
  }

  void advance(Duration by) {
    if (!by.isNegative()) {
      offsetMillis.addAndGet(by.toMillis());
    }
  }

  void resetOffset() {
    offsetMillis.set(0);
  }

  @Override
  public ZoneId getZone() {
    return base.getZone();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new OffsetClock(base.withZone(zone), offsetMillis);
  }

  @Override
  public Instant instant() {
    return base.instant().plusMillis(offsetMillis.get());
  }
}
