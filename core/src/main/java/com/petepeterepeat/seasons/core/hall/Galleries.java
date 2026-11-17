// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.concurrent.CompletableFuture;

/** The Hall's galleries. Filled in M1 part 3; until then there are none to lock. */
@FunctionalInterface
public interface Galleries {
  /**
   * Locks every gallery before anyone is archived at season end; owners standing in their cell
   * switch to adventure mode first.
   */
  CompletableFuture<Void> lockAll(int season);

  /** No galleries yet. */
  static Galleries none() {
    return season -> CompletableFuture.completedFuture(null);
  }
}
