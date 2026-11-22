// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.announce.AdminAlerts;
import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.Archiver;
import com.petepeterepeat.seasons.core.archive.Unarchiver;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigCommands;
import com.petepeterepeat.seasons.core.config.ConfigEdits;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.ConfigProblem;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.curate.CurateFiles;
import com.petepeterepeat.seasons.core.curate.Curation;
import com.petepeterepeat.seasons.core.dragon.Dragon;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.guard.Immunity;
import com.petepeterepeat.seasons.core.guard.IntentRouter;
import com.petepeterepeat.seasons.core.guard.Tripwire;
import com.petepeterepeat.seasons.core.hall.CellEdits;
import com.petepeterepeat.seasons.core.hall.CellFixes;
import com.petepeterepeat.seasons.core.hall.CellRows;
import com.petepeterepeat.seasons.core.hall.Gates;
import com.petepeterepeat.seasons.core.hall.Hall;
import com.petepeterepeat.seasons.core.hall.HallAudit;
import com.petepeterepeat.seasons.core.hall.HallCommands;
import com.petepeterepeat.seasons.core.hall.HallImporter;
import com.petepeterepeat.seasons.core.hall.HallLists;
import com.petepeterepeat.seasons.core.hall.HallOutlines;
import com.petepeterepeat.seasons.core.hall.HallRenderer;
import com.petepeterepeat.seasons.core.hall.HallRules;
import com.petepeterepeat.seasons.core.hall.HallService;
import com.petepeterepeat.seasons.core.hall.HallVisits;
import com.petepeterepeat.seasons.core.hall.Museum;
import com.petepeterepeat.seasons.core.hall.MuseumFiles;
import com.petepeterepeat.seasons.core.hall.PlaqueScale;
import com.petepeterepeat.seasons.core.hall.Reconciler;
import com.petepeterepeat.seasons.core.hall.SeasonCard;
import com.petepeterepeat.seasons.core.hall.Stamper;
import com.petepeterepeat.seasons.core.hall.TemplateLibrary;
import com.petepeterepeat.seasons.core.hall.Wand;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.map.MapService;
import com.petepeterepeat.seasons.core.map.MarkerCommands;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.pet.Pets;
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
import com.petepeterepeat.seasons.core.season.Awards;
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
import com.petepeterepeat.seasons.core.vault.Vault;
import com.petepeterepeat.seasons.core.zone.ZoneCommands;
import com.petepeterepeat.seasons.core.zone.Zones;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
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
  private final MapPublisher maps;
  private final Map<String, String> environment;
  private final SerialWriter writer;
  private final Optional<ScenarioHooks> hooks;
  private final ConfigHolder config = new ConfigHolder(ConfigLoader.defaults());

  private @Nullable SeasonService service;
  private @Nullable SeasonCommands commands;
  private @Nullable CommandHelp commandHelp;
  private @Nullable PlayerStore store;
  private @Nullable Tripwire tripwire;
  private @Nullable FirstsTracker firsts;
  private @Nullable Pets pets;
  private @Nullable Dragon dragon;
  private @Nullable AdminAlerts alerts;
  private @Nullable Curation curation;
  private @Nullable Wand wand;
  private @Nullable HallOutlines outlines;
  private @Nullable PlaqueScale plaqueScale;
  private @Nullable ResetCommand reset;
  private boolean stopped;

  /** How long after an admin joins they hear about galleries left out at startup. */
  private static final java.time.Duration LEFT_OUT_NOTICE_DELAY = java.time.Duration.ofSeconds(3);

  private @Nullable ScheduledTask flusher;
  private @Nullable HallService hallService;
  private @Nullable HallVisits visits;
  private @Nullable Reconciler reconciler;
  private @Nullable Gates gates;
  private @Nullable HallRules rules;
  private @Nullable Zones zones;
  private @Nullable MapService map;
  private @Nullable RolloverArchiver archiverForTests;
  private LongSupplier nanoTime = System::nanoTime;

  /**
   * @param maps publishes the map to a web map such as BlueMap, or {@link MapPublisher#NONE};
   *     Seasons closes it at {@link #stop}
   * @param environment environment variables, for the webhook overrides
   * @param writer the file writer thread; Seasons closes it at {@link #stop}
   * @param hooks the scenario hooks, only on a scenario server (docs/adr/0003)
   */
  public Seasons(
      Platform platform,
      String version,
      Clock clock,
      Webhooks webhooks,
      MapPublisher maps,
      Map<String, String> environment,
      SerialWriter writer,
      Optional<ScenarioHooks> hooks) {
    this.platform = platform;
    this.version = version;
    this.clock = clock;
    this.webhooks = webhooks;
    this.maps = maps;
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
    // A reset a crash cut short is finished first, so a half-deleted folder never loads as real
    // data (questions 283); its leftover displays go once the server is running.
    try {
      List<com.petepeterepeat.seasons.core.hall.Bounds> leftover =
          ResetCommand.finishInterrupted(world, platform.storage().configDirectory());
      if (!leftover.isEmpty()) {
        serverThread.execute(() -> {
          var _ = ResetCommand.removeDisplays(platform, leftover);
        });
      }
    } catch (IOException | RuntimeException e) {
      LOG.error("Can't finish the interrupted /season reset; remove what is left by hand", e);
    }
    ConfigLoader loader =
        new ConfigLoader(
            platform.storage().configDirectory(),
            world,
            environment,
            platform.world()::dimensionExists,
            platform.world()::isItem,
            platform.world()::isGateBlock);
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
    AdminAlerts adminAlerts = new AdminAlerts(platform.scheduler(), webhooks, config);
    alerts = adminAlerts;
    CrashPoints crash = hooks.<CrashPoints>map(h -> h).orElse(CrashPoints.NONE);
    Pets pets = new Pets(platform, config, players, audit, Optional.ofNullable(journal));
    this.pets = pets;
    SeasonEntry entry =
        new SeasonEntry(
            platform,
            config,
            players,
            immunity,
            clock,
            player -> {
              var _ = pets.travel(player);
            });
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
            audit,
            RandomGenerator.getDefault());
    hallService = halls;
    halls.hallFile(loader.hallFile());
    HallAudit hallAudit = new HallAudit(audit, clock);
    HallVisits visiting =
        new HallVisits(platform, config, halls, hallAudit, players, immunity, clock, entry::spawn);
    visits = visiting;
    HallRenderer renderer = new HallRenderer(platform, config, writer, archives, halls);
    Hall hall = halls.hall(renderer);
    Museum museum =
        new Museum(
            platform,
            config,
            halls,
            new MuseumFiles(world.resolve("museum.yml"), world.resolve("data/museum")),
            writer,
            Optional.ofNullable(journal),
            hold,
            audit,
            crash,
            clock);
    Reconciler reconciling =
        new Reconciler(
            platform, halls, renderer, archives, writer, audit, museum, nanoTime, adminAlerts);
    reconciler = reconciling;
    HallRules hallRules = new HallRules(platform, config, halls, hallAudit, reconciling);
    rules = hallRules;
    Optional<HallImporter> importer = Optional.empty();
    CellEdits cellEdits = new CellEdits(platform, config, halls);
    Wand wanding = new Wand(platform, config, halls, cellEdits, this::currentSeason);
    wand = wanding;
    Optional<Stamper> stamper = Optional.empty();

    Optional<Archiver> archiver = Optional.empty();
    Optional<Unarchiver> unarchiver = Optional.empty();
    Optional<JournalRecovery> recovery = Optional.empty();
    Optional<Vault> vault = Optional.empty();
    RolloverArchiver rollover = refusingArchiver();
    if (journal != null) {
      JournalRecovery replay =
          new JournalRecovery(journal, serverThread, recoveryAlerts(adminAlerts));
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
              world,
              hold,
              owner -> curation != null && curation.curating(owner));
      replay.register(Operation.IMPORT, importing.recovery());
      replay.register(Operation.IMPORT_UNDO, importing.undoRecovery());
      Vault vaulting =
          new Vault(
              platform, config, this::state, writer, journal, vaults, hold, audit, crash, clock);
      replay.register(Operation.VAULT_WITHDRAW, vaulting.recovery());
      vaulting.bindArchiveOpen((player, season) ->
          replay.open(player).stream()
              .anyMatch(op -> op.operation() == Operation.ARCHIVE && op.season() == season));
      replay.register(Operation.MUSEUM, museum.recovery());
      Curation curating =
          new Curation(
              platform,
              config,
              archives,
              vaults,
              new CurateFiles(world.resolve("data")),
              museum,
              renderer,
              players,
              writer,
              journal,
              replay,
              hold,
              audit,
              announcer,
              adminAlerts,
              crash,
              clock,
              back ->
                  vaulting.returnItem(
                      back.owner(), back.item(), back.why(), Optional.of(back.ref())));
      replay.register(Operation.CURATE, curating.recovery());
      Stamper stamping =
          new Stamper(
              platform,
              config,
              halls,
              new TemplateLibrary(world.resolve("templates"), platform.templates()),
              journal,
              writer,
              audit,
              crash,
              world.resolve("data/stamps"),
              this::currentSeason,
              clock);
      replay.register(Operation.STAMP, stamping.recovery());
      stamper = Optional.of(stamping);
      curation = curating;
      vault = Optional.of(vaulting);
      importer = Optional.of(importing);
      var _ = replay.recoverAll();
      archiver = Optional.of(archiving);
      unarchiver = Optional.of(unarchiving);
      recovery = Optional.of(replay);
      rollover = archiving;
      gates = new Gates(platform, config, halls, journal, audit, crash, announcer);
    }

    Dragon fight =
        new Dragon(
            platform,
            config,
            announcer,
            audit,
            Optional.ofNullable(journal),
            vault,
            vaults,
            writer,
            crash,
            clock);
    dragon = fight;
    LaunchSequence launch =
        new LaunchSequence(
            platform,
            config,
            announcer,
            entry,
            hold::held,
            unsettled(players, recovery),
            Optional.ofNullable(journal),
            fight::launched);
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
            platform,
            config,
            announcer,
            audit,
            clock,
            halls::inHall,
            entry::spawn,
            hold::isHeld,
            players);
    zones = zoning;
    zoning.bind(seasons);
    pets.bind(seasons::state, zoning::decide);
    fight.bind(seasons);
    museum.bind(seasons::state, reconciling);
    seasons.onEnding(museum::seasonEnded);
    archiver.ifPresent(a -> a.bind(seasons::state));
    Awards awarding = new Awards(config, archives, writer, seasons, announcer, clock, serverThread);
    awarding.bind((season, winners) -> {
      for (PlayerId player : winners) {
        var _ = renderer.render(player, season);
      }
      museum.seasonEnded(season);
    });
    archiver.ifPresent(a -> a.onArchived(awarding::archived));
    seasons.onIntermission(awarding::intermission);
    seasons.start();
    fight.start();
    halls.bind(seasons::state, seasons::updateDecorating);
    if (curation != null) {
      halls.bindCuration(curation);
      curation.start();
      // Set below with the reset's, which comes first.
    }
    halls.start();
    if (gates != null) {
      gates.start();
    }
    visiting.bind(seasons::state);
    visiting.bindZones(zoning::lockedFor);
    visiting.bindHold(hold::isHeld);
    visiting.start();
    reconciling.start();
    hallRules.start();
    zoning.start();
    // Once the Hall can draw what a crash left undrawn.
    awarding.start();
    MapService mapping = new MapService(platform, config, seasons::state, zoning, maps);
    map = mapping;
    mapping.start();

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
                clock,
                pets::loggedIn));
    Tripwire wire =
        new Tripwire(
            platform.players(), platform.inventories(), platform.scheduler(), audit, adminAlerts);
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
                archiver,
                unarchiver,
                recovery,
                players,
                furthest,
                login,
                archives,
                vaults,
                halls,
                pets::waiting,
                maps::status,
                audit));
    commands = built;
    halls.bindLaunch(player ->
        built.launch(
            new CommandSource() {
              @Override
              public Optional<PlayerId> player() {
                return Optional.of(player);
              }

              @Override
              public void reply(Message message) {
                platform.players().sendMessage(player, message);
              }
            },
            Arguments.NONE));
    platform
        .events()
        .handle(
            new IntentRouter(
                hold,
                immunity,
                furthest,
                intent ->
                    wanding
                        .decide(intent)
                        .or(() ->
                            plaqueScale == null ? Optional.empty() : plaqueScale.decide(intent))
                        .or(() -> visiting.decide(intent))
                        .or(() -> halls.decide(intent))
                        .or(() -> hallRules.decide(intent))
                        .or(() -> zoning.decide(intent)),
                visiting::damaged,
                player -> platform.permissions().has(player, SeasonService.ADMIN_PERMISSION),
                () -> config.get().messages()));
    platform.events().sessions(sessions(login));
    platform.events().tripwire(wire);
    platform.events().hall(hallRules);
    platform.events().zones(zoning);
    ConfigEdits edits = new ConfigEdits(platform, config, loader, writer, audit, seasons::reloaded);
    CommandHelp help =
        new CommandHelp(
            () -> config.get().messages(),
            (source, node) ->
                source.player().map(p -> platform.permissions().has(p, node)).orElse(true),
            () -> config.get().config().admin().hints());
    TemplateLibrary library = new TemplateLibrary(world.resolve("templates"), platform.templates());
    CommandSuggestions suggestions =
        new CommandSuggestions(
            platform, config, () -> Optional.of(players), seasons::state, library::names);
    help.choices(suggestions);
    commandHelp = help;
    cellEdits.bindHelp(help);
    Path seasonBackups = world.resolve("backups").resolve("seasons");
    List<CommandNode> nodes = new ArrayList<>();
    SpawnCommands spawnCommands =
        new SpawnCommands(
            platform, config, loader, edits, seasons, seasonBackups, help, visiting::adminGo);
    nodes.add(spawnCommands.node());
    nodes.add(spawnCommands.tpNode());
    ResetCommand resetting =
        new ResetCommand(
            platform,
            config,
            seasons::state,
            Optional.ofNullable(journal),
            hold,
            halls,
            players,
            world,
            platform.storage().configDirectory(),
            clock,
            this::stop);
    reset = resetting;
    nodes.add(resetting.node());
    Curation chatting = curation;
    platform
        .events()
        .chat((player, text) ->
            resetting.typed(player, text) || (chatting != null && chatting.typed(player, text)));
    nodes.addAll(
        new ZoneCommands(
                platform, config, loader, edits, seasons, zoning, seasonBackups, wanding::selection)
            .nodes());
    nodes.add(
        new MarkerCommands(platform, config, loader, edits, world.resolve("backups"), maps::status)
            .node());
    nodes.add(new ConfigCommands(config, loader, edits, world.resolve("backups")).node());
    built.bindHelp(help);
    platform.commands().register(help.decorate(built.root(nodes), suggestions));
    HallCommands hallCommands =
        new HallCommands(
            platform,
            config,
            halls,
            visiting,
            renderer,
            reconciling,
            importer,
            players,
            cellEdits,
            wanding,
            stamper,
            help);
    wanding.bindLinks(hallCommands::addLink);
    CellRows rows = new CellRows(platform, config, halls, cellEdits, wanding, clock, help);
    PlaqueScale plaques = new PlaqueScale(platform, config, halls, cellEdits);
    plaqueScale = plaques;
    HallOutlines outlining = new HallOutlines(platform, config, halls, wanding);
    outlining.bindPreviews(rows::previews);
    outlines = outlining;
    hallCommands.bindOutlines(outlining);
    outlining.start();
    CommandNode hof =
        hallCommands
            .root()
            .merge(new HallLists(platform, config, halls, players, cellEdits).root())
            .merge(
                new CellFixes(platform, config, halls, players, cellEdits, wanding, reconciling)
                    .root())
            .merge(rows.root())
            .merge(plaques.root());
    if (curation != null) {
      List<CommandNode> children = new ArrayList<>(hof.children());
      children.add(curation.command());
      hof = new CommandNode(hof.name(), hof.argument(), hof.permission(), children, hof.action());
    }
    platform.commands().register(help.decorate(hof, suggestions), List.of("halloffame"));
    vault.ifPresent(
        v -> platform.commands().register(help.decorate(v.command(), suggestions), List.of()));
    platform.commands().register(help.decorate(pets.command(), suggestions), List.of());
    platform.commands().register(help.decorate(museum.command(), suggestions), List.of());
    platform.events().pets(pets);
    platform.events().dragon(fight);
    login.ifPresent(l -> l.markPlaying(platform.players().online()));

    if (hooks.isPresent() && recovery.isPresent()) {
      hooks
          .get()
          .wire(
              new ScenarioHooks.Wiring(
                  archives,
                  vaults,
                  vault.orElseThrow(),
                  players,
                  recovery.get(),
                  platform.menus(),
                  writer,
                  config,
                  serverThread,
                  (player, season) ->
                      halls
                          .card(player, season)
                          .thenApply(card -> card.map(SeasonCard::plainLines).orElse(List.of())),
                  hold));
    }
  }

  /**
   * Joins and leaves go to login catch-up, if the journal could be opened; admins who join are also
   * told about any gallery, or markers.yml, left out at startup.
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
            .runGlobalLater(LEFT_OUT_NOTICE_DELAY, () -> tellAboutLeftOutFiles(player));
      }

      private void tellAboutLeftOutFiles(PlayerRef player) {
        ConfigSnapshot snapshot = config.get();
        if (platform.players().find(player.id()).isPresent()
            && platform.permissions().has(player.id(), SeasonService.ADMIN_PERMISSION)
            && service != null
            && service.state().isPresent()
            && store != null) {
          SeasonCommands.recordsAhead(snapshot.messages(), service.state().get(), store)
              .forEach(line -> platform.players().sendMessage(player.id(), line));
        }
        if (platform.players().find(player.id()).isPresent()
            && (snapshot.anyGalleryLeftOut() || !snapshot.markersLeftOut().isEmpty())
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
          snapshot
              .markersLeftOut()
              .forEach(problem ->
                  platform
                      .players()
                      .sendMessage(
                          player.id(),
                          SeasonCommands.markersLeftOut(snapshot.messages(), problem)));
          snapshot
              .hallLeftOut()
              .forEach(problem ->
                  platform
                      .players()
                      .sendMessage(
                          player.id(), SeasonCommands.hallLeftOut(snapshot.messages(), problem)));
        }
      }

      @Override
      public void left(PlayerId player) {
        login.ifPresent(l -> l.left(player));
        if (wand != null) {
          wand.left(player);
        }
        if (outlines != null) {
          outlines.left(player);
        }
        if (reset != null) {
          reset.left(player);
        }
        if (curation != null) {
          curation.left(player);
        }
      }
    };
  }

  /** Recovery runs for the admin webhook: each operation finished, and each that failed. */
  private JournalRecovery.Reporter recoveryAlerts(AdminAlerts adminAlerts) {
    return (operation, outcome) -> {
      String name =
          operation
              .player()
              .map(p -> platform.players().knownName(p).orElse(p.toString()))
              .orElse("nobody");
      String kind = operation.operation().name().toLowerCase(java.util.Locale.ROOT);
      String season = Integer.toString(operation.season());
      switch (outcome) {
        case com.petepeterepeat.seasons.core.store.RecoveryOutcome.Finished f ->
            adminAlerts.alert(
                "alerts.recovery-finished", "operation", kind, "name", name, "season", season);
        case com.petepeterepeat.seasons.core.store.RecoveryOutcome.Failed f ->
            adminAlerts.alert(
                "alerts.recovery-failed",
                "operation",
                kind,
                "name",
                name,
                "season",
                season,
                "reason",
                f.reason());
        case com.petepeterepeat.seasons.core.store.RecoveryOutcome.WaitForPlayer w -> {}
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
      public void respawned(PlayerId player) {
        first.respawned(player);
        second.respawned(player);
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
    // Once: a reset stops Seasons itself before the platform does (questions 283).
    if (stopped) {
      return;
    }
    stopped = true;
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
    if (outlines != null) {
      outlines.stop();
    }
    if (gates != null) {
      gates.stop();
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
    if (map != null) {
      map.stop();
    }
    if (firsts != null) {
      firsts.stop();
    }
    if (pets != null) {
      pets.stop();
    }
    if (dragon != null) {
      dragon.stop();
    }
    if (curation != null) {
      curation.stop();
    }
    if (flusher != null) {
      flusher.cancel();
    }
    if (store != null) {
      logFailure(store.flush());
    }
    writer.close();
    if (alerts != null) {
      alerts.flush();
    }
    webhooks.close();
    maps.close();
  }

  @Override
  public String version() {
    return version;
  }

  /** The season state, once started. */
  private Optional<SeasonState> state() {
    return service == null ? Optional.empty() : service.state();
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

  /** Help and hints over every registered command, for tests. */
  Optional<CommandHelp> commandHelp() {
    return Optional.ofNullable(commandHelp);
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
      result
          .snapshot()
          .get()
          .markersLeftOut()
          .forEach(problem ->
              LOG.error(
                  "markers.yml is left out, so no shared waypoints are shown, until it is fixed"
                      + " and /season reload succeeds: {}",
                  problem));
      result
          .snapshot()
          .get()
          .hallLeftOut()
          .forEach(problem ->
              LOG.error(
                  "hall.yml is left out, so the Hall has no hub, spawn or links, until it is"
                      + " fixed and /season reload succeeds; /season end and /season launch are"
                      + " refused until then: {}",
                  problem));
      return List.of();
    }
    for (ConfigProblem problem : result.problems()) {
      LOG.error("Config problem, using the bundled defaults: {}", problem);
    }
    return result.problems();
  }
}
