// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;

/**
 * An admin's wand selection (questions 229): up to two corners in one dimension. Corner 1 starts a
 * new selection; corner 2 completes it.
 */
public record Selection(String dimension, Optional<Position> first, Optional<Position> second) {
  /** A new selection with only corner 1. */
  public static Selection startingAt(Position corner) {
    return new Selection(corner.dimension(), Optional.of(corner), Optional.empty());
  }

  /**
   * Sets corner 2. A corner in another dimension than corner 1 starts a new selection holding only
   * itself.
   */
  public Selection withSecond(Position corner) {
    if (!corner.dimension().equals(dimension)) {
      return new Selection(corner.dimension(), Optional.empty(), Optional.of(corner));
    }
    return new Selection(dimension, first, Optional.of(corner));
  }

  /** The box between both corners, once there are two. */
  public Optional<Bounds> bounds() {
    if (first.isEmpty() || second.isEmpty()) {
      return Optional.empty();
    }
    Position a = first.get();
    Position b = second.get();
    return Optional.of(
        Bounds.between(
            dimension, a.blockX(), a.blockY(), a.blockZ(), b.blockX(), b.blockY(), b.blockZ()));
  }
}
