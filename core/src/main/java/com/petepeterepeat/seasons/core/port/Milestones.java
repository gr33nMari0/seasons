// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/** What a player reaches that can be a season first, as the adapter sees it happen. */
public interface Milestones {
  /**
   * A player completed an advancement that has a display (so never a recipe unlock). Advancements
   * given back by {@code /season unarchive} are not reported.
   *
   * @param title the advancement's title, as plain text
   */
  void advancementDone(PlayerId player, String advancement, String title);

  /** A player arrived in a dimension, such as {@code minecraft:the_nether}. */
  void enteredDimension(PlayerId player, String dimension);

  /**
   * A player respawned after dying, once the game has sent their client the world as it is there
   * (its world border included).
   */
  default void respawned(PlayerId player) {}

  /** An ender dragon died, by anyone's hand or none. */
  default void dragonKilled() {}
}
