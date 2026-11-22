// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * A typed point in a cell. Stored positions are absolute block positions; the yaw is the way a
 * figure faces.
 *
 * @param page for an imported archive anchor, the page of the archive view it opens at (0-based)
 * @param egg for a trophy anchor in a museum, the season whose dragon egg it shows: an egg plinth
 * @param area for an entry anchor, a box arrivals spread over (questions 279), from {@code at}
 * @param scale for a plaque anchor, the size of its text (questions 278); else the gallery's
 */
public record Anchor(
    AnchorType type,
    Position at,
    OptionalInt page,
    OptionalInt egg,
    Optional<Bounds> area,
    OptionalDouble scale) {
  public Anchor(AnchorType type, Position at, OptionalInt page, OptionalInt egg) {
    this(type, at, page, egg, Optional.empty(), OptionalDouble.empty());
  }

  public static Anchor of(AnchorType type, Position at) {
    return new Anchor(type, at, OptionalInt.empty(), OptionalInt.empty());
  }

  /** Whether the anchor sits on the block a position is in. */
  public boolean isAt(Position position) {
    return at.dimension().equals(position.dimension())
        && at.blockX() == position.blockX()
        && at.blockY() == position.blockY()
        && at.blockZ() == position.blockZ();
  }

  /** The same anchor somewhere else, its area moved with it. */
  public Anchor movedBy(int dx, int dy, int dz) {
    Position next =
        new Position(at.dimension(), at.x() + dx, at.y() + dy, at.z() + dz, at.yaw(), at.pitch());
    Optional<Bounds> moved =
        area.map(b ->
            new Bounds(
                b.dimension(),
                b.minX() + dx,
                b.minY() + dy,
                b.minZ() + dz,
                b.maxX() + dx,
                b.maxY() + dy,
                b.maxZ() + dz));
    return new Anchor(type, next, page, egg, moved, scale);
  }

  public Anchor withAt(Position next) {
    return new Anchor(type, next, page, egg, area, scale);
  }

  public Anchor withArea(Optional<Bounds> next) {
    return new Anchor(type, at, page, egg, next, scale);
  }

  public Anchor withScale(OptionalDouble next) {
    return new Anchor(type, at, page, egg, area, next);
  }
}
