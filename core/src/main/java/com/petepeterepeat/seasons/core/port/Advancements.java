// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Visible advancements and a player's progress in them. Recipe unlocks are never visible. */
public interface Advancements {
  /** Ids of every advancement that has a display, such as {@code minecraft:story/mine_stone}. */
  List<String> visible();

  /** Awarded criteria per visible advancement the player has any progress in. */
  Map<String, Set<String>> awarded(PlayerId player);
}
