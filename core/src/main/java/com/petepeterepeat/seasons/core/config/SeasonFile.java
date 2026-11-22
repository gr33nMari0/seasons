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
import java.util.regex.Pattern;

/**
 * {@code <world>/seasons/seasons/<n>.yml}: one season's command-editable definition.
 *
 * @param plannedEnd the time reminders count down to; the source of truth for it
 * @param spawn where the season starts: launch sends everyone here and makes it the world spawn
 * @param zones the zones over the old world, by id, which lock from this season's launch
 * @param homecoming when Homecoming happens by itself; manual unless the file says otherwise
 * @param spawnCandidates places the spawn might go, by name, marked before the group votes
 */
public record SeasonFile(
    int season,
    Optional<ZonedDateTime> plannedEnd,
    Optional<Position> spawn,
    Map<String, Zone> zones,
    UnlockCondition homecoming,
    Map<String, Position> spawnCandidates) {
  /**
   * How far from 0, 0 an overworld spawn can be with strongholds still in reach: Java's last
   * stronghold ring ends at 24,320 blocks. Beyond it is a warning, never a refusal.
   */
  public static final int STRONGHOLD_REACH = 24_000;

  private static final String OVERWORLD = "minecraft:overworld";

  /**
   * A spawn candidate's name: lower-case letters, digits and dashes, such as {@code north-mesa}.
   */
  public static final Pattern CANDIDATE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

  public SeasonFile {
    zones = Collections.unmodifiableMap(new TreeMap<>(zones));
    spawnCandidates = Collections.unmodifiableMap(new TreeMap<>(spawnCandidates));
  }

  public SeasonFile(
      int season,
      Optional<ZonedDateTime> plannedEnd,
      Optional<Position> spawn,
      Map<String, Zone> zones,
      UnlockCondition homecoming) {
    this(season, plannedEnd, spawn, zones, homecoming, Map.of());
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

  /**
   * How far a block is from 0, 0 across x and z, measured from its middle and rounded down, as the
   * furthest block placed is.
   */
  public static long distanceFromOrigin(Position at) {
    return (long) Math.floor(Math.hypot(at.blockX() + 0.5, at.blockZ() + 0.5));
  }

  /** Whether an overworld spawn is further from 0, 0 than {@link #STRONGHOLD_REACH}. */
  public static boolean beyondStrongholds(Position at) {
    return at.dimension().equals(OVERWORLD)
        && Math.hypot(at.blockX() + 0.5, at.blockZ() + 0.5) > STRONGHOLD_REACH;
  }

  /** The first spawn candidate, by name, that a zone would hold, if any. */
  public Optional<Map.Entry<String, Position>> candidateIn(Zone zone) {
    return spawnCandidates.entrySet().stream().filter(c -> zone.contains(c.getValue())).findFirst();
  }
}
