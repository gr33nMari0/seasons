// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stands in for archiving until M1 part 2: locks nothing, archives nothing, moves no item, and
 * finishes at once, so the season still reaches the Intermission.
 */
public final class StubArchiver implements RolloverArchiver {
  private static final Logger LOG = LoggerFactory.getLogger(StubArchiver.class);

  @Override
  public CompletableFuture<Void> lockGalleries(int season) {
    LOG.info("Archiving isn't built yet (M1 part 2): no galleries to lock for season {}", season);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> archive(PlayerId player, int season) {
    LOG.info("Archiving isn't built yet (M1 part 2): {} keeps their gear for now", player);
    return CompletableFuture.completedFuture(null);
  }
}
