// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.Archiver;
import com.petepeterepeat.seasons.core.archive.Unarchiver;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.ConfigProblem;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.guard.Immunity;
import com.petepeterepeat.seasons.core.guard.IntentRouter;
import com.petepeterepeat.seasons.core.guard.Tripwire;
import com.petepeterepeat.seasons.core.hall.Hall;
import com.petepeterepeat.seasons.core.hall.HallAudit;
import com.petepeterepeat.seasons.core.hall.HallCommands;
import com.petepeterepeat.seasons.core.hall.HallImporter;
import com.petepeterepeat.seasons.core.hall.HallRenderer;
import com.petepeterepeat.seasons.core.hall.HallRules;
import com.petepeterepeat.seasons.core.hall.HallService;
import com.petepeterepeat.seasons.core.hall.HallVisits;
import com.petepeterepeat.seasons.core.hall.Reconciler;
import com.petepeterepeat.seasons.core.hall.SeasonCard;
import com.petepeterepeat.seasons.core.player.FurthestTracker;
import com.petepeterepeat.seasons.core.player.LoginService;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.player.SeasonEntry;
import com.petepeterepeat.seasons.core.port.Milestones;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Sessions;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.LaunchSequence;
import com.petepeterepeat.seasons.core.season.RolloverArchiver;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.SeasonStore;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.Operation;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.zone.ZoneCommands;
import com.petepeterepeat.seasons.core.zone.Zones;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Seasons core. An adapter builds its {@link Platform} and starts one of these. */
public final class Seasons implements SeasonsApi {
  /** Permission for season admin commands such as {@code /season status}. */
  public static final String PERMISSION_ADMIN_SEASON = "seasons.admin.season";

  /** Permission for {@code /season reload} and the other config commands. */
  public static final String PERMISSION_ADMIN_CONFIG = "seasons.admin.config";

  /** Permission for {@code /season info}. */
  public static final String PERMISSION_INFO = "seasons.info";

  private static final Logger LOG = LoggerFactory.getLogger(Seasons.class);

  private final Platform platform;
  private final String version;
  private final Clock clock;
  private final Webhooks webhooks;
  private final Map<String, String> environment;
  private final SerialWriter writer;
  private final Optional<ScenarioHooks> hooks;
  private final ConfigHolder config = new ConfigHolder(ConfigLoader.defaults());

  private @Nullable SeasonService service;
  private @Nullable SeasonCommands commands;
  private @Nullable PlayerStore store;
  private @Nullable Tripwire tripwire;
  private @Nullable FirstsTracker firsts;

  /** How long after an admin joins they hear about galleries left out at startup. */
  private static final java.time.Duration LEFT_OUT_NOTICE_DELAY = java.time.Duration.ofSeconds(3);

  private @Nullable ScheduledTask flusher;
  private @Nullable HallService hallService;
  private @Nullable HallVisits visits;
  private @Nullable Reconciler reconciler;
  private @Nullable HallRules rules;
  private @Nullable Zones zones;
  private @Nullable RolloverArchiver archiverForTests;
  private LongSupplier nanoTime = System::nanoTime;

  /**
   * @param environment environment variables, for the webhook overrides
   * @param writer the file writer thread; Seasons closes it at {@link #stop}
   * @param hooks the scenario hooks, only on a scenario server (docs/adr/0003)
   */
  public Seasons(
      Platform platform,
      String version,
      Clock clock,
      Webhooks webhooks,
      Map<String, String> environment,
      SerialWriter writer,
      Optional<ScenarioHooks> hooks) {
    this.platform = platform;
    this.version = version;
    this.clock = clock;
    this.webhooks = webhooks;
    this.environment = Map.copyOf(environment);
    this.writer = writer;
    this.hooks = hooks;
  }

