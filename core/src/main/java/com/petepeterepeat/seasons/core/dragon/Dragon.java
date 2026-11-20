// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.dragon;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.DragonEvents;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.season.DragonState;
import com.petepeterepeat.seasons.core.season.DragonState.Egg;
import com.petepeterepeat.seasons.core.season.DragonState.Fight;
import com.petepeterepeat.seasons.core.season.DragonState.Kill;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.vault.Vault;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dragon egg every season (see Ender vault, museum and dragon egg in the spec): the
 * previously-killed flag cleared at launch, the damage each player deals the End's dragon, the
 * slayer of the season's first kill, the fanfare, and the egg taken off the exit portal and handed
 * to the slayer (questions 178 to 186).
 *
 * <p>Handing the egg over is one transaction: the state says the egg is being taken, the journal
 * has {@code EGG_DELIVERED}, the egg leaves the portal, it goes on the slayer's Returned page under
 * a reference ({@code egg-s3}), and the vault's own withdrawal gives it to them from there. A crash
 * anywhere is finished at startup: an egg the vault was given is done; otherwise it is taken off
 * the portal if it is still there, and put on the Returned page.
 */
public final class Dragon implements DragonEvents {
  /** How often the exit portal is checked for the egg after a kill, and for how long. */
  public static final Duration WATCH_EVERY = Duration.ofSeconds(1);

  public static final int WATCH_CHECKS = 30;

  /** How high over the exit portal the fireworks go off, and how many. */
  private static final double FIREWORK_HEIGHT = 12;

  private static final int FIREWORKS = 5;
  private static final Duration FIREWORK_GAP = Duration.ofMillis(600);

  /** How often the damage dealt is saved while a fight goes on. */
  private static final Duration SAVE_EVERY = Duration.ofSeconds(1);

  private static final Logger LOG = LoggerFactory.getLogger(Dragon.class);

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final Announcer announcer;
  private final AuditLog audit;
  private final Optional<Journal> journal;
  private final Optional<Vault> vault;
  private final VaultFiles vaults;
  private final SerialWriter writer;
  private final CrashPoints crash;
  private final Clock clock;
  private final Executor serverThread;
  private @Nullable SeasonService seasons;

  /** The fight as it goes, saved at most once a second. */
  private Optional<Fight> fight = Optional.empty();

  private boolean unsaved;
  private @Nullable ScheduledTask saver;
  private @Nullable ScheduledTask watcher;

