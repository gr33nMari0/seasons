// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Advancements and a player's progress in them. Recipe unlocks are the advancements under {@code
 * recipes/}; they have no display, so they are never visible. Hidden entries aren't visible either.
 */
public interface Advancements {
  /** Ids of every visible advancement, such as {@code minecraft:story/mine_stone}. */
  List<String> visible();

  /** Awarded criteria per visible advancement the player has any progress in. */
  Map<String, Set<String>> awarded(PlayerId player);

  /**
   * Every criterion an online player has been awarded, in every advancement except recipe unlocks
   * unless {@code includeRecipes}, with the completed and visible counts.
   */
  AdvancementSnapshot snapshot(PlayerId player, boolean includeRecipes);

  /**
   * Revokes every criterion of every advancement except recipe unlocks unless {@code
   * includeRecipes}. Revoking twice changes nothing.
   */
  void revokeAll(PlayerId player, boolean includeRecipes);

  /** Awards criteria back, without announcing them in chat. */
  void award(PlayerId player, Map<String, Map<String, Instant>> criteria);

  /** Forgets every recipe the player has discovered, for {@code recipes: reset}. */
  void forgetRecipes(PlayerId player);
}
