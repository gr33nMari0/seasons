// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/**
 * What the end sequence asks of archiving. Called on the server thread; the futures may complete on
 * any thread.
 */
public interface RolloverArchiver {
  /** Locks every gallery before anyone is archived. */
  CompletableFuture<Void> lockGalleries(int season);

  /**
   * Archives one online player into {@code season}. Harmless to call again for the same pair.
   * Completes with the number of item stacks archived if the player was archived now, or empty if
   * they weren't (already archived, offline, busy or failed), for the server log's summary.
   */
  CompletableFuture<OptionalInt> archive(PlayerId player, int season);
}
