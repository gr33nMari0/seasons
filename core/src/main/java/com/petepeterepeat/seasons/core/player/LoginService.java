// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import com.petepeterepeat.seasons.core.archive.Archiver;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Scheduler;
import com.petepeterepeat.seasons.core.port.Sessions;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joins and leaves: finishes anything a crash left open for the player, then follows their {@link
 * LoginPlan} (archive, the new spawn, their cell), and keeps their record's play time.
 */
public final class LoginService implements Sessions {
  private static final Logger LOG = LoggerFactory.getLogger(LoginService.class);

  private final Supplier<Optional<SeasonState>> state;
  private final PlayerStore store;
  private final JournalRecovery recovery;
  private final Archiver archiver;
  private final SeasonEntry entry;
  private final Clock clock;
  private final Executor serverThread;
  private final Map<PlayerId, Instant> joinedAt = new ConcurrentHashMap<>();

  public LoginService(
      Supplier<Optional<SeasonState>> state,
      PlayerStore store,
      JournalRecovery recovery,
      Archiver archiver,
      SeasonEntry entry,
      Scheduler scheduler,
      Clock clock) {
    this.state = state;
    this.store = store;
    this.recovery = recovery;
    this.archiver = archiver;
    this.entry = entry;
    this.clock = clock;
    this.serverThread = scheduler::runGlobal;
  }

  @Override
  public void joined(PlayerRef player, boolean playedBefore) {
    joinedAt.put(player.id(), clock.instant());
    if (state.get().isEmpty()) {
      // Seasons isn't in use until /season init: nobody gets a record before then.
      return;
    }
    var _ =
        recovery
            .retry(player.id())
            .exceptionally(e -> 0)
            .thenComposeAsync(finished -> follow(player, playedBefore), serverThread)
            .exceptionally(e -> {
              LOG.error("Login catch-up for {} stopped", player.name(), e);
              return null;
            });
  }

  @Override
  public void left(PlayerId player) {
    Instant joined = joinedAt.remove(player);
    Optional<SeasonState> current = state.get();
    if (joined == null || current.isEmpty() || !isPlaying(current.get().phase())) {
      return;
    }
    store
        .find(player)
        .ifPresent(record ->
            store.put(
                record.withPlay(
                    current.get().season(),
                    clock.instant(),
                    Duration.between(joined, clock.instant()))));
  }

  /**
   * Marks everyone online as playing the current season, such as right after {@code /season init},
   * when they joined before Seasons was in use.
   */
  public void markPlaying(Collection<PlayerRef> online) {
    Optional<SeasonState> current = state.get();
    if (current.isEmpty() || !isPlaying(current.get().phase())) {
      return;
    }
    for (PlayerRef player : online) {
      store.put(
          store
              .getOrCreate(player.id(), player.name())
              .withName(player.name())
              .withPlay(current.get().season(), clock.instant(), Duration.ZERO));
    }
  }

  /** What a login leads to, once any recovery has run. */
  CompletableFuture<Void> follow(PlayerRef player, boolean playedBefore) {
    Optional<SeasonState> found = state.get();
    if (found.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    SeasonState current = found.get();
    PlayerId id = player.id();
    LoginPlan.Plan plan = LoginPlan.decide(store.find(id), playedBefore, current);
    LOG.info("{} logged in: {}", player.name(), plan);
    PlayerRecord record = store.getOrCreate(id, player.name()).withName(player.name());
    if (isPlaying(current.phase()) && !(plan instanceof LoginPlan.Plan.Archive)) {
      record = record.withPlay(current.season(), clock.instant(), Duration.ZERO);
    }
    store.put(record);
    return switch (plan) {
      case LoginPlan.Plan.Archive archive ->
          archiveAndMove(player.id(), archive).thenApply(outcome -> null);
      case LoginPlan.Plan.EnterSeason enter ->
          archiver
              .followUp(id, Archiver.Destination.STAY)
              .thenCompose(done -> entry.enter(id, enter.season()))
              .thenApply(done -> null);
      case LoginPlan.Plan.ToCell cell -> archiver.followUp(id, Archiver.Destination.CELL);
      case LoginPlan.Plan.NewPlayer newPlayer -> {
        if (isPlaying(current.phase()) && current.launchedAt().isPresent()) {
          yield entry.enter(id, newPlayer.season()).thenApply(done -> null);
        }
        if (isPlaying(current.phase())) {
          entry.takeBaseline(id, newPlayer.season());
        }
        yield CompletableFuture.completedFuture(null);
      }
      case LoginPlan.Plan.Play play -> archiver.followUp(id, Archiver.Destination.STAY);
    };
  }

  /**
   * Archives a player into the season their plan names, then sends them on: to the new spawn if the
   * next season has launched, otherwise to their cell. For logins and {@code /season archive}.
   */
  public CompletableFuture<Archiver.Outcome> archiveAndMove(
      PlayerId id, LoginPlan.Plan.Archive archive) {
    int current = state.get().map(SeasonState::season).orElse(archive.season());
    return archiver
        .archive(id, archive.season(), AuditLog.SEASONS)
        .thenComposeAsync(
            outcome -> {
              boolean archived =
                  outcome instanceof Archiver.Outcome.Archived
                      || outcome instanceof Archiver.Outcome.AlreadyArchived;
              if (!archived) {
                return CompletableFuture.completedFuture(outcome);
              }
              CompletableFuture<?> moved =
                  archive.thenEnter()
                      ? archiver
                          .followUp(id, Archiver.Destination.STAY)
                          .thenCompose(done -> entry.enter(id, current))
                      : archiver.followUp(id, Archiver.Destination.CELL);
              return moved.thenApply(done -> outcome);
            },
            serverThread);
  }

  /** Active, or counting down to the end: the season is still being played. */
  static boolean isPlaying(Phase phase) {
    return phase instanceof Phase.Active || phase instanceof Phase.Countdown;
  }
}
