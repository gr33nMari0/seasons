// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players being archived or unarchived right now. Between copying a player's inventory and clearing
 * it, a held player can't click, drop, pick up, swap hands, eat, use anything, build, break or take
 * damage, so what was copied is exactly what is cleared.
 */
public final class ArchiveHold {
  private final Set<PlayerId> held = ConcurrentHashMap.newKeySet();

  /** Holds a player; false if they were already held. */
  public boolean hold(PlayerId player) {
    return held.add(player);
  }

  public void release(PlayerId player) {
    held.remove(player);
  }

  public boolean isHeld(PlayerId player) {
    return held.contains(player);
  }

  /** Everyone held, such as for refusing a launch while archiving still runs. */
  public Set<PlayerId> held() {
    return Set.copyOf(held);
  }
}
