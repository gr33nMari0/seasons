// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.UUID;

/** Figures, text and item displays, and books. Every display carries the tag it was made with. */
public interface Displays {
  /**
   * Spawns a text display.
   *
   * @param tag what the display belongs to, such as a cell id, so reconciliation can find it
   * @return the display entity's id
   */
  UUID spawnText(Position position, Message text, String tag);

  /** Spawns a figure. */
  UUID spawnFigure(Figure figure);

  /** Spawns an item display, a copy of the item, such as a trophy. */
  UUID spawnItem(Position position, NeutralItem item, String tag);

  void remove(UUID display);

  /**
   * Removes every display between two corners whose tag starts with a prefix. The chunks must be
   * loaded.
   *
   * @return how many were removed
   */
  int removeTagged(Position min, Position max, String tagPrefix);
}
