// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;

/**
 * A player's furthest block placed in the overworld this season, measured horizontally from the
 * season spawn (for the furthest-builder award) and from 0,0 (which guides where the next spawn
 * goes). Blocks in the Hall don't count.
 */
public record FurthestBlock(Optional<Best> fromSpawn, Optional<Best> fromOrigin) {
  public static final FurthestBlock NONE = new FurthestBlock(Optional.empty(), Optional.empty());

  /** The overworld, the only dimension furthest blocks count in. */
  public static final String OVERWORLD = "minecraft:overworld";

  /**
   * One best so far.
   *
   * @param distance horizontal distance in whole blocks, rounded down
   */
  public record Best(long distance, Position at) {}

  /** This record with a newly placed block taken into account. Pure. */
  public FurthestBlock place(Position block, Position spawn) {
    Optional<Best> nextFromSpawn = better(fromSpawn, block, spawn.x(), spawn.z());
    Optional<Best> nextFromOrigin = better(fromOrigin, block, 0, 0);
    return new FurthestBlock(nextFromSpawn, nextFromOrigin);
  }

  private static Optional<Best> better(Optional<Best> current, Position block, double x, double z) {
    // Measure from the block's centre to the reference, rounded down to whole blocks.
    long distance =
        (long) Math.floor(Math.hypot(block.blockX() + 0.5 - x, block.blockZ() + 0.5 - z));
    if (current.isPresent() && current.get().distance() >= distance) {
      return current;
    }
    return Optional.of(new Best(distance, block));
  }
}
