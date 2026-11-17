// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The decorating window (see Season lifecycle in the spec), kept in {@code season.json} so it
 * survives a restart.
 *
 * @param openedAt when the window last opened this season; once set, it never opens again by itself
 * @param open whether owners can decorate their cells now
 * @param lockAt for owners archived at the end, when their cell locks for good
 *     (decorating.afterArchive after their archive)
 */
public record Decorating(Optional<Instant> openedAt, boolean open, Map<PlayerId, Instant> lockAt) {
  /** A season that hasn't opened its window. */
  public static final Decorating NONE = new Decorating(Optional.empty(), false, Map.of());

  public Decorating {
    lockAt = Map.copyOf(lockAt);
  }

  public Decorating opened(Instant now) {
    return new Decorating(Optional.of(now), true, lockAt);
  }

  /** The window closes and every cell locks. */
  public Decorating closed() {
    return new Decorating(openedAt, false, Map.of());
  }

  /** The window closes at the end, before anyone is archived. */
  public Decorating lockedAtEnd() {
    return new Decorating(openedAt, false, lockAt);
  }

  public Decorating withLockAt(PlayerId player, Instant at) {
    Map<PlayerId, Instant> next = new HashMap<>(lockAt);
    next.put(player, at);
    return new Decorating(openedAt, open, next);
  }

  public Decorating withoutLock(PlayerId player) {
    Map<PlayerId, Instant> next = new HashMap<>(lockAt);
    next.remove(player);
    return new Decorating(openedAt, open, next);
  }

  /** Whether an owner archived at the end may still arrange their cell. */
  public boolean afterArchive(PlayerId player, Instant now) {
    Instant until = lockAt.get(player);
    return until != null && now.isBefore(until);
  }
}
