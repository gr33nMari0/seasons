// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;

/**
 * Where each player's cell is. Filled in M1 part 3; until then nobody has a cell, so a player
 * archived during the Intermission stays where they are.
 */
@FunctionalInterface
public interface CellDirectory {
  /** Where to send a player archived into a season: their cell's entry. */
  Optional<Position> cell(PlayerId player, int season);

  /** No cells yet. */
  static CellDirectory none() {
    return (player, season) -> Optional.empty();
  }
}
