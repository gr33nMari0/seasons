// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api.event;

import com.petepeterepeat.seasons.api.PlayerName;
import java.time.Instant;
import java.util.Optional;

/**
 * Something that happened in the season, as {@link com.petepeterepeat.seasons.api.SeasonsApi}
 * delivers it. Never with coordinates.
 */
public sealed interface SeasonsEvent {
  /** When it happened. */
  Instant at();

  /** A player joined the server. */
  record PlayerJoined(Instant at, PlayerName player) implements SeasonsEvent {}

  /**
   * A season first.
   *
   * @param key what was reached, such as {@code nether}
   * @param label how it reads, such as {@code reached the Nether}
   */
  record SeasonFirst(Instant at, int season, String key, String label, PlayerName player)
      implements SeasonsEvent {}

  /**
   * The season's first dragon kill.
   *
   * @param slayer the slayer, if a player had a hand in it
   */
  record DragonSlain(Instant at, int season, Optional<PlayerName> slayer) implements SeasonsEvent {}

  /**
   * A player walked into an announce-mode zone.
   *
   * @param zone the zone's name as players read it
   */
  record ZoneEntered(Instant at, PlayerName player, String zone) implements SeasonsEvent {}

  /**
   * The season's phase or stage changed: a countdown began or was cancelled, the season ended, the
   * Intermission began, or the next season launched.
   *
   * @param phase as {@link com.petepeterepeat.seasons.api.SeasonView#phase()}
   * @param stage as {@link com.petepeterepeat.seasons.api.SeasonView#stage()}
   */
  record PhaseChanged(Instant at, int season, String phase, Optional<String> stage)
      implements SeasonsEvent {}

  /** Homecoming: the old world opened. */
  record HomecomingStarted(Instant at, int season) implements SeasonsEvent {}

  /** A player was archived into a season. */
  record PlayerArchived(Instant at, int season, PlayerName player) implements SeasonsEvent {}
}
