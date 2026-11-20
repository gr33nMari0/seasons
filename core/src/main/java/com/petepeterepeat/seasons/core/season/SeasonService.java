// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.config.SeasonFiles;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.ProgressBar;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import com.petepeterepeat.seasons.core.season.Phase.Archiving;
import com.petepeterepeat.seasons.core.season.Phase.Countdown;
import com.petepeterepeat.seasons.core.season.SeasonEvent.AllArchived;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Cancel;
import com.petepeterepeat.seasons.core.season.SeasonEvent.CountdownElapsed;
import com.petepeterepeat.seasons.core.season.SeasonEvent.End;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Init;
import com.petepeterepeat.seasons.core.season.SeasonEvent.Restarted;
import com.petepeterepeat.seasons.core.season.SeasonEvent.ScheduleEnd;
import com.petepeterepeat.seasons.core.season.Transition.Changed;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the season lifecycle on the server thread: applies events through {@link SeasonMachine},
 * saves the state, and does what each change means (the countdown bar, reminders, the end
 * sequence).
 *
 * <p>The state in memory changes at once, so a second command in the same tick sees it. Saving runs
 * on the writer thread; archiving starts only once the Archiving phase is on disk, so a crash
 * during it always finds Archiving and runs the end sequence again.
 */
public final class SeasonService {
  /** Who hears about failures and interrupted countdowns. */
  public static final String ADMIN_PERMISSION = "seasons.admin.season";

  private static final Logger LOG = LoggerFactory.getLogger(SeasonService.class);

  /** What launch does to the world and the players, once the new season is saved. */
  public interface Launcher {
    /**
     * Why the next season can't launch now, as a messages.yml key whose placeholders are {@code
     * <season>} and {@code <next>}; empty if it can.
     */
    Optional<String> refusal(int nextSeason);

    /**
     * Sets the world spawn, resets the dragon flag, creates the deaths objective and brings
     * everyone online into the launched season. Harmless to run again after a crash.
     */
    CompletableFuture<Void> apply(SeasonState launched);

    /**
     * Shows a launched season's deaths objective where {@code deaths.display} now says, such as
     * after a reload changes it.
     */
    default void showDeaths(SeasonState current) {}
  }

  private static final Duration TICK = Duration.ofSeconds(1);

  private final Players players;
  private final Scheduler scheduler;
  private final Clock clock;
  private final SeasonStore store;
  private final SerialWriter writer;
  private final Supplier<ConfigSnapshot> config;
  private final Announcer announcer;
  private final RolloverArchiver archiver;
  private final Launcher launcher;
  private final Path seasonsDirectory;
  private final Path backupsDirectory;
  private final Executor serverThread;

  private Optional<SeasonState> state = Optional.empty();
  private Optional<String> loadError = Optional.empty();
  private CompletableFuture<Void> lastSave = CompletableFuture.completedFuture(null);
  private final List<java.util.function.IntConsumer> endingListeners = new java.util.ArrayList<>();
  private final List<java.util.function.IntConsumer> intermissionListeners =
      new java.util.ArrayList<>();
  private Instant startedAt;
  private @Nullable ProgressBar bar;
  private @Nullable ScheduledTask ticker;
  private boolean autoEndHeld;

  /**
   * @param seasonsDirectory {@code <world>/seasons/seasons}, where season files live
   * @param backupsDirectory {@code <world>/seasons/backups}
   */
  public SeasonService(
      Players players,
      Scheduler scheduler,
      Clock clock,
      SeasonStore store,
      SerialWriter writer,
      Supplier<ConfigSnapshot> config,
      Announcer announcer,
      RolloverArchiver archiver,
      Launcher launcher,
      Path seasonsDirectory,
      Path backupsDirectory) {
    this.players = players;
    this.scheduler = scheduler;
    this.clock = clock;
    this.store = store;
    this.writer = writer;
    this.config = config;
    this.announcer = announcer;
    this.archiver = archiver;
    this.launcher = launcher;
    this.seasonsDirectory = seasonsDirectory;
    this.backupsDirectory = backupsDirectory;
    this.serverThread = scheduler::runGlobal;
    this.startedAt = clock.instant();
  }

  /** The current state, or empty before {@code /season init}. */
  public Optional<SeasonState> state() {
    return state;
  }

  /** Why season.json couldn't be read at startup, if it couldn't. */
  public Optional<String> loadError() {
    return loadError;
  }