  /**
   * Loads config, state and player records, replays the journal, registers commands and listeners,
   * and starts the season lifecycle. Runs on the server thread while the plugin enables.
   */
  public void start() {
    Path world = platform.storage().worldDataDirectory();
    Executor serverThread = platform.scheduler()::runGlobal;
    ConfigLoader loader =
        new ConfigLoader(
            platform.storage().configDirectory(),
            world,
            environment,
            platform.world()::dimensionExists);
    List<ConfigProblem> startupProblems = loadConfig(loader);

    Journal journal = null;
    Optional<String> journalError = Optional.empty();
    try {
      journal = Journal.open(world.resolve("data/journal.log"), writer, clock);
    } catch (IOException | RuntimeException e) {
      journalError = Optional.of(String.valueOf(e.getMessage()));
      LOG.error("Can't open the journal; nobody can be archived until it is fixed", e);
    }

    PlayerStore players = new PlayerStore(world.resolve("data/players"), writer);
    store = players;
    try {
      int loaded = players.loadAll().join();
      LOG.info("Loaded {} player record(s)", loaded);
    } catch (RuntimeException e) {
      LOG.error("Can't read the player records", e);
    }
    ArchiveFiles archives = new ArchiveFiles(world.resolve("data/archives"));
    VaultFiles vaults = new VaultFiles(world.resolve("data/vaults"));
    AuditLog audit = new AuditLog(world.resolve("audit"), writer, clock);
    ArchiveHold hold = new ArchiveHold();
    Immunity immunity = new Immunity(clock);
    Announcer announcer =
        new Announcer(platform.players(), platform.permissions(), webhooks, config);
    CrashPoints crash = hooks.<CrashPoints>map(h -> h).orElse(CrashPoints.NONE);
    SeasonEntry entry = new SeasonEntry(platform, config, players, immunity, clock);
    HallService halls =
        new HallService(
            platform,
            config,
            loader::galleryFile,
            world.resolve("backups"),
            writer,
            clock,
            players,
            archives,
            announcer,
            RandomGenerator.getDefault());
    hallService = halls;
    HallVisits visiting =
        new HallVisits(platform, config, halls, players, immunity, clock, entry::spawn);
    visits = visiting;
    HallRenderer renderer = new HallRenderer(platform, config, writer, archives, halls);
    Hall hall = halls.hall(renderer);
    Reconciler reconciling =
        new Reconciler(platform, halls, renderer, archives, writer, audit, nanoTime);
    reconciler = reconciling;
    HallRules hallRules =
        new HallRules(platform, config, halls, new HallAudit(audit, clock), reconciling);
    rules = hallRules;
    Optional<HallImporter> importer = Optional.empty();

    Optional<Archiver> archiver = Optional.empty();
    Optional<Unarchiver> unarchiver = Optional.empty();
    Optional<JournalRecovery> recovery = Optional.empty();
    RolloverArchiver rollover = refusingArchiver();
    if (journal != null) {
      JournalRecovery replay = new JournalRecovery(journal, serverThread);
      Archiver archiving =
          new Archiver(
              platform, config, clock, writer, journal, replay, archives, vaults, players, hall,
              audit, announcer, hold, immunity, crash);
      Unarchiver unarchiving =
          new Unarchiver(
              platform, clock, writer, journal, archives, vaults, players, hall, audit, hold);
      replay.register(Operation.ARCHIVE, archiving.recovery());
      replay.register(Operation.UNARCHIVE, unarchiving.recovery());
      HallImporter importing =
          new HallImporter(
              platform, writer, journal, archives, players, halls, renderer, audit, crash, clock,
              world);
      replay.register(Operation.IMPORT, importing.recovery());
      replay.register(Operation.IMPORT_UNDO, importing.undoRecovery());
      importer = Optional.of(importing);
      var _ = replay.recoverAll();
      archiver = Optional.of(archiving);
      unarchiver = Optional.of(unarchiving);
      recovery = Optional.of(replay);
      rollover = archiving;
    }

    LaunchSequence launch =
        new LaunchSequence(
            platform,
            config,
            announcer,
            entry,
            hold::held,
            unsettled(players, recovery),
            Optional.ofNullable(journal));
    SeasonService seasons =
        new SeasonService(
            platform.players(),
            platform.scheduler(),
            clock,
            new SeasonStore(world.resolve("data/season.json"), writer),
            writer,
            config,
            announcer,
            archiverForTests != null ? archiverForTests : rollover,
            launch,
            world.resolve("seasons"),
            world.resolve("backups"));
    service = seasons;
    Zones zoning =
        new Zones(
            platform, config, announcer, audit, clock, halls::inHall, entry::spawn, hold::isHeld);
    zones = zoning;
    zoning.bind(seasons);
    seasons.start();
    halls.bind(seasons::state, seasons::updateDecorating);
    halls.start();
    visiting.bind(seasons::state);
    visiting.start();
    reconciling.start();
    hallRules.start();
    zoning.start();

    FurthestTracker furthest =
        new FurthestTracker(seasons::state, config, players, hall.bounds(), platform.world());
    Optional<JournalRecovery> replaying = recovery;
    Optional<LoginService> login =
        archiver.map(a ->
            new LoginService(
                seasons::state,
                players,
                replaying.orElseThrow(),
                a,
                hold,
                entry,
                platform.scheduler(),
                clock));
    Tripwire wire =
        new Tripwire(platform.players(), platform.inventories(), platform.scheduler(), audit);
    tripwire = wire;
    wire.start();
    FirstsTracker firstsTracker =
        new FirstsTracker(
            platform.players(),
            platform.inventories(),
            platform.scheduler(),
            seasons::state,
            seasons::addFirst,
            hold::isHeld,
            config,
            announcer,
            clock);
    firsts = firstsTracker;
    firstsTracker.start();
    platform.events().milestones(both(firstsTracker, zoning));
    flusher =
        platform
            .scheduler()
            .runGlobalRepeating(PlayerStore.COALESCE, () -> logFailure(players.flush()));

    SeasonCommands built =
        new SeasonCommands(
            platform,
            version,
            clock,
            seasons,
            announcer,
            config,
            loader,
            writer,
            Optional.ofNullable(journal),
            journalError,
            startupProblems,
            new SeasonCommands.Services(
                archiver, unarchiver, recovery, players, furthest, login, archives, vaults, halls));
    commands = built;
    platform
        .events()
        .handle(
            new IntentRouter(
                hold,
                immunity,
                furthest,
                intent ->
                    visiting
                        .decide(intent)
                        .or(() -> halls.decide(intent))
                        .or(() -> hallRules.decide(intent))
                        .or(() -> zoning.decide(intent)),
                visiting::damaged));
    platform.events().sessions(sessions(login));
    platform.events().tripwire(wire);
    platform.events().hall(hallRules);
    platform.events().zones(zoning);
    platform
        .commands()
        .register(
            built.root(
                new ZoneCommands(
                        platform,
                        config,
                        loader,
                        writer,
                        seasons,
                        zoning,
                        audit,
                        world.resolve("backups").resolve("seasons"))
                    .nodes()));
    platform
        .commands()
        .register(
            new HallCommands(
                    platform, config, halls, visiting, renderer, reconciling, importer, players)
                .root(),
            List.of("halloffame"));
    login.ifPresent(l -> l.markPlaying(platform.players().online()));

    if (hooks.isPresent() && recovery.isPresent()) {
      hooks
          .get()
          .wire(
              new ScenarioHooks.Wiring(
                  archives,
                  vaults,
                  players,
                  recovery.get(),
                  platform.menus(),
                  writer,
                  config,
                  serverThread,
                  (player, season) ->
                      halls
                          .card(player, season)
                          .thenApply(card -> card.map(SeasonCard::plainLines).orElse(List.of()))));
    }
  }

