// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.List;
import java.util.Optional;

/**
 * A figure to draw: immovable, invulnerable, silent, with no gravity, standing at a position.
 *
 * @param name shown above it, such as "Peter · Season 2"
 * @param skin the skin it wears, frozen at that season's; empty for the default skin
 * @param equipment head, chest, legs, feet, main hand, off hand; copies, never real items
 * @param tag what it belongs to, so reconciliation and clicks can find it
 * @param kind what draws it
 * @param pose a mannequin's pose, such as {@code crouching}; empty for standing, and an armour
 *     stand always stands
 */
public record Figure(
    Position at,
    Message name,
    Optional<Skin> skin,
    List<Optional<NeutralItem>> equipment,
    String tag,
    Kind kind,
    Optional<String> pose) {
  public Figure {
    equipment = List.copyOf(equipment);
  }

  /** What draws a figure (see Displays in the spec). */
  public enum Kind {
    MANNEQUIN,
    /** An armour stand in the player's head, which the adapter makes from the skin. */
    ARMOUR_STAND
  }
}
