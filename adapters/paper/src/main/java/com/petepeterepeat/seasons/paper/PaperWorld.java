// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.World;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;

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
  public BlockInfo block(Position position) {
    Optional<org.bukkit.World> world = PaperPositions.world(position.dimension());
    if (world.isEmpty()
        || !world.get().isChunkLoaded(position.blockX() >> 4, position.blockZ() >> 4)) {
      return BlockInfo.AIR;
    }
    Block block = world.get().getBlockAt(position.blockX(), position.blockY(), position.blockZ());
    Material type = block.getType();
    return new BlockInfo(
        type.getKey().asString(), block.isSolid(), block.isPassable(), isBed(type));
  }

  /** Every kind of bed, straw beds included. */
  static boolean isBed(Material type) {
    return Tag.BEDS.isTagged(type) || type == Material.STRAW_BED;
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
