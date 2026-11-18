// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.Chest;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * The blocks behind a block inventory: one, or both halves of a double chest. A double chest's
 * {@code Inventory#getLocation} is the point between its halves, which can't say which half, or
 * whether both, sit in the Hall.
 */
final class PaperChests {
  private PaperChests() {}

  /** The block itself, and the chest it is joined to if it is half of a double chest. */
  static List<Block> halves(Block block) {
    if (!(block.getBlockData() instanceof Chest chest) || chest.getType() == Chest.Type.SINGLE) {
      return List.of(block);
    }
    // As the game finds the other half: clockwise of the facing for a left half, else
    // anticlockwise.
    BlockFace facing = chest.getFacing();
    BlockFace side =
        chest.getType() == Chest.Type.LEFT
            ? clockwise(facing)
            : clockwise(facing).getOppositeFace();
    return List.of(block, block.getRelative(side));
  }

  /** Every block whose items an inventory holds; empty if no block holds them. */
  static List<Block> blocks(Inventory inventory) {
    InventoryHolder holder = inventory.getHolder(false);
    if (holder instanceof DoubleChest chest && chest.getLeftSide() instanceof BlockState left) {
      return halves(left.getBlock());
    }
    if (holder instanceof BlockInventoryHolder block) {
      return halves(block.getBlock());
    }
    return List.of();
  }

  static List<Position> positions(List<Block> blocks) {
    return blocks.stream().map(block -> PaperPositions.toPosition(block.getLocation())).toList();
  }

  private static BlockFace clockwise(BlockFace face) {
    return switch (face) {
      case NORTH -> BlockFace.EAST;
      case EAST -> BlockFace.SOUTH;
      case SOUTH -> BlockFace.WEST;
      case WEST -> BlockFace.NORTH;
      default -> face;
    };
  }
}
