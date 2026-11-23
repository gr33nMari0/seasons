// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The museum (since 1.3): trophies an op put on its plinths, and each season's dragon egg with who
 * slew the dragon.
 */
public record MuseumView(List<Trophy> trophies, List<Egg> eggs) {
  public MuseumView {
    trophies = List.copyOf(trophies);
    eggs = List.copyOf(eggs);
  }

  /**
   * A trophy on a plinth.
   *
   * @param anchor the plinth, as {@code gallery:cell}
   * @param label the plaque's label, if it has one
   * @param season the season it was put there in
   * @param credit who put it there
   * @param item the item, as text; empty if it can't be read
   */
  public record Trophy(
      String anchor,
      Optional<String> label,
      int season,
      String credit,
      Instant at,
      Optional<ArchiveView.ItemView> item) {}

  /** A season's dragon egg, once the season has ended: who slew the dragon, and when. */
  public record Egg(int season, PlayerName slayer, Instant at) {}
}
