// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.World;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;

final class PaperWorld implements World {
  private final @org.jspecify.annotations.Nullable PaperText text;

  PaperWorld() {
    this(null);
  }

  PaperWorld(@org.jspecify.annotations.Nullable PaperText text) {
    this.text = text;
  }

  @Override
  public boolean writeSignLine(
      Position at, int line, com.petepeterepeat.seasons.core.message.Message message) {
    Optional<org.bukkit.block.Sign> sign = sign(at);
    if (sign.isEmpty() || text == null || line < 0 || line > 3) {
      return false;
    }
    sign.get().getSide(org.bukkit.block.sign.Side.FRONT).line(line, text.render(message).compact());
    sign.get().update(true, false);
    return true;
  }

  @Override
  public boolean glowSign(Position at, boolean glowing) {
    Optional<org.bukkit.block.Sign> sign = sign(at);
    if (sign.isEmpty()) {
      return false;
    }
    sign.get().getSide(org.bukkit.block.sign.Side.FRONT).setGlowingText(glowing);
    sign.get().update(true, false);
    return true;
  }

  private static Optional<org.bukkit.block.Sign> sign(Position at) {
    Optional<org.bukkit.World> world = PaperPositions.world(at.dimension());
    if (world.isEmpty() || !world.get().isChunkLoaded(at.blockX() >> 4, at.blockZ() >> 4)) {
      return Optional.empty();
    }
    return world.get().getBlockAt(at.blockX(), at.blockY(), at.blockZ()).getState(false)
            instanceof org.bukkit.block.Sign sign
        ? Optional.of(sign)
        : Optional.empty();
  }

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
        type.getKey().asString(),
        block.isSolid(),
        block.isPassable(),
        isBed(type),
        PaperEvents.isInteractable(block));
  }

  /** Every kind of bed, straw beds included. */
  static boolean isBed(Material type) {
    return Tag.BEDS.isTagged(type) || type == Material.STRAW_BED;
  }

  @Override
  public void setBlocks(List<Position> blocks, String type) {
    Material material = Material.matchMaterial(type);
    if (material == null || !material.isBlock()) {
      throw new IllegalArgumentException(type + " is not a block");
    }
    for (Position position : blocks) {
      // Physics on, so bars and panes join their neighbours; setType never drops anything.
      world(position.dimension())
          .getBlockAt(position.blockX(), position.blockY(), position.blockZ())
          .setType(material, true);
    }
  }

  @Override
  public boolean isItem(String id) {
    Material material = Material.matchMaterial(id);
    return material != null && material.isItem();
  }

  @Override
  public Optional<NeutralItem> item(String id) {
    Material material = Material.matchMaterial(id);
    return material == null || !material.isItem()
        ? Optional.empty()
        : Optional.of(new NeutralItem(ItemStack.of(material).serializeAsBytes()));
  }

  @Override
  public boolean isGateBlock(String id) {
    Material material = Material.matchMaterial(id);
    return material != null
        && material.isBlock()
        && material.isSolid()
        && !material.hasGravity()
        && !(material.createBlockData().createBlockState() instanceof TileState);
  }

  @Override
  public boolean holdsContents(Position position) {
    if (!isLoaded(position)) {
      return false;
    }
    return world(position.dimension())
            .getBlockAt(position.blockX(), position.blockY(), position.blockZ())
            .getState(false)
        instanceof TileState;
  }

  @Override
  public boolean isLoaded(Position position) {
    Optional<org.bukkit.World> world = PaperPositions.world(position.dimension());
    int x = position.blockX() >> 4;
    int z = position.blockZ() >> 4;
    return world.isPresent()
        && world.get().isChunkLoaded(x, z)
        && world.get().getChunkAt(x, z).isEntitiesLoaded();
  }

  @Override
  public int minHeight(String dimension) {
    return world(dimension).getMinHeight();
  }

  @Override
  public int maxHeight(String dimension) {
    return world(dimension).getMaxHeight();
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
