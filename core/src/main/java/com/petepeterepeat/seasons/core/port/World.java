// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Blocks, chunks, dimensions and spawn. */
public interface World {
  boolean dimensionExists(String dimension);

  Position spawn(String dimension);

  /** Sets the world spawn, where new players and players with no respawn point appear. */
  void setSpawn(Position position);

  /** The block at a position, in a loaded chunk; air if its chunk isn't loaded. */
  BlockInfo block(Position position);

  /**
   * Sets every block to one type, such as {@code minecraft:iron_bars}, as the game would place it,
   * so panes and bars join their neighbours. Nothing is dropped. The chunks must be loaded.
   */
  void setBlocks(List<Position> blocks, String type);

  /** Whether the game knows an item, such as {@code minecraft:trident}. */
  boolean isItem(String id);

  /** Whether the game knows a block, such as {@code minecraft:iron_bars}. */
  boolean isBlock(String id);

  /** Whether the chunk holding a position is loaded, entities included. Never loads it. */
  boolean isLoaded(Position position);

  /** The lowest y a block can have in a dimension, such as -64 in the overworld. */
  int minHeight(String dimension);

  /** One above the highest y a block can have in a dimension, such as 320 in the overworld. */
  int maxHeight(String dimension);

  /** Loads (and generates if needed) the chunk holding a position without stalling the tick. */
  CompletableFuture<Void> loadChunk(Position position);
}
