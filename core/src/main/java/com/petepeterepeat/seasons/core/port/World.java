// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.concurrent.CompletableFuture;

/** Blocks, chunks, dimensions and spawn. */
public interface World {
  boolean dimensionExists(String dimension);

  Position spawn(String dimension);

  /** Sets the world spawn, where new players and players with no respawn point appear. */
  void setSpawn(Position position);

  /** The block at a position, in a loaded chunk; air if its chunk isn't loaded. */
  BlockInfo block(Position position);

  /** Loads (and generates if needed) the chunk holding a position without stalling the tick. */
  CompletableFuture<Void> loadChunk(Position position);
}
