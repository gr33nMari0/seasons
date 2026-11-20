// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code data/season.json}: the server-wide season state. Changed only through {@link
 * SeasonMachine}, on the server thread.
 *
 * @param plannedEnd a copy of the season file's planned end, which is the source of truth
 * @param plannedEndSetAt when that planned end was set; reminders already past then are never sent
 * @param remindersSent reminders already sent for this planned end, by how long before it they are
 * @param autoEndStarted whether {@code schedule.autoEnd} already started the countdown for this
 *     planned end, so a cancelled automatic countdown doesn't start again
 * @param countdownInterrupted when a restart cancelled a running countdown, until the next end
 * @param initSeason the season {@code /season init} recorded as already in progress, if any; a
 *     player with no record who played before then counts as having played it
 * @param launchApplied whether this season's launch finished its world changes (spawn, dragon flag,
 *     deaths objective); a launch cut short by a crash finishes them at startup
 * @param decorating the decorating window and the cells still open after archiving
 * @param firsts every season first recorded, this season's and earlier ones', oldest first; season
 *     cards read earlier seasons' from here after launch
 * @param zones this season's zones unlocked or locked one at a time, and the milestones their
 *     conditions wait for
 * @param dragon this season's dragon fight, its first kill and where its egg went
 * @param awards every season's awards, worked out once as each season's Intermission began; season
 *     cards, plaques and the museum read earlier seasons' from here after launch
 */
public record SeasonState(
    int season,
    Phase phase,
    Optional<ZonedDateTime> plannedEnd,
    Optional<Instant> plannedEndSetAt,
    Set<Duration> remindersSent,
    boolean autoEndStarted,
    Optional<Instant> launchedAt,
    Optional<Instant> homecomingAt,
    Optional<Instant> countdownInterrupted,
    Optional<Integer> initSeason,
    boolean launchApplied,
    Decorating decorating,
    List<SeasonFirst> firsts,
    ZoneState zones,
    DragonState dragon,
    List<SeasonAwards> awards) {
  public SeasonState {
    remindersSent = Set.copyOf(remindersSent);
    firsts = List.copyOf(firsts);
    awards = List.copyOf(awards);
  }

  /** A season recorded with {@code /season init} or started at launch. */
  public static SeasonState start(int season, Stage stage, Optional<Instant> launchedAt) {
    return new SeasonState(
        season,
        new Phase.Active(stage),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        false,
        launchedAt,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        launchedAt.isEmpty(),
        Decorating.NONE,
        List.of(),
        ZoneState.NONE,
        DragonState.NONE,
        List.of());
  }

  /** The state {@code /season init} records. */
  public static SeasonState init(int season) {
    SeasonState started = start(season, Stage.OPEN, Optional.empty());
    return new SeasonState(
        season,
        started.phase(),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        false,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(season),
        true,
        Decorating.NONE,
        List.of(),
        ZoneState.NONE,
        DragonState.NONE,
        List.of());
  }

  /** A season launched from this one's Intermission, keeping what it needs to remember. */
  public SeasonState next(Instant launchedAt) {
    SeasonState started = start(season + 1, Stage.FRONTIER, Optional.of(launchedAt));
    return new SeasonState(
        started.season(),
        started.phase(),
        Optional.empty(),
        Optional.empty(),
        Set.of(),
        false,
        Optional.of(launchedAt),
        Optional.empty(),
        Optional.empty(),
        initSeason,
        false,
        Decorating.NONE,
        firsts,
        ZoneState.NONE,
        DragonState.NONE,
        awards);
  }

  public SeasonState withLaunchApplied() {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        true,
        decorating,
        firsts,
        zones,
        dragon,
        awards);
  }

  public SeasonState withPhase(Phase next) {
    return new SeasonState(
        season,
        next,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        dragon,
        awards);
  }

  /** A new planned end, or none: reminders and the automatic end start over. */
  public SeasonState withPlannedEnd(Optional<ZonedDateTime> end, Instant now) {
    return new SeasonState(
        season,
        phase,
        end,
        end.map(e -> now),
        Set.of(),
        false,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        dragon,
        awards);
  }

  public SeasonState withRemindersSent(Set<Duration> sent) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        sent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        dragon,
        awards);
  }

  public SeasonState withAutoEndStarted() {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        true,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        dragon,
        awards);
  }

  public SeasonState withHomecoming(Instant at) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        Optional.of(at),
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        dragon,
        awards);
  }

  public SeasonState withCountdownInterrupted(Optional<Instant> at) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        at,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        dragon,
        awards);
  }

  public SeasonState withDecorating(Decorating next) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        next,
        firsts,
        zones,
        dragon,
        awards);
  }

  public SeasonState withZones(ZoneState next) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        next,
        dragon,
        awards);
  }

  public SeasonState withDragon(DragonState next) {
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        next,
        awards);
  }

  /** A season's awards, once its Intermission has worked them out. */
  public Optional<SeasonAwards> awards(int season) {
    return awards.stream().filter(a -> a.season() == season).findFirst();
  }

  /** The same state with a season's awards, which are worked out once. */
  public SeasonState withAwards(SeasonAwards worked) {
    List<SeasonAwards> next = new ArrayList<>(awards);
    next.removeIf(a -> a.season() == worked.season());
    next.add(worked);
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        firsts,
        zones,
        dragon,
        next);
  }

  /** The first recorded for a key in this season, if any. */
  public java.util.Optional<SeasonFirst> first(String key) {
    return firsts.stream().filter(f -> f.season() == season && f.key().equals(key)).findFirst();
  }

  /** One season's firsts, oldest first. */
  public List<SeasonFirst> firsts(int season) {
    return firsts.stream().filter(f -> f.season() == season).toList();
  }

  /** The same state with one more first. */
  public SeasonState withFirst(SeasonFirst first) {
    List<SeasonFirst> next = new ArrayList<>(firsts);
    next.add(first);
    return new SeasonState(
        season,
        phase,
        plannedEnd,
        plannedEndSetAt,
        remindersSent,
        autoEndStarted,
        launchedAt,
        homecomingAt,
        countdownInterrupted,
        initSeason,
        launchApplied,
        decorating,
        next,
        zones,
        dragon,
        awards);
  }
}
