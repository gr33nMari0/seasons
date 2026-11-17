// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.concurrent.CompletableFuture;

/**
 * Draws a player's displays (figure, plaque, archive anchor) from their archive. Filled in M1 part
 * 3; until then there is nothing to draw. Rendering is idempotent and retried until it succeeds.
 */
public interface DisplayRenderer {
  /** Draws, or redraws, one player's displays for one season. */
  CompletableFuture<Void> render(PlayerId player, int season);

  /** Removes one player's displays for one season, as {@code /season unarchive} does. */
  void remove(PlayerId player, int season);

  /** Nothing to draw yet. */
  static DisplayRenderer none() {
    return new DisplayRenderer() {
      @Override
      public CompletableFuture<Void> render(PlayerId player, int season) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void remove(PlayerId player, int season) {}
    };
  }
}
