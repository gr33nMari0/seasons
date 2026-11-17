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
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.guard.Immunity;
import com.petepeterepeat.seasons.core.guard.IntentRouter;
import com.petepeterepeat.seasons.core.guard.Tripwire;
import com.petepeterepeat.seasons.core.hall.Hall;
import com.petepeterepeat.seasons.core.hall.HallCommands;
import com.petepeterepeat.seasons.core.hall.HallImporter;
import com.petepeterepeat.seasons.core.hall.HallRenderer;
import com.petepeterepeat.seasons.core.hall.HallService;
import com.petepeterepeat.seasons.core.player.FurthestTracker;
import com.petepeterepeat.seasons.core.player.LoginService;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.player.SeasonEntry;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.season.LaunchSequence;
import com.petepeterepeat.seasons.core.season.RolloverArchiver;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.SeasonStore;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.Operation;
import com.petepeterepeat.seasons.core.store.SerialWriter;
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
  private @Nullable ScheduledTask flusher;
  private @Nullable HallService hallService;
  private @Nullable RolloverArchiver archiverForTests;

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
        new ConfigLoader(platform.storage().configDirectory(), world, environment);
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
    HallRenderer renderer = new HallRenderer(platform, config, writer, archives, halls);
    Hall hall = halls.hall(renderer);
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
              platform,
              writer,
              journal,
              archives,
              players,
              halls,
              renderer,
              audit,
              crash,
              clock,
              world.resolve("seasons"));
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
    seasons.start();
    halls.bind(seasons::state, seasons::updateDecorating);
    halls.start();

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
    platform.events().handle(new IntentRouter(hold, immunity, furthest, halls::decide));
    login.ifPresent(platform.events()::sessions);
    platform.events().tripwire(wire);
    platform.commands().register(built.root());
    platform
        .commands()
        .register(new HallCommands(platform, config, halls, renderer, importer, players).root());
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
                  serverThread));
    }
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
    if (tripwire != null) {
      tripwire.stop();
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
    ConfigLoader.Result result = loader.load();
    result.warnings().forEach(warning -> LOG.warn("{}", warning));
    if (result.snapshot().isPresent()) {
      config.set(result.snapshot().get());
      return List.of();
    }
    for (ConfigProblem problem : result.problems()) {
      LOG.error("Config problem, using the bundled defaults: {}", problem);
    }
    return result.problems();
  }
}
