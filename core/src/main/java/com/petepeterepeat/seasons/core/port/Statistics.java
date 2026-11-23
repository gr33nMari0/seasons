// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Map;

/** A player's statistics. */
public interface Statistics {
  /** General-tab statistics of an online player, by statistic id. */
  Map<String, Long> general(PlayerId player);

  /**
   * The block counts the season card shows, which the General tab doesn't have, under {@code
   * seasons:} ids: {@link #DIAMONDS_MINED}, {@link #DEBRIS_MINED}, {@link #BLOCKS_PLACED} and
   * {@link #BLOCKS_MINED}. Taken with the General-tab statistics, so they have baselines too.
   */
  Map<String, Long> counts(PlayerId player);

  /**
   * General-tab statistics of any player, online or not, as last saved to disk by the game; empty
   * if it has none. Reads a file, so it runs off the server thread.
   */
  default java.util.Optional<Map<String, Long>> stored(PlayerId player) {
    return java.util.Optional.empty();
  }

  /** Diamond ore and deepslate diamond ore mined. */
  String DIAMONDS_MINED = "seasons:diamonds_mined";

  /** Ancient debris mined. */
  String DEBRIS_MINED = "seasons:ancient_debris_mined";

  /** Blocks placed: every block item used. */
  String BLOCKS_PLACED = "seasons:blocks_placed";

  /** Every block mined. */
  String BLOCKS_MINED = "seasons:blocks_mined";
}
