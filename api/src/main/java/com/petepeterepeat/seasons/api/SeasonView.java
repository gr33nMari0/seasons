// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The season now.
 *
 * @param season its number
 * @param phase {@code active}, {@code countdown}, {@code archiving} or {@code intermission}
 * @param stage while active or counting down, {@code frontier} (the old world locked) or {@code
 *     open} (after Homecoming)
 * @param plannedEnd when the season is planned to end, if set
 * @param countdownEndsAt when the end countdown running now finishes
 * @param launchedAt when the season launched, if Seasons launched it
 * @param homecomingAt when Homecoming came, if it has
 * @param zones the zones in force now, by name, never with their bounds
 */
public record SeasonView(
    int season,
    String phase,
    Optional<String> stage,
    Optional<Instant> plannedEnd,
    Optional<Instant> countdownEndsAt,
    Optional<Instant> launchedAt,
    Optional<Instant> homecomingAt,
    List<ZoneView> zones) {
  public SeasonView {
    zones = List.copyOf(zones);
  }

  /**
   * A zone in force.
   *
   * @param name as players read it, such as {@code old world}
   * @param mode what it stops now: {@code block}, {@code no-build}, {@code announce} or {@code off}
   */
  public record ZoneView(String name, String mode) {}
}
