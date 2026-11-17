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
 */
public record Figure(
    Position at,
    Message name,
    Optional<Skin> skin,
    List<Optional<NeutralItem>> equipment,
    String tag) {
  public Figure {
    equipment = List.copyOf(equipment);
  }
}
