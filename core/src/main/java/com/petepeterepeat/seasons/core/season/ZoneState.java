// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What this season's zones remember, kept in {@code season.json}: the zones unlocked or locked
 * again one at a time, by command or by their own condition, and the milestones unlock conditions
 * wait for.
 *
 * @param unlocked zones switched one at a time, by id: true once unlocked, false once locked again,
 *     whatever their condition or Homecoming says
 * @param dragonKilled whether anyone has killed the ender dragon this season
 * @param reachedEnd players who have arrived in the End this season
 */
public record ZoneState(
    Map<String, Boolean> unlocked, boolean dragonKilled, Set<PlayerId> reachedEnd) {
  /** A season whose zones are as their files say. */
  public static final ZoneState NONE = new ZoneState(Map.of(), false, Set.of());

  public ZoneState {
    unlocked = Map.copyOf(unlocked);
    reachedEnd = Set.copyOf(reachedEnd);
  }

  /** One zone unlocked, or locked again. */
  public ZoneState with(String zone, boolean open) {
    Map<String, Boolean> next = new HashMap<>(unlocked);
    next.put(zone, open);
    return new ZoneState(next, dragonKilled, reachedEnd);
  }

  public ZoneState withDragonKilled() {
    return new ZoneState(unlocked, true, reachedEnd);
  }

  public ZoneState withReachedEnd(PlayerId player) {
    Set<PlayerId> next = new HashSet<>(reachedEnd);
    next.add(player);
    return new ZoneState(unlocked, dragonKilled, next);
  }
}