  /**
   * Joins and leaves go to login catch-up, if the journal could be opened; admins who join are also
   * told about any gallery left out at startup.
   */
  private Sessions sessions(Optional<LoginService> login) {
    return new Sessions() {
      @Override
      public void arriving(PlayerId player) {
        login.ifPresent(l -> l.arriving(player));
      }

      @Override
      public void joined(PlayerRef player, boolean playedBefore) {
        login.ifPresent(l -> l.joined(player, playedBefore));
        // A moment after the join messages, so it isn't lost among them.
        platform
            .scheduler()
            .runGlobalLater(LEFT_OUT_NOTICE_DELAY, () -> tellAboutLeftOutGalleries(player));
      }

      private void tellAboutLeftOutGalleries(PlayerRef player) {
        ConfigSnapshot snapshot = config.get();
        if (platform.players().find(player.id()).isPresent()
            && snapshot.anyGalleryLeftOut()
            && platform.permissions().has(player.id(), SeasonService.ADMIN_PERMISSION)) {
          snapshot
              .galleriesLeftOut()
              .forEach((gallery, problems) ->
                  problems.forEach(problem ->
                      platform
                          .players()
                          .sendMessage(
                              player.id(),
                              SeasonCommands.galleryLeftOut(
                                  snapshot.messages(), gallery, problem))));
        }
      }

      @Override
      public void left(PlayerId player) {
        login.ifPresent(l -> l.left(player));
      }
    };
  }

