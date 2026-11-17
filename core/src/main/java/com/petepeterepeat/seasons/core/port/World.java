// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.concurrent.CompletableFuture;

/** Blocks, chunks, dimensions and spawn. */
public interface World {
  boolean dimensionExists(String dimension);

  Position spawn(String dimension);

  /** Loads (and generates if needed) the chunk holding a position without stalling the tick. */
  CompletableFuture<Void> loadChunk(Position position);
}
