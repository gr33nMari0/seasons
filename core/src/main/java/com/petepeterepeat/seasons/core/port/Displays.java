// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.List;
import java.util.Optional;
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
   * Every display between two corners, with the tag it was made with. The chunks must be loaded.
   */
  List<Shown> tagged(Position min, Position max);

  /** A display as it stands in the world. */
  record Shown(UUID id, String tag, Position at) {}

  /**
   * Whether a figure wears exactly this equipment: head, chest, legs, feet, main hand, off hand, as
   * {@link Figure#equipment()}.
   */
  boolean wears(UUID figure, List<Optional<NeutralItem>> equipment);

  /**
   * Removes every display between two corners whose tag starts with a prefix. The chunks must be
   * loaded.
   *
   * @return how many were removed
   */
  int removeTagged(Position min, Position max, String tagPrefix);
}