  /** At startup, on the server thread: loads the state and resumes whatever was in progress. */
  public void start() {
    startedAt = clock.instant();
    try {
      state = store.load();
    } catch (Exception e) {
      loadError = Optional.of(String.valueOf(e.getMessage()));
      LOG.error("Can't read season.json; season commands are refused until it is fixed", e);
    }
    if (state.isPresent()) {
      boolean counting = state.get().phase() instanceof Countdown;
      apply(new Restarted());
      if (counting) {
        announcer.admins(ADMIN_PERMISSION, "end.interrupted", "season", seasonNumber());
      }
      reconcilePlannedEnd(config.get());
      if (state.get().phase() instanceof Archiving) {
        endAfterSave();
      }
      if (state.get().phase() instanceof Phase.Active && !state.get().launchApplied()) {
        LOG.warn("Season {} launched but didn't finish; finishing it now", seasonNumber());
        launchAfterSave();
      }
      if (state.get().phase() instanceof Phase.Intermission) {
        int season = state.get().season();
        // Once everything else has started.
        scheduler.runGlobal(
            () -> intermissionListeners.forEach(listener -> listener.accept(season)));
      }
    }
    ticker = scheduler.runGlobalRepeating(TICK, this::tick);
  }

  /** At shutdown. */
  public void stop() {
    if (ticker != null) {
      ticker.cancel();
      ticker = null;
    }
    hideBar();
  }

  /** {@code /season init}. */
  public Transition init(int season) {
    if (loadError.isPresent()) {
      return new Transition.Refused("season.unreadable");
    }
    Transition transition = apply(new Init(season));
    if (transition instanceof Changed) {
      Path file = seasonFile(season);
      Path backups = backupsDirectory.resolve("seasons");
      writer
          .submit(() -> {
            SeasonFiles.create(file, backups, season);
            return Boolean.TRUE;
          })
          .exceptionally(e -> logFailure("Can't write " + file, e));
    }
    return transition;
  }

  /** {@code /season schedule end}. */
  public Transition scheduleEnd(ZonedDateTime at) {
    Transition transition = apply(new ScheduleEnd(Optional.of(at)));
    if (transition instanceof Changed changed) {
      int season = changed.state().season();
      Path file = seasonFile(season);
      Path backups = backupsDirectory.resolve("seasons");
      writer
          .submit(() -> {
            SeasonFiles.writePlannedEnd(file, backups, season, Optional.of(at));
            return Boolean.TRUE;
          })
          .exceptionally(e -> logFailure("Can't write " + file, e));
    }
    return transition;
  }

  /** {@code /season end}, with {@code --now} as {@code skipCountdown}. */
  public Transition end(boolean skipCountdown) {
    if (config.get().anyGalleryLeftOut()
        && state.map(s -> s.phase() instanceof Phase.Active).orElse(false)) {
      // Its players would be archived without cells or displays.
      return new Transition.Refused("end.galleries-left-out");
    }
    Duration countdown =
        skipCountdown ? Duration.ZERO : config.get().config().schedule().endCountdown();
    Transition transition = apply(new End(countdown));
    if (transition instanceof Changed changed) {
      switch (changed.state().phase()) {
        case Countdown running -> {
          announcer.broadcast(
              "end.started",
              "season",
              seasonNumber(),
              "when",
              HumanTime.duration(config.get().messages(), running.length()));
          updateBar(running);
        }
        case Archiving archiving -> endAfterSave();
        default -> {}
      }
    }
    return transition;
  }

  /**
   * Why {@code /season launch} would be refused now, as a messages.yml key; empty if it wouldn't.
   */
  public Optional<String> launchRefusal() {
    if (config.get().anyGalleryLeftOut()) {
      return Optional.of("launch.galleries-left-out");
    }
    Transition would = SeasonMachine.apply(state, new SeasonEvent.Launch(), clock.instant());
    if (would instanceof Transition.Refused refused) {
      return Optional.of(refused.messageKey());
    }
    return launcher.refusal(state.map(SeasonState::season).orElse(0) + 1);
  }

  /** {@code /season launch}. */
  public Transition launch() {
    Optional<String> refusal = launchRefusal();
    if (refusal.isPresent()) {
      return new Transition.Refused(refusal.get());
    }
    Transition transition = apply(new SeasonEvent.Launch());
    if (transition instanceof Changed) {
      launchAfterSave();
    }
    return transition;
  }

  /** {@code /season cancel}. */
  public Transition cancel() {
    Transition transition = apply(new Cancel());
    if (transition instanceof Changed) {
      hideBar();
      announcer.broadcast("end.cancelled", "season", seasonNumber());
    }
    return transition;
  }

