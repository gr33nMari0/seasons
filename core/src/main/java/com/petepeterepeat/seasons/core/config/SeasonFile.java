// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.zone.UnlockCondition;
import com.petepeterepeat.seasons.core.zone.Zone;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code <world>/seasons/seasons/<n>.yml}: one season's command-editable definition. Spawn
 * candidates arrive with their milestone and are kept untouched when a command rewrites the file.
 *
 * @param plannedEnd the time reminders count down to; the source of truth for it
 * @param spawn where the season starts: launch sends everyone here and makes it the world spawn
 * @param zones the zones over the old world, by id, which lock from this season's launch
 * @param homecoming when Homecoming happens by itself; manual unless the file says otherwise
 */
public record SeasonFile(
    int season,
    Optional<ZonedDateTime> plannedEnd,
    Optional<Position> spawn,
    Map<String, Zone> zones,
    UnlockCondition homecoming) {
  public SeasonFile {
    zones = Collections.unmodifiableMap(new TreeMap<>(zones));
  }

  public SeasonFile(int season, Optional<ZonedDateTime> plannedEnd, Optional<Position> spawn) {
    this(season, plannedEnd, spawn, Map.of(), new UnlockCondition.Manual());
  }

  public SeasonFile(int season, Optional<ZonedDateTime> plannedEnd) {
    this(season, plannedEnd, Optional.empty());
  }

  /** The first zone, by id, that holds the spawn, if any does. */
  public Optional<Zone> zoneHoldingSpawn() {
    return spawn.flatMap(at -> zoneHolding(at));
  }

  /** The first zone, by id, that holds a position, if any does. */
  public Optional<Zone> zoneHolding(Position at) {
    return zones.values().stream().filter(zone -> zone.contains(at)).findFirst();
  }
}