  /**
   * @param journal without one, nothing moves: the egg is left on the portal
   * @param vault without one, the same
   */
  public Dragon(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      Announcer announcer,
      AuditLog audit,
      Optional<Journal> journal,
      Optional<Vault> vault,
      VaultFiles vaults,
      SerialWriter writer,
      CrashPoints crash,
      Clock clock) {
    this.platform = platform;
    this.config = config;
    this.announcer = announcer;
    this.audit = audit;
    this.journal = journal;
    this.vault = vault;
    this.vaults = vaults;
    this.writer = writer;
    this.crash = crash;
    this.clock = clock;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** Where the season state lives; before {@link #start}. */
  public void bind(SeasonService service) {
    this.seasons = service;
  }

  /**
   * At startup, once the season state is loaded: clears a flag launch couldn't, and finishes
   * handing over an egg a restart interrupted.
   */
  public void start() {
    Optional<SeasonState> current = state();
    fight = current.flatMap(s -> s.dragon().fight());
    saver = platform.scheduler().runGlobalRepeating(SAVE_EVERY, this::save);
    if (current.isEmpty()) {
      return;
    }
    if (current.get().dragon().flagPending()) {
      endLoaded();
    }
    Optional<Kill> kill = current.get().dragon().kill();
    if (kill.isPresent() && kill.get().slayer().isPresent()) {
      switch (kill.get().egg()) {
        case WAITING -> watch(current.get().season(), kill.get());
        case TAKING -> recoverTaking(current.get().season(), kill.get());
        default -> {}
      }
    }
  }

  public void stop() {
    save();
    if (saver != null) {
      saver.cancel();
      saver = null;
    }
    stopWatching();
  }

  /**
   * At launch, with {@code dragon.newEggEachSeason}: clears the flag so the next dragon drops an
   * egg. With the End not loaded, that waits until it loads.
   */
  public void launched() {
    if (!config.get().config().dragon().newEggEachSeason()) {
      return;
    }
    if (platform.dragonFight().setPreviouslyKilled(false)) {
      update(d -> d.withFlagPending(false));
      return;
    }
    update(d -> d.withFlagPending(true));
    announcer.admins(SeasonService.ADMIN_PERMISSION, "dragon.flag-pending");
  }

  @Override
  public void endLoaded() {
    boolean pending = state().map(s -> s.dragon().flagPending()).orElse(false);
    if (pending && platform.dragonFight().setPreviouslyKilled(false)) {
      LOG.info("The End loaded: cleared the dragon's previously-killed flag, as launch asked");
      update(d -> d.withFlagPending(false));
    }
  }

  @Override
  public void hurt(UUID dragon, PlayerId player, double dealt) {
    if (!counting() || dealt <= 0) {
      return;
    }
    Fight now =
        fight.filter(f -> f.dragon().equals(dragon)).orElseGet(() -> new Fight(dragon, List.of()));
    fight = Optional.of(now.hit(player, dealt));
    unsaved = true;
  }

  @Override
  public void died(UUID dragon, Optional<PlayerId> finalBlow) {
    if (!counting()) {
      return;
    }
    SeasonState current = state().orElseThrow();
    int season = current.season();
    Optional<Fight> fought = fight.filter(f -> f.dragon().equals(dragon));
    Optional<PlayerId> slayer = finalBlow.or(() -> fought.flatMap(Fight::mostDamage));
    fight = Optional.empty();
    unsaved = false;
    if (slayer.isEmpty()) {
      // No player had a hand in it: the egg stays on the portal, as in vanilla.
      update(d ->
          d.withKill(
              new Kill(
                  Optional.empty(),
                  "",
                  clock.instant(),
                  Egg.PORTAL,
                  Optional.empty(),
                  Optional.empty())));
      audit.record(AuditLog.SEASONS, "dragon", "Season " + season, "slain", "by no player");
      return;
    }
    PlayerId who = slayer.get();
    String name = name(who);
    Egg egg;
    if (platform.dragonFight().previouslyKilled()) {
      egg = Egg.NONE;
    } else if (config.get().config().dragon().eggTo() == SeasonsConfig.EggTo.PORTAL
        || journal.isEmpty()
        || vault.isEmpty()) {
      egg = Egg.PORTAL;
    } else {
      egg = Egg.WAITING;
    }
    Optional<Position> before =
        egg == Egg.WAITING ? platform.dragonFight().eggOnPortal() : Optional.empty();
    Kill kill = new Kill(slayer, name, clock.instant(), egg, before, Optional.empty());
    update(d -> d.withKill(kill));
    seasons().addFirst(new SeasonFirst(season, FirstsTracker.DRAGON, "", who, name, kill.at()));
    audit.record(
        AuditLog.SEASONS,
        "dragon",
        name,
        "slain",
        "Season " + season + (finalBlow.isPresent() ? ", final blow" : ", most damage"));
    fanfare(name, season);
    if (egg == Egg.WAITING) {
      watch(season, kill);
    }
  }

  /** Damage counts while the season is played, until its first kill. */
  private boolean counting() {
    Optional<SeasonState> current = state();
    return current.isPresent()
        && current.get().phase() instanceof Phase.Active
        && current.get().dragon().kill().isEmpty();
  }

  /**
   * A title and sound for everyone online, fireworks over the exit portal, a chat broadcast and a
   * Discord post.
   */
  private void fanfare(String name, int season) {
    Messages messages = config.get().messages();
    for (PlayerRef online : platform.players().online()) {
      platform
          .players()
          .celebrate(
              online.id(),
              messages.get("dragon.title"),
              messages.get("dragon.subtitle", "name", name));
    }
    platform
        .dragonFight()
        .exitPortal()
        .ifPresent(portal -> {
          Position above =
              Position.of(
                  portal.dimension(),
                  portal.blockX() + 0.5,
                  portal.blockY() + FIREWORK_HEIGHT,
                  portal.blockZ() + 0.5);
          for (int i = 0; i < FIREWORKS; i++) {
            platform
                .scheduler()
                .runGlobalLater(
                    FIREWORK_GAP.multipliedBy(i), () -> platform.players().fireworks(above));
          }
        });
    announcer.broadcast("dragon.slain", "name", name);
    announcer.discord("dragon.discord", "name", name, "season", Integer.toString(season));
  }

  /** Checks the exit portal once a second for the new egg, for up to 30 seconds. */
  private void watch(int season, Kill kill) {
    stopWatching();
    int[] checks = {0};
    watcher =
        platform
            .scheduler()
            .runGlobalRepeating(
                WATCH_EVERY,
                () -> {
                  Optional<Kill> now = state().flatMap(s -> s.dragon().kill());
                  if (state().map(SeasonState::season).orElse(0) != season
                      || now.isEmpty()
                      || now.get().egg() != Egg.WAITING) {
                    stopWatching();
                    return;
                  }
                  Optional<Position> egg =
                      platform
                          .dragonFight()
                          .eggOnPortal()
                          .filter(at -> !kill.eggBefore().map(b -> same(b, at)).orElse(false));
                  if (egg.isPresent()) {
                    stopWatching();
                    take(season, now.get(), egg.get());
                  } else if (++checks[0] >= WATCH_CHECKS) {
                    stopWatching();
                    update(d -> d.withKill(now.get().withEgg(Egg.MISSING, Optional.empty())));
                    audit.record(
                        AuditLog.SEASONS, "egg", kill.name(), "missing", "Season " + season);
                    announcer.admins(
                        SeasonService.ADMIN_PERMISSION,
                        "dragon.egg-missing",
                        "season",
                        Integer.toString(season),
                        "name",
                        kill.name());
                  }
                });
  }

  private void stopWatching() {
    if (watcher != null) {
      watcher.cancel();
      watcher = null;
    }
  }

  /** The egg is on the portal: records that it is being taken, journals, then takes it. */
  private void take(int season, Kill kill, Position at) {
    Kill taking = kill.withEgg(Egg.TAKING, Optional.of(at));
    update(d -> d.withKill(taking));
    PlayerId slayer = taking.slayer().orElseThrow();
    seasons()
        .afterSave(() -> {
          var _ =
              journal
                  .orElseThrow()
                  .append(JournalKind.EGG_DELIVERED, slayer, season, payload(season, at))
                  .thenRunAsync(() -> clearAndReturn(season, taking, at), serverThread)
                  .exceptionally(failure -> {
                    LOG.error("Handing over Season {}'s dragon egg stopped", season, failure);
                    return null;
                  });
        });
  }

  /**
   * Takes the egg off the portal, if it is still there, and puts it on the slayer's Returned page,
   * then gives it to them if they are online. Run again by recovery, it changes nothing done
   * already.
   */
  private void clearAndReturn(int season, Kill kill, Position at) {
    PlayerId slayer = kill.slayer().orElseThrow();
    boolean removed = platform.dragonFight().removeEgg(at);
    crash.reached(CrashPoint.EGG_CLEARED, slayer);
    var _ =
        writer
            .submit(() ->
                vaults.addReturned(
                    slayer,
                    platform.dragonFight().eggItem(),
                    clock.instant(),
                    Optional.of(ref(season))))
            .thenRunAsync(
                () -> {
                  update(d -> d.withKill(kill.withEgg(Egg.RETURNED, Optional.empty())));
                  audit.record(
                      AuditLog.SEASONS,
                      "egg",
                      kill.name(),
                      "returned",
                      "Season "
                          + season
                          + (removed ? ": taken off the portal" : ": already off the portal"));
                  seasons().afterSave(() -> handOver(season, kill));
                },
                serverThread)
            .exceptionally(failure -> {
              LOG.error("Can't put Season {}'s dragon egg on the Returned page", season, failure);
              return null;
            });
  }

  /** Gives the egg from the Returned page, if the slayer is online and has room. */
  private void handOver(int season, Kill kill) {
    PlayerId slayer = kill.slayer().orElseThrow();
    crash.reached(CrashPoint.EGG_RETURNED, slayer);
    if (vault.isEmpty() || platform.players().find(slayer).isEmpty()) {
      return;
    }
    var _ =
        vault
            .get()
            .deliver(slayer, ref(season), "dragon.egg-given", "dragon.egg-returned")
            .thenAcceptAsync(
                given -> {
                  if (given && state().map(SeasonState::season).orElse(0) == season) {
                    update(d -> d.withKill(kill.withEgg(Egg.GIVEN, Optional.empty())));
                  }
                },
                serverThread);
  }

  /** An egg being taken when the server stopped: finished as far as it got. */
  private void recoverTaking(int season, Kill kill) {
    PlayerId slayer = kill.slayer().orElseThrow();
    var _ =
        writer
            .submit(() -> vaults.delivered(slayer, ref(season)))
            .thenAcceptAsync(
                delivered -> {
                  LOG.warn("Finishing handing over Season {}'s dragon egg after a restart", season);
                  if (delivered) {
                    update(d -> d.withKill(kill.withEgg(Egg.RETURNED, Optional.empty())));
                  } else {
                    clearAndReturn(season, kill, kill.eggAt().orElseThrow());
                  }
                },
                serverThread)
            .exceptionally(failure -> {
              LOG.error("Can't finish handing over Season {}'s dragon egg", season, failure);
              return null;
            });
  }

  /** Saves the damage dealt, if it changed. */
  private void save() {
    if (unsaved && counting()) {
      Optional<Fight> now = fight;
      update(d -> d.withFight(now));
    }
    unsaved = false;
  }

  private void update(java.util.function.UnaryOperator<DragonState> change) {
    seasons().updateDragon(change);
  }

  private Optional<SeasonState> state() {
    return seasons == null ? Optional.empty() : seasons.state();
  }

  private SeasonService seasons() {
    if (seasons == null) {
      throw new IllegalStateException("Dragon used before bind");
    }
    return seasons;
  }

  private String name(PlayerId player) {
    return platform
        .players()
        .find(player)
        .map(PlayerRef::name)
        .or(() -> platform.players().knownName(player))
        .orElse(player.toString());
  }

  /** What the vault remembers the season's egg by. */
  static String ref(int season) {
    return "egg-s" + season;
  }

  private static String payload(int season, Position at) {
    return String.format(
        Locale.ROOT,
        "s%d@%s/%d,%d,%d",
        season,
        at.dimension(),
        at.blockX(),
        at.blockY(),
        at.blockZ());
  }

  private static boolean same(Position a, Position b) {
    return a.dimension().equals(b.dimension())
        && a.blockX() == b.blockX()
        && a.blockY() == b.blockY()
        && a.blockZ() == b.blockZ();
  }
}
