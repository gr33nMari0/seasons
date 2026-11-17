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
    return PaperPositions.toPosition(world(dimension).getSpawnLocation());
  }

  @Override
  public void setSpawn(Position position) {
    world(position.dimension()).setSpawnLocation(PaperPositions.toLocation(position));
  }

  @Override
  public CompletableFuture<Void> loadChunk(Position position) {
    return world(position.dimension())
        .getChunkAtAsync(position.blockX() >> 4, position.blockZ() >> 4, true)
        .thenApply(chunk -> null);
  }

  private static org.bukkit.World world(String dimension) {
    return PaperPositions.world(dimension)
        .orElseThrow(() -> new IllegalArgumentException("No dimension " + dimension));
  }
}
