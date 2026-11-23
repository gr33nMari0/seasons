// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
  private final RateLimiter limiter = new RateLimiter(clock);

  @Test
  void aMinutesAllowanceThenRetryAfter() {
    for (int i = 0; i < 5; i++) {
      assertThat(limiter.take("a", 5)).isZero();
    }
    long wait = limiter.take("a", 5);
    assertThat(wait).isBetween(1L, 12L);
    assertThat(limiter.take("b", 5)).as("another key has its own").isZero();
    clock.advance(Duration.ofSeconds(wait));
    assertThat(limiter.take("a", 5)).isZero();
  }

  @Test
  void fullAgainAfterAMinute() {
    for (int i = 0; i < 10; i++) {
      var _ = limiter.take("a", 10);
    }
    clock.advance(Duration.ofMinutes(1));
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.take("a", 10)).isZero();
    }
    assertThat(limiter.take("a", 10)).isPositive();
  }

  @Test
  void checkingTakesNothing() {
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.check("a", 1)).isZero();
    }
    assertThat(limiter.take("a", 1)).isZero();
    assertThat(limiter.check("a", 1)).isPositive();
  }

  @Test
  void idleBucketsAreDropped() {
    for (int i = 0; i < 1000; i++) {
      var _ = limiter.take("visitor-" + i, 5);
    }
    clock.advance(Duration.ofMinutes(11));
    for (int i = 0; i < 100; i++) {
      var _ = limiter.take("late-" + i, 5);
    }
    assertThat(limiter.size()).isLessThan(1100);
  }
}
