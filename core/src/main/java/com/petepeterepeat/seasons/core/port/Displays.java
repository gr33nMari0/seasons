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

  /** Spawns a text display at a size, such as a plaque's (questions 278). */
  default UUID spawnText(Position position, Message text, String tag, double scale) {
    return spawnText(position, text, tag);
  }

  /** Sets a text display's size; false if there is no such display. */
  default boolean scaleText(UUID display, double scale) {
    return false;
  }

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

  /** Whether a figure is drawn as this kind, in this pose (empty for standing). */
  boolean drawnAs(UUID figure, Figure.Kind kind, Optional<String> pose);

  /**
   * Removes every display between two corners whose tag starts with a prefix. The chunks must be
   * loaded.
   *
   * @return how many were removed
   */
  int removeTagged(Position min, Position max, String tagPrefix);

  /**
   * One outline box an admin sees (questions 276): its corners, inclusive, what it marks, which
   * sets its colour, and a label over it, if any.
   */
  record Outline(Position min, Position max, String kind, Optional<String> label) {
    /** The kinds, each its own colour. */
    public static final List<String> KINDS =
        List.of(
            "hub",
            "gallery",
            "cell",
            "focus",
            "gate",
            "entry",
            "exit",
            "link",
            "selection",
            "preview");
  }

  /**
   * Shows a player exactly these outlines, seen by nobody else and never saved with the world; an
   * empty list takes away what they were shown.
   */
  default void outline(PlayerId viewer, List<Outline> boxes) {}
}
