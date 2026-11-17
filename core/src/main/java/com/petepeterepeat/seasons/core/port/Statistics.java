// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Map;

/** A player's statistics. */
public interface Statistics {
  /** General-tab statistics of an online player, by statistic id. */
  Map<String, Long> general(PlayerId player);
}
