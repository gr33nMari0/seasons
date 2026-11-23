// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class LogThrottleTest {
  private final Ticking clock = new Ticking();
  private final LogThrottle throttle = new LogThrottle(clock, Duration.ofMinutes(5));

  @Test
  void theFirstOfAKindIsWrittenAndRepeatsWithinTheWindowAreCounted() {
    assertThat(throttle.admit("Pat")).isEqualTo(OptionalInt.of(0));
    clock.now = clock.now.plusSeconds(10);
    assertThat(throttle.admit("Pat")).isEmpty();
    assertThat(throttle.admit("Pat")).isEmpty();
    // Another kind has its own window.
    assertThat(throttle.admit("Lou")).isEqualTo(OptionalInt.of(0));

    clock.now = clock.now.plus(Duration.ofMinutes(5));
    assertThat(throttle.admit("Pat")).isEqualTo(OptionalInt.of(2));
    assertThat(throttle.admit("Pat")).isEmpty();
  }

  @Test
  void aWrittenLineSaysHowManyWereLeftOut() {
    assertThat(LogThrottle.repeats(0)).isEmpty();
    assertThat(LogThrottle.repeats(37)).isEqualTo(" (37 more like it since the last one logged)");
  }

  @Test
  void debugLinesAreOnForTheListedAreasOnly() {
    try {
      SeasonsLog.debugging(java.util.Set.of(DebugArea.HALL));
      assertThat(SeasonsLog.debugging(DebugArea.HALL)).isTrue();
      assertThat(SeasonsLog.debugging(DebugArea.ZONES)).isFalse();
    } finally {
      SeasonsLog.debugging(java.util.Set.of());
    }
    assertThat(SeasonsLog.debugging(DebugArea.HALL)).isFalse();
  }

  private static final class Ticking extends Clock {
    Instant now = Instant.parse("2026-11-23T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