  /** After a successful reload, on the server thread. */
  public void reloaded(ConfigSnapshot snapshot) {
    reconcilePlannedEnd(snapshot);
    // A changed deaths.display moves the season's objective at once.
    state
        .filter(s -> s.launchedAt().isPresent() && s.launchApplied())
        .ifPresent(launcher::showDeaths);
  }

  /** Once a second: the countdown, reminders and the automatic end. */
  void tick() {
    if (state.isEmpty()) {
      return;
    }
    SeasonState current = state.get();
    Instant now = clock.instant();
    if (current.phase() instanceof Countdown running) {
      if (now.isBefore(running.endsAt())) {
        updateBar(running);
      } else {
        hideBar();
        apply(new CountdownElapsed());
        endAfterSave();
      }
      return;
    }
    ConfigSnapshot snapshot = config.get();
    Optional<Reminders.Due> due =
        Reminders.due(current, snapshot.config().schedule().reminders(), now);
    if (due.isPresent()) {
      update(current.withRemindersSent(due.get().sent()));
      String when = HumanTime.duration(snapshot.messages(), due.get().before());
      String time = HumanTime.time(due.get().plannedEnd());
      announcer.broadcast("reminder", "season", seasonNumber(), "when", when, "time", time);
      announcer.discord("reminder", "season", seasonNumber(), "when", when, "time", time);
    }
    if (snapshot.config().schedule().autoEnd()) {
      Optional<Instant> at = Reminders.autoEndAt(state.get(), startedAt);
      if (at.isPresent() && !now.isBefore(at.get())) {
        if (snapshot.anyGalleryLeftOut()) {
          // Waits for the gallery to be fixed, telling admins once.
          if (!autoEndHeld) {
            autoEndHeld = true;
            announcer.admins(ADMIN_PERMISSION, "end.galleries-left-out", "season", seasonNumber());
          }
          return;
        }
        update(state.get().withAutoEndStarted());
        end(false);
      }
    }
  }

  /**
   * Hears that a season's end sequence has started, its countdown over, such as the museum drawing
   * the season's dragon egg. Heard again if a restart resumes the sequence.
   */
  public void onEnding(java.util.function.IntConsumer listener) {
    endingListeners.add(listener);
  }

  /**
   * Hears that a season's Intermission has begun, such as the awards being worked out. Heard again
   * at startup while the server is in the Intermission, so what a restart cut short is finished.
   */
  public void onIntermission(java.util.function.IntConsumer listener) {
    intermissionListeners.add(listener);
  }

  /** Online players are archived, then the season moves to the Intermission. */
  private void runEndSequence() {
    if (state.isEmpty() || !(state.get().phase() instanceof Archiving)) {
      return;
    }
    int season = state.get().season();
    endingListeners.forEach(listener -> listener.accept(season));
    List<PlayerId> online = players.online().stream().map(PlayerRef::id).toList();
    var _ =
        archiver
            .lockGalleries(season)
            .thenComposeAsync(
                locked ->
                    CompletableFuture.allOf(
                        online.stream()
                            .map(player -> archiver.archive(player, season))
                            .toArray(CompletableFuture[]::new)),
                serverThread)
            .whenCompleteAsync(
                (done, failure) -> {
                  if (failure != null) {
                    LOG.error("The end sequence stopped", failure);
                    announcer.admins(
                        ADMIN_PERMISSION,
                        "end.archiving-failed",
                        "season",
                        Integer.toString(season),
                        "error",
                        String.valueOf(failure.getMessage()));
                    return;
                  }
                  if (apply(new AllArchived()) instanceof Changed) {
                    announcer.broadcast("end.intermission", "season", Integer.toString(season));
                    intermissionListeners.forEach(listener -> listener.accept(season));
                  }
                },
                serverThread);
  }

  /** Brings the state's planned end in line with the season file, the source of truth. */
  private void reconcilePlannedEnd(ConfigSnapshot snapshot) {
    if (state.isEmpty()) {
      return;
    }
    SeasonState current = state.get();
    Optional<ZonedDateTime> inFile =
        snapshot.season(current.season()).flatMap(SeasonFile::plannedEnd);
    if (!inFile.equals(current.plannedEnd()) && !(inFile.isEmpty() && fileMissing(snapshot))) {
      apply(new ScheduleEnd(inFile));
    }
  }

  /** A season with no file yet keeps the planned end it has. */
  private boolean fileMissing(ConfigSnapshot snapshot) {
    return state.isPresent() && snapshot.season(state.get().season()).isEmpty();
  }

