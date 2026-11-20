// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Optional;

/** The End's dragon fight. */
public interface DragonFight {
  /**
   * Clears or sets the flag that decides whether the next dragon drops an egg.
   *
   * @return false if there is no End with a dragon fight loaded to set it on
   */
  boolean setPreviouslyKilled(boolean previouslyKilled);

  /** Whether the flag is set, so a dragon dying now drops no egg; false with no End loaded. */
  boolean previouslyKilled();

  Optional<Position> exitPortal();

  /**
   * The dragon egg on top of the exit portal, where the game puts one: the highest block of the
   * portal's column, if it is a dragon egg. Loads that chunk if it must.
   */
  Optional<Position> eggOnPortal();

  /** Takes the dragon egg at a block away, leaving air; false if no egg stands there. */
  boolean removeEgg(Position at);

  /**
   * Freezes or frees the dragon egg on top of the exit portal: while frozen nobody can make it
   * teleport, break it, push it or blow it up, so it stays put until Seasons takes it.
   */
  void guardEgg(boolean guarded);

  /** One dragon egg, as an item. */
  NeutralItem eggItem();
}