  /** Milestones for season firsts and for the zones' unlock conditions. */
  private static Milestones both(Milestones first, Milestones second) {
    return new Milestones() {
      @Override
      public void advancementDone(PlayerId player, String advancement, String title) {
        first.advancementDone(player, advancement, title);
        second.advancementDone(player, advancement, title);
      }

      @Override
      public void enteredDimension(PlayerId player, String dimension) {
        first.enteredDimension(player, dimension);
        second.enteredDimension(player, dimension);
      }

      @Override
      public void dragonKilled() {
        first.dragonKilled();
        second.dragonKilled();
      }
    };
  }

  /** Times reconciliation's budget with this clock; for tests, before {@link #start}. */
  void useNanoTime(LongSupplier clock) {
    this.nanoTime = clock;
  }

  /** Ends seasons with this archiver instead of the real one; for tests, before {@link #start}. */
  void useArchiver(RolloverArchiver archiver) {
    this.archiverForTests = archiver;
  }

  /** Stops the lifecycle and finishes queued writes and posts. */
  public void stop() {
    if (service != null) {
      service.stop();
    }
    if (hallService != null) {
      hallService.stop();
    }
    if (visits != null) {
      visits.stop();
    }
    if (reconciler != null) {
      reconciler.stop();
    }
    if (rules != null) {
      rules.stop();
    }
    if (tripwire != null) {
      tripwire.stop();
    }
    if (zones != null) {
      zones.stop();
    }
    if (firsts != null) {
      firsts.stop();
    }
    if (flusher != null) {
      flusher.cancel();
    }
    if (store != null) {
      logFailure(store.flush());
    }
    writer.close();
    webhooks.close();
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public OptionalInt currentSeason() {
    Optional<SeasonState> state = service == null ? Optional.empty() : service.state();
    return state.map(s -> OptionalInt.of(s.season())).orElse(OptionalInt.empty());
  }

  /** The command handlers, once started. */
  Optional<SeasonCommands> commands() {
    return Optional.ofNullable(commands);
  }

  /** Player records, once started. */
  Optional<PlayerStore> players() {
    return Optional.ofNullable(store);
  }

  /** With no journal, nothing may move: the end sequence stops and admins are told why. */
  private static RolloverArchiver refusingArchiver() {
    return new RolloverArchiver() {
      @Override
      public CompletableFuture<Void> lockGalleries(int season) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("the journal is unavailable, so nobody can be archived"));
      }

      @Override
      public CompletableFuture<Void> archive(PlayerId player, int season) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("the journal is unavailable, so nobody can be archived"));
      }
    };
  }

  /**
   * For launch: whether a player who played the season that ended isn't archived into it, or has an
   * archive or unarchive still open in the journal.
   */
  private static BiPredicate<PlayerId, Integer> unsettled(
      PlayerStore players, Optional<JournalRecovery> recovery) {
    return (player, ended) ->
        recovery.map(r -> !r.open(player).isEmpty()).orElse(false)
            || players
                .find(player)
                .map(r -> r.played(ended) && r.archivedThrough() < ended)
                .orElse(false);
  }

  private static void logFailure(CompletableFuture<?> future) {
    var _ =
        future.exceptionally(e -> {
          LOG.error("Saving player records failed", e);
          return null;
        });
  }

  private List<ConfigProblem> loadConfig(ConfigLoader loader) {
    try {
      loader.writeDefaults();
    } catch (IOException e) {
      LOG.error("Can't write the default config files", e);
    }
    ConfigLoader.Result result = loader.loadAtStartup();
    result.warnings().forEach(warning -> LOG.warn("{}", warning));
    if (result.snapshot().isPresent()) {
      config.set(result.snapshot().get());
      result
          .snapshot()
          .get()
          .galleriesLeftOut()
          .forEach((gallery, problems) ->
              problems.forEach(problem ->
                  LOG.error(
                      "Gallery {} is left out until it is fixed and /season reload succeeds;"
                          + " /season end and /season launch are refused until then: {}",
                      gallery,
                      problem)));
      return List.of();
    }
    for (ConfigProblem problem : result.problems()) {
      LOG.error("Config problem, using the bundled defaults: {}", problem);
    }
    return result.problems();
  }
}
