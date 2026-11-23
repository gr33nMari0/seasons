// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token buckets, one per key such as a visitor's address or an account: each holds a minute's
 * allowance and refills steadily, so a burst spends it and the limit returns over the minute.
 * Buckets nobody has used for ten minutes are dropped, so memory stays bounded.
 */
final class RateLimiter {
  private static final Duration IDLE = Duration.ofMinutes(10);
  private static final int SWEEP_EVERY = 1024;

  private final Clock clock;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private int calls;

  private static final class Bucket {
    double tokens;
    Instant at;

    Bucket(double tokens, Instant at) {
      this.tokens = tokens;
      this.at = at;
    }
  }

  RateLimiter(Clock clock) {
    this.clock = clock;
  }

  /**
   * Takes one from a key's allowance.
   *
   * @return 0 if allowed; otherwise the seconds until one is free again
   */
  long take(String key, int perMinute) {
    return use(key, perMinute, true);
  }

  /** Like {@link #take} but takes nothing: whether one is free now. */
  long check(String key, int perMinute) {
    return use(key, perMinute, false);
  }

  private long use(String key, int perMinute, boolean take) {
    if (perMinute <= 0) {
      return 60;
    }
    sweep();
    Instant now = clock.instant();
    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(perMinute, now));
    synchronized (bucket) {
      double perMilli = perMinute / 60_000.0;
      long elapsed = Math.max(0, Duration.between(bucket.at, now).toMillis());
      bucket.tokens = Math.min(perMinute, bucket.tokens + elapsed * perMilli);
      bucket.at = now;
      if (bucket.tokens >= 1) {
        if (take) {
          bucket.tokens -= 1;
        }
        return 0;
      }
      return Math.max(1, (long) Math.ceil((1 - bucket.tokens) / perMilli / 1000));
    }
  }

  private void sweep() {
    if (++calls % SWEEP_EVERY != 0) {
      return;
    }
    Instant cut = clock.instant().minus(IDLE);
    for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
      Bucket b = it.next();
      synchronized (b) {
        if (b.at.isBefore(cut)) {
          it.remove();
        }
      }
    }
  }

  int size() {
    return buckets.size();
  }

  void clear() {
    buckets.clear();
  }
}
