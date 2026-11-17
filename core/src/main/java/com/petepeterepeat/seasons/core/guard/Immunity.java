// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Damage immunity after a Seasons teleport ({@code hof.arrivalImmunity}, 5 s by default). */
public final class Immunity {
  private final Clock clock;
  private final Map<PlayerId, Instant> until = new ConcurrentHashMap<>();

  public Immunity(Clock clock) {
    this.clock = clock;
  }

  public void grant(PlayerId player, Duration length) {
    until.put(player, clock.instant().plus(length));
  }

  public boolean isImmune(PlayerId player) {
    Instant end = until.get(player);
    if (end == null) {
      return false;
    }
    if (clock.instant().isBefore(end)) {
      return true;
    }
    until.remove(player, end);
    return false;
  }
}
