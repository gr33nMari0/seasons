// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import com.petepeterepeat.seasons.core.port.BorderSquare;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The optional frontier border ({@code zones.frontierBorder}): a world border of their own for each
 * player inside a locked {@code outside} circle, drawn as the square around it. Visual only: the
 * zone does the locking.
 *
 * <p>The game treats a border as a wall a player can't walk through, so it is drawn only where it
 * can never stop anyone the zone lets pass: around a circle that blocks, never inside it, and
 * around the zone's own exemptions too. Players outside the square (in the Hall, beyond the
 * frontier), in another dimension or holding the bypass see the world's border, so nobody sees one
 * from the wrong side. Server thread only; a player is sent a border only when theirs changes.
 */
final class FrontierBorder {
  private final Players players;

  /** The border each player has been sent, while it isn't the world's. */
  private final Map<PlayerId, BorderSquare> shown = new HashMap<>();

  FrontierBorder(Players players) {
    this.players = players;
  }

  /** Sends a player the border they should see now, if it isn't the one they have. */
  void update(PlayerId player, Position at, ZoneMap zones, boolean enabled, boolean bypasses) {
    Optional<BorderSquare> wanted =
        enabled && !bypasses
            ? square(zones, at.dimension()).filter(s -> s.holds(at))
            : Optional.empty();
    BorderSquare had = shown.get(player);
    if (wanted.isPresent() ? wanted.get().equals(had) : had == null) {
      return;
    }
    if (wanted.isPresent()) {
      shown.put(player, wanted.get());
    } else {
      shown.remove(player);
    }
    players.showBorder(player, wanted);
  }

  /**
   * Forgets the border a player was sent, as when they change dimension: the game sends their
   * client the new world's border then, whatever Seasons sent before.
   */
  void forget(PlayerId player) {
    shown.remove(player);
  }

  /** Forgets players who have left: their client has the world's border when they come back. */
  void retain(Set<PlayerId> online) {
    shown.keySet().retainAll(online);
  }

  /** Puts back the world's border for everyone who has another, as when Seasons stops. */
  void clear() {
    for (PlayerId player : shown.keySet()) {
      players.showBorder(player, Optional.empty());
    }
    shown.clear();
  }

  /**
   * The border for a dimension: the smallest square around a blocking outside circle there and its
   * exemptions. With several, the smallest holds every spot inside all of them.
   */
  static Optional<BorderSquare> square(ZoneMap zones, String dimension) {
    return zones.zones().stream()
        .filter(live -> live.now() == ZoneMode.BLOCK && live.zone().dimension().equals(dimension))
        .filter(live -> live.zone().shape() instanceof ZoneShape.OutsideCircle)
        .map(live -> square(live.zone()))
        .min(Comparator.comparingDouble(BorderSquare::size));
  }

  private static BorderSquare square(Zone zone) {
    ZoneShape.OutsideCircle circle = (ZoneShape.OutsideCircle) zone.shape();
    double minX = circle.centerX() - circle.radius();
    double maxX = circle.centerX() + circle.radius();
    double minZ = circle.centerZ() - circle.radius();
    double maxZ = circle.centerZ() + circle.radius();
    for (ZoneShape.Box box : zone.exempt()) {
      minX = Math.min(minX, box.minX());
      maxX = Math.max(maxX, box.maxX() + 1);
      minZ = Math.min(minZ, box.minZ());
      maxZ = Math.max(maxZ, box.maxZ() + 1);
    }
    return new BorderSquare(
        (minX + maxX) / 2, (minZ + maxZ) / 2, Math.max(maxX - minX, maxZ - minZ));
  }
}
