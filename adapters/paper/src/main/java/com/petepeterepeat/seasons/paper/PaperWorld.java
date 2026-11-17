// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.World;
import java.util.concurrent.CompletableFuture;

final class PaperWorld implements World {
  @Override
  public boolean dimensionExists(String dimension) {
    return PaperPositions.world(dimension).isPresent();
  }

  @Override
  public Position spawn(String dimension) {
    return PaperPositions.toPosition(
        PaperPositions.world(dimension)
            .orElseThrow(() -> new IllegalArgumentException("No dimension " + dimension))
            .getSpawnLocation());
  }

  @Override
  public CompletableFuture<Void> loadChunk(Position position) {
    org.bukkit.World world =
        PaperPositions.world(position.dimension())
            .orElseThrow(
                () -> new IllegalArgumentException("No dimension " + position.dimension()));
    return world
        .getChunkAtAsync(position.blockX() >> 4, position.blockZ() >> 4, true)
        .thenApply(chunk -> null);
  }
}
