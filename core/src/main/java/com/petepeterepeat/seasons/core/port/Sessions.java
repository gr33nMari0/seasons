// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/** Players joining and leaving, as the adapter reports them. */
public interface Sessions {
  /**
   * A player has joined and is in the world.
   *
   * @param playedBefore whether the server had their player file before this join
   */
  void joined(PlayerRef player, boolean playedBefore);

  /** A player has left. */
  void left(PlayerId player);
}
