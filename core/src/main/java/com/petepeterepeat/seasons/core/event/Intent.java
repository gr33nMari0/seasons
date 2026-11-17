// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.event;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;

/** Something a player is about to do, raised by an adapter from a platform event. */
public sealed interface Intent {
  PlayerId player();

  /** Breaking the block at a position. */
  record BreakBlock(PlayerId player, Position block) implements Intent {}

  /**
   * Placing a block at a position.
   *
   * @param blockType a namespaced block id, such as {@code minecraft:hopper}
   */
  record PlaceBlock(PlayerId player, Position block, String blockType) implements Intent {}
}
