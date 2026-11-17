// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.port.Position;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * {@code <world>/seasons/seasons/<n>.yml}: one season's command-editable definition. M1 reads the
 * planned end and the spawn; candidates, Homecoming and zones arrive with their milestones and are
 * kept untouched when a command rewrites the file.
 *
 * @param plannedEnd the time reminders count down to; the source of truth for it
 * @param spawn where the season starts: launch sends everyone here and makes it the world spawn
 */
public record SeasonFile(int season, Optional<ZonedDateTime> plannedEnd, Optional<Position> spawn) {
  public SeasonFile(int season, Optional<ZonedDateTime> plannedEnd) {
    this(season, plannedEnd, Optional.empty());
  }
}
