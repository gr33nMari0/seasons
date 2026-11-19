// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * {@code data/players/<uuid>.json}: what Seasons knows about one player. Records follow the UUID;
 * the name is the last one seen. Changed only on the server thread.
 *
 * @param seasons the seasons they played, with play time and first and last seen
 * @param archivedThrough the latest season they have been archived into, or 0
 * @param seasonEntered the latest season they have been brought into: sent to its spawn with their
 *     stat baseline taken, at launch or at their first login after it
 * @param baselines vanilla General-tab statistics at the start of each season, by season
 * @param furthest furthest block placed, by season
 * @param pendingDisplays seasons whose displays still need rendering
 * @param pendingDestination a season archived whose cell they haven't been sent to yet
 * @param hallAdventure Seasons switched them from survival to adventure in the Hall, so leaving it
 *     puts survival back; kept here so a restart doesn't strand them in adventure
 * @param hallReturn where a visitor to the Hall goes back to, and in which game mode; kept here so
 *     it survives logging out and restarts
 * @param pets pets registered to travel with them, in the order registered
 * @param notices messages waiting for their next login, such as a pet that died while they were
 *     away
 */
public record PlayerRecord(
    PlayerId id,
    String lastName,
    Map<Integer, SeasonPlay> seasons,
    int archivedThrough,
    int seasonEntered,
    Map<Integer, Map<String, Long>> baselines,
    Map<Integer, FurthestBlock> furthest,
    Set<Integer> pendingDisplays,
    OptionalInt pendingDestination,
    boolean hallAdventure,
    Optional<HallReturn> hallReturn,
    List<Pet> pets,
    List<Notice> notices) {
  public PlayerRecord {
    pets = List.copyOf(pets);
    notices = List.copyOf(notices);
    seasons = Map.copyOf(seasons);
    baselines = Map.copyOf(baselines);
    furthest = Map.copyOf(furthest);
    pendingDisplays = Set.copyOf(pendingDisplays);
  }

  /**
   * Time in one season.
   *
   * @param playTime online time while the season was active
   */
  public record SeasonPlay(Instant firstSeen, Instant lastSeen, Duration playTime) {}

  /**
   * Where a Hall visitor returns to.
   *
   * @param mode the game mode they had when they set off
   */
  public record HallReturn(Position at, GameMode mode) {}

  /**
   * A tamed animal registered to travel with its owner to the next season's spawn.
   *
   * @param type the entity type's id without its namespace, such as {@code wolf}
   * @param name what its owner calls it in {@code /pet} commands, unique among their pets
   * @param lastSeen where it was registered, or last stood when its chunk unloaded
   * @param season the season it was registered in; it travels when they enter a later one
   * @param onShoulder a parrot last seen landing on its owner's shoulder, so out of the world but
   *     with them
   */
  public record Pet(
      java.util.UUID entity,
      String type,
      String name,
      Optional<Position> lastSeen,
      int season,
      boolean onShoulder) {
    public Pet withLastSeen(Position at) {
      return new Pet(entity, type, name, Optional.of(at), season, false);
    }

    public Pet withOnShoulder() {
      return new Pet(entity, type, name, lastSeen, season, true);
    }
  }

  /**
   * A message for a player's next login.
   *
   * @param placeholders alternating placeholder names and values
   */
  public record Notice(String key, List<String> placeholders) {
    public Notice {
      placeholders = List.copyOf(placeholders);
    }
  }

  public static PlayerRecord create(PlayerId id, String name) {
    return new PlayerRecord(
        id,
        name,
        Map.of(),
        0,
        0,
        Map.of(),
        Map.of(),
        Set.of(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        List.of(),
        List.of());
  }

  public boolean played(int season) {
    return seasons.containsKey(season);
  }

  public PlayerRecord withName(String name) {
    return new PlayerRecord(
        id,
        name,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn,
        pets,
        notices);
  }

  /** Marks the season played and adds online time to it. */
  public PlayerRecord withPlay(int season, Instant now, Duration more) {
    Map<Integer, SeasonPlay> next = new HashMap<>(seasons);
    SeasonPlay play = seasons.get(season);
    next.put(
        season,
        play == null
            ? new SeasonPlay(now, now, more)
            : new SeasonPlay(play.firstSeen(), now, play.playTime().plus(more)));
    return new PlayerRecord(
        id,
        lastName,
        next,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn,
        pets,
        notices);
  }

  /** Archived into a season; a saved Hall return is dropped, since it belongs to that season. */
  public PlayerRecord withArchived(int season) {
    Set<Integer> displays = new HashSet<>(pendingDisplays);
    displays.add(season);
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        Math.max(archivedThrough, season),
        seasonEntered,
        baselines,
        furthest,
        displays,
        OptionalInt.of(season),
        hallAdventure,
        Optional.empty(),
        pets,
        notices);
  }

  /**
   * After {@code /season unarchive}: no longer archived into {@code season}. Only unarchiving the
   * latest archived season lowers {@link #archivedThrough}; unarchiving an older one leaves it, so
   * later seasons, still archived, are never archived over again.
   */
  public PlayerRecord withUnarchived(int season) {
    Set<Integer> displays = new HashSet<>(pendingDisplays);
    displays.remove(season);
    OptionalInt destination =
        pendingDestination.isPresent() && pendingDestination.getAsInt() == season
            ? OptionalInt.empty()
            : pendingDestination;
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough == season ? season - 1 : archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        displays,
        destination,
        hallAdventure,
        hallReturn,
        pets,
        notices);
  }

  public PlayerRecord withDisplaysRendered(int season) {
    Set<Integer> displays = new HashSet<>(pendingDisplays);
    displays.remove(season);
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        displays,
        pendingDestination,
        hallAdventure,
        hallReturn,
        pets,
        notices);
  }

  public PlayerRecord withDestinationReached() {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        OptionalInt.empty(),
        hallAdventure,
        hallReturn,
        pets,
        notices);
  }

  /** Brought into a season, with the statistics it starts from; a saved Hall return is dropped. */
  public PlayerRecord withEntered(int season, Map<String, Long> baseline) {
    Map<Integer, Map<String, Long>> next = new HashMap<>(baselines);
    next.put(season, Map.copyOf(baseline));
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        Math.max(seasonEntered, season),
        next,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        Optional.empty(),
        pets,
        notices);
  }

  public FurthestBlock furthest(int season) {
    return furthest.getOrDefault(season, FurthestBlock.NONE);
  }

  public PlayerRecord withFurthest(int season, FurthestBlock best) {
    Map<Integer, FurthestBlock> next = new HashMap<>(furthest);
    next.put(season, best);
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        next,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn,
        pets,
        notices);
  }

  public PlayerRecord withHallAdventure(boolean switched) {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        switched,
        hallReturn,
        pets,
        notices);
  }

  public PlayerRecord withHallReturn(Optional<HallReturn> next) {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        next,
        pets,
        notices);
  }

  public PlayerRecord withPets(List<Pet> next) {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn,
        next,
        notices);
  }

  public PlayerRecord withNotices(List<Notice> next) {
    return new PlayerRecord(
        id,
        lastName,
        seasons,
        archivedThrough,
        seasonEntered,
        baselines,
        furthest,
        pendingDisplays,
        pendingDestination,
        hallAdventure,
        hallReturn,
        pets,
        next);
  }

  public Optional<Map<String, Long>> baseline(int season) {
    return Optional.ofNullable(baselines.get(season));
  }
}
