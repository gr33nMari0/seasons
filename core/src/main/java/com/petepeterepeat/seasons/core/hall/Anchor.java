// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.OptionalInt;

/**
 * A typed point in a cell. Stored positions are absolute block positions; the yaw is the way a
 * figure faces.
 *
 * @param page for an imported archive anchor, the page of the archive view it opens at (0-based)
 * @param egg for a trophy anchor in a museum, the season whose dragon egg it shows: an egg plinth
 */
public record Anchor(AnchorType type, Position at, OptionalInt page, OptionalInt egg) {
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
}