  /** Records a season first, and saves it. Nothing happens before {@code /season init}. */
  public void addFirst(SeasonFirst first) {
    state.ifPresent(current -> update(current.withFirst(first)));
  }

  /** Records a season's awards, and saves them. */
  public void updateAwards(SeasonAwards awards) {
    state.ifPresent(current -> update(current.withAwards(awards)));
  }

  /** {@code /season unlock homecoming}, or its condition coming true. */
  public Transition homecoming() {
    return apply(new SeasonEvent.Homecoming());
  }

  /** Changes what the zones remember, and saves it. Nothing happens before {@code /season init}. */
  public void updateZones(java.util.function.UnaryOperator<ZoneState> change) {
    state.ifPresent(current -> update(current.withZones(change.apply(current.zones()))));
  }

  /**
   * Changes what the season remembers of the dragon, and saves it. Nothing happens before {@code
   * /season init}.
   */
  public void updateDragon(java.util.function.UnaryOperator<DragonState> change) {
    state.ifPresent(current -> update(current.withDragon(change.apply(current.dragon()))));
  }

  /**
   * Runs a task on the server thread once the latest state is on disk, so what it does to the world
   * never runs ahead of the state that records it. If that save failed, the task doesn't run.
   */
  public void afterSave(Runnable task) {
    var _ =
        lastSave.whenCompleteAsync(
            (saved, failure) -> {
              if (failure == null) {
                task.run();
              }
            },
            serverThread);
  }

  /** Changes the decorating window, and saves it. Nothing happens before {@code /season init}. */
  public void updateDecorating(java.util.function.UnaryOperator<Decorating> change) {
    state.ifPresent(current -> update(current.withDecorating(change.apply(current.decorating()))));
  }

  private Transition apply(SeasonEvent event) {
    Transition transition = SeasonMachine.apply(state, event, clock.instant());
    if (transition instanceof Changed changed) {
      update(changed.state());
    }
    return transition;
  }

  private void update(SeasonState next) {
    state = Optional.of(next);
    CompletableFuture<Void> save = store.save(next);
    lastSave = save;
    var _ =
        save.exceptionally(e -> {
          LOG.error("Can't save season.json", e);
          scheduler.runGlobal(() ->
              announcer.admins(
                  ADMIN_PERMISSION, "season.save-failed", "error", String.valueOf(e.getMessage())));
          return null;
        });
  }

  /**
   * Starts the end sequence once the latest state is on disk. If that save failed, nothing is
   * archived: a crash would otherwise find a phase on disk that doesn't match what was done.
   */
  private void endAfterSave() {
    int season = state.map(SeasonState::season).orElse(0);
    var _ =
        lastSave.whenCompleteAsync(
            (saved, failure) -> {
              if (failure == null) {
                runEndSequence();
              } else {
                announcer.admins(
                    ADMIN_PERMISSION, "end.not-saved", "season", Integer.toString(season));
              }
            },
            serverThread);
  }

  /** Applies the launch once the new season is on disk, then records that it finished. */
  private void launchAfterSave() {
    var _ =
        lastSave
            .thenComposeAsync(saved -> launcher.apply(state.orElseThrow()), serverThread)
            .whenCompleteAsync(
                (done, failure) -> {
                  if (failure != null) {
                    LOG.error("Launching Season {} stopped", seasonNumber(), failure);
                    announcer.admins(
                        ADMIN_PERMISSION,
                        "launch.failed",
                        "season",
                        seasonNumber(),
                        "error",
                        String.valueOf(failure.getMessage()));
                    return;
                  }
                  state.ifPresent(current -> update(current.withLaunchApplied()));
                },
                serverThread);
  }

  private void updateBar(Countdown running) {
    Duration remaining = Duration.between(clock.instant(), running.endsAt());
    float progress =
        running.length().isZero()
            ? 0f
            : Math.clamp((float) remaining.toMillis() / running.length().toMillis(), 0f, 1f);
    Message title =
        config
            .get()
            .messages()
            .get("end.bar", "season", seasonNumber(), "time", HumanTime.clock(remaining));
    if (bar == null) {
      bar = players.showProgressBar(title, progress);
    } else {
      bar.update(title, progress);
    }
  }

  private void hideBar() {
    if (bar != null) {
      bar.hide();
      bar = null;
    }
  }

  private Path seasonFile(int season) {
    return seasonsDirectory.resolve(season + ".yml");
  }

  private String seasonNumber() {
    return state.map(s -> Integer.toString(s.season())).orElse("?");
  }

  private static Boolean logFailure(String what, Throwable e) {
    LOG.error(what, e);
    return Boolean.FALSE;
  }
}
