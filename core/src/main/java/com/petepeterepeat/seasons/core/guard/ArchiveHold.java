// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players being archived or unarchived right now, or still logging in. Between copying a player's
 * inventory and clearing it, a held player can't click, drop, pick up, swap hands, eat, use
 * anything, build, break or take damage, so what was copied is exactly what is cleared.
 */
public final class ArchiveHold {
  private final Set<PlayerId> held = ConcurrentHashMap.newKeySet();
  private final Set<PlayerId> arriving = ConcurrentHashMap.newKeySet();
  private final java.util.Map<PlayerId, Integer> pending = new ConcurrentHashMap<>();

  /** Holds a player; false if they were already held. */
  public boolean hold(PlayerId player) {
    return held.add(player);
  }

  public void release(PlayerId player) {
    held.remove(player);
  }

  /**
   * Holds a player from the moment they join until their login catch-up and any crash recovery have
   * run, so nothing they carry can move before it is checked and archived. Separate from {@link
   * #hold}, which archiving itself takes during that time.
   */
  public void holdArriving(PlayerId player) {
    arriving.add(player);
  }

  public void releaseArriving(PlayerId player) {
    arriving.remove(player);
  }

  /**
   * Holds a player whose archive failed or found them busy until it goes through, so they are never
   * left free with what it should have taken (security review H2). Separate from {@link #hold},
   * which each attempt takes.
   *
   * @return how many times in a row this player's archive hasn't gone through
   */
  public int markPending(PlayerId player) {
    return pending.merge(player, 1, Integer::sum);
  }

  /** Lets go of a pending player: archived, or gone. */
  public void clearPending(PlayerId player) {
    pending.remove(player);
  }

  public boolean isPending(PlayerId player) {
    return pending.containsKey(player);
  }

  /**
   * Whether an archive, unarchive or other transaction holds the player right now, or their archive
   * is pending, as opposed to their login still running: what a command may not change (security
   * review M1 and H2).
   */
  public boolean inTransaction(PlayerId player) {
    return held.contains(player) || pending.containsKey(player);
  }

  public boolean isHeld(PlayerId player) {
    return held.contains(player) || arriving.contains(player) || pending.containsKey(player);
  }

  /** Everyone held, such as for refusing a launch while archiving or a login still runs. */
  public Set<PlayerId> held() {
    Set<PlayerId> all = new HashSet<>(held);
    all.addAll(arriving);
    return Set.copyOf(all);
  }
}
