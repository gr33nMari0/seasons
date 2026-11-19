// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static com.petepeterepeat.seasons.core.Seasons.PERMISSION_ADMIN_CONFIG;
import static com.petepeterepeat.seasons.core.Seasons.PERMISSION_ADMIN_SEASON;
import static com.petepeterepeat.seasons.core.Seasons.PERMISSION_INFO;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.Archiver;
import com.petepeterepeat.seasons.core.archive.Unarchiver;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Flags;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.ConfigProblem;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.config.ZonedTimes;
import com.petepeterepeat.seasons.core.hall.HallService;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.FurthestTracker;
import com.petepeterepeat.seasons.core.player.LoginPlan;
import com.petepeterepeat.seasons.core.player.LoginService;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.HumanTime;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonEvent;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonMachine;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.season.Transition;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalHealth;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.zone.Zone;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code /season} command tree and what each command does. */
final class SeasonCommands {
  private static final String DRY_RUN = "--dry-run";
  private static final String NOW = "--now";
  private static final String FORCE = "--force";
  private static final Logger LOG = LoggerFactory.getLogger(SeasonCommands.class);

  private final Platform platform;
  private final String version;
  private final Clock clock;
  private final SeasonService seasons;
  private final Announcer announcer;
  private final ConfigHolder config;
  private final ConfigLoader loader;
  private final SerialWriter writer;
  private final Optional<Journal> journal;
  private final Optional<String> journalError;
  private final List<ConfigProblem> startupProblems;
  private final Services services;

  /**
   * What the archive commands work with. The archiving services are empty when the journal couldn't
   * be opened, and those commands are then refused.
   */
  record Services(
      Optional<Archiver> archiver,
      Optional<Unarchiver> unarchiver,
      Optional<JournalRecovery> recovery,
      PlayerStore players,
      FurthestTracker furthest,
      Optional<LoginService> login,
      ArchiveFiles archives,
      VaultFiles vaults,
      HallService hall,
      java.util.function.LongSupplier petsWaiting,
      java.util.function.Supplier<MapPublisher.Status> map) {}

  SeasonCommands(
      Platform platform,
      String version,
      Clock clock,
      SeasonService seasons,
      Announcer announcer,
      ConfigHolder config,
      ConfigLoader loader,
      SerialWriter writer,
      Optional<Journal> journal,
      Optional<String> journalError,
      List<ConfigProblem> startupProblems,
      Services services) {
    this.platform = platform;
    this.version = version;
    this.clock = clock;
    this.seasons = seasons;
    this.announcer = announcer;
    this.config = config;
    this.loader = loader;
    this.writer = writer;
    this.journal = journal;
    this.journalError = journalError;
    this.startupProblems = List.copyOf(startupProblems);
    this.services = services;
  }

  /**
   * @param more further nodes under {@code /season}, such as the spawn and zone commands
   */
  CommandNode root(List<CommandNode> more) {
    CommandNode.Builder root = CommandNode.literal("season");
    more.forEach(root::then);
    return root.then(
            CommandNode.literal("status")
                .permission(PERMISSION_ADMIN_SEASON)
                .executes(this::status))
        .then(CommandNode.literal("info").permission(PERMISSION_INFO).executes(this::info))
        .then(
            CommandNode.literal("init")
                .permission(PERMISSION_ADMIN_SEASON)
                .then(
                    CommandNode.argument("season", ArgumentType.integer(1, Integer.MAX_VALUE))
                        .executes(this::init)))
        .then(
            CommandNode.literal("schedule")
                .permission(PERMISSION_ADMIN_SEASON)
                .then(
                    CommandNode.literal("end")
                        .then(
                            CommandNode.argument("time", ArgumentType.greedy())
                                .executes(this::scheduleEnd))))
        .then(
            CommandNode.literal("end")
                .permission(PERMISSION_ADMIN_SEASON)
                .executes(this::end)
                .then(CommandNode.argument("options", ArgumentType.greedy()).executes(this::end)))
        .then(
            CommandNode.literal("launch")
                .permission(PERMISSION_ADMIN_SEASON)
                .executes(this::launch)
                .then(
                    CommandNode.argument("options", ArgumentType.greedy()).executes(this::launch)))
        .then(
            CommandNode.literal("firsts")
                .permission(PERMISSION_ADMIN_SEASON)
                .executes(this::firsts))
        .then(
            CommandNode.literal("archive")
                .permission(PERMISSION_ADMIN_SEASON)
                .then(CommandNode.argument("player", ArgumentType.word()).executes(this::archive)))
        .then(
            CommandNode.literal("unarchive")
                .permission(PERMISSION_ADMIN_SEASON)
                .then(
                    CommandNode.argument("player", ArgumentType.word())
                        .then(
                            CommandNode.argument(
                                    "season", ArgumentType.integer(1, Integer.MAX_VALUE))
                                .executes(this::unarchive)
                                .then(
                                    CommandNode.argument("options", ArgumentType.greedy())
                                        .executes(this::unarchive)))))
        .then(
            CommandNode.literal("reprocess")
                .permission(PERMISSION_ADMIN_SEASON)
                .then(
                    CommandNode.argument("player", ArgumentType.word()).executes(this::reprocess)))
        .then(
            CommandNode.literal("decorate")
                .permission(PERMISSION_ADMIN_SEASON)
                .then(CommandNode.literal("open").executes(this::decorateOpen))
                .then(CommandNode.literal("close").executes(this::decorateClose)))
        .then(
            CommandNode.literal("cancel")
                .permission(PERMISSION_ADMIN_SEASON)
                .executes(this::cancel))
        .then(
            CommandNode.literal("reload")
                .permission(PERMISSION_ADMIN_CONFIG)
                .executes(this::reload))
        .then(
            CommandNode.literal("validate")
                .permission(PERMISSION_ADMIN_CONFIG)
                .executes(this::validate))
        .build();
  }

  void status(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String name = platform.name();
    source.reply(messages.get("status.header", "version", version, "platform", name));
    seasons
        .loadError()
        .ifPresent(error -> source.reply(messages.get("status.unreadable", "error", error)));
    Optional<SeasonState> state = seasons.state();
    if (state.isEmpty()) {
      source.reply(messages.get("status.no-season"));
    } else {
      SeasonState current = state.get();
      source.reply(messages.get("status.season", "season", Integer.toString(current.season())));
      source.reply(messages.get("status.phase", "phase", phase(messages, current.phase())));
      plannedEnd(source, "status", current);
      if (current.phase() instanceof Phase.Countdown countdown) {
        Duration left = Duration.between(clock.instant(), countdown.endsAt());
        source.reply(messages.get("status.countdown", "time", HumanTime.clock(left)));
      }
      current
          .countdownInterrupted()
          .ifPresent(at ->
              source.reply(
                  messages.get(
                      "status.interrupted",
                      "time",
                      HumanTime.time(at.atZone(ZoneId.systemDefault())))));
    }
    state.ifPresent(current -> playerStatus(source, messages, current));
    journalStatus(source, messages);
    for (Map.Entry<String, List<ConfigProblem>> gallery :
        config.get().galleriesLeftOut().entrySet()) {
      for (ConfigProblem problem : gallery.getValue()) {
        source.reply(galleryLeftOut(messages, gallery.getKey(), problem));
      }
    }
    for (ConfigProblem problem : config.get().markersLeftOut()) {
      source.reply(markersLeftOut(messages, problem));
    }
    for (ConfigProblem problem : startupProblems) {
      source.reply(messages.get("status.config-problem", "problem", problem.describe(messages)));
    }
    var gaps = platform.capabilities().gaps();
    if (gaps.isEmpty()) {
      source.reply(messages.get("status.capabilities-ok", "platform", name));
    }
    for (Capability gap : gaps) {
      source.reply(
          messages.get("status.capability-gap", "platform", name, "capability", gap.name()));
    }
    source.reply(
        switch (services.map().get()) {
          case MapPublisher.Status.NotInstalled _ -> messages.get("status.map-none");
          case MapPublisher.Status.Waiting _ -> messages.get("status.map-waiting");
          case MapPublisher.Status.Published published ->
              messages.get(
                  "status.map-published",
                  "markers",
                  Integer.toString(published.markers()),
                  "maps",
                  Integer.toString(published.maps()));
        });
  }

  void info(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    Optional<SeasonState> state = seasons.state();
    if (state.isEmpty()) {
      source.reply(messages.get("info.no-season"));
      return;
    }
    SeasonState current = state.get();
    source.reply(messages.get("info.season", "season", Integer.toString(current.season())));
    source.reply(messages.get("info.phase", "phase", phase(messages, current.phase())));
    plannedEnd(source, "info", current);
    if (source.player().isPresent()) {
      Optional<HallService.CellRef> cell =
          services.hall().cellOf(source.player().get(), current.season());
      source.reply(
          cell.map(ref ->
                  messages.get("info.cell", "cell", ref.cell().id(), "gallery", ref.gallery().id()))
              .orElseGet(() -> messages.get("info.no-cell")));
    }
  }

  /** {@code /season firsts}: this season's firsts, oldest first. */
  void firsts(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    Optional<SeasonState> current = seasons.state();
    List<SeasonFirst> firsts = current.map(s -> s.firsts(s.season())).orElse(List.of());
    if (firsts.isEmpty()) {
      source.reply(messages.get("firsts.none", "season", season()));
      return;
    }
    source.reply(messages.get("firsts.header", "season", season()));
    for (SeasonFirst first : firsts) {
      source.reply(
          messages.get(
              "firsts.line",
              "name",
              first.name(),
              "what",
              Messages.plain(FirstsTracker.what(messages, first)),
              "time",
              HumanTime.time(first.at().atZone(ZoneId.systemDefault()))));
    }
  }

  /** A gallery left out at startup, for /season status and admins as they join. */
  static Message galleryLeftOut(Messages messages, String gallery, ConfigProblem problem) {
    return messages.get(
        "status.gallery-left-out", "gallery", gallery, "problem", problem.describe(messages));
  }

  /** markers.yml left out at startup, for /season status and admins as they join. */
  static Message markersLeftOut(Messages messages, ConfigProblem problem) {
    return messages.get("status.markers-left-out", "problem", problem.describe(messages));
  }

  void decorateOpen(CommandSource source, Arguments arguments) {
    switch (services.hall().open()) {
      case HallService.OpenResult.Opened opened ->
          source.reply(
              opened.owners() == 1
                  ? messages().get("decorate.opened-one", "season", season())
                  : messages()
                      .get(
                          "decorate.opened",
                          "season",
                          season(),
                          "count",
                          Integer.toString(opened.owners())));
      case HallService.OpenResult.Refused refused ->
          source.reply(messages().get(refused.messageKey(), "season", season()));
    }
  }

  void decorateClose(CommandSource source, Arguments arguments) {
    source.reply(
        messages()
            .get(
                services.hall().close() ? "decorate.closed" : "decorate.not-open",
                "season",
                season()));
  }

  void init(CommandSource source, Arguments arguments) {
    int season = arguments.integer("season");
    Transition transition = seasons.init(season);
    reply(source, transition, "init.done");
    if (transition instanceof Transition.Changed) {
      // Everyone online now is playing the season init recorded.
      services.login().ifPresent(login -> login.markPlaying(platform.players().online()));
    }
  }

  void scheduleEnd(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    ZonedDateTime at;
    try {
      at = ZonedTimes.parse(arguments.string("time"));
    } catch (IllegalArgumentException e) {
      source.reply(messages.get("schedule.invalid-time", "error", String.valueOf(e.getMessage())));
      return;
    }
    if (!at.toInstant().isAfter(clock.instant())) {
      source.reply(messages.get("schedule.past"));
      return;
    }
    Transition transition = seasons.scheduleEnd(at);
    if (transition instanceof Transition.Refused refused) {
      refuse(source, refused);
      return;
    }
    String key =
        config.get().config().schedule().autoEnd() ? "schedule.done-auto" : "schedule.done";
    source.reply(
        messages.get(
            key,
            "season",
            season(),
            "time",
            HumanTime.time(at),
            "utc",
            HumanTime.time(at, ZoneOffset.UTC)));
    if (Duration.between(clock.instant(), at.toInstant()).compareTo(Duration.ofDays(7)) < 0) {
      source.reply(messages.get("schedule.soon"));
    }
  }

  void end(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    Flags.Parsed parsed = Flags.parse(arguments.optionalString("options"), Set.of(DRY_RUN, NOW));
    if (parsed instanceof Flags.Parsed.Unknown unknown) {
      source.reply(messages.get("end.unknown-flag", "flag", unknown.word()));
      return;
    }
    Flags.Parsed.Ok flags = (Flags.Parsed.Ok) parsed;
    if (flags.has(DRY_RUN)) {
      dryRun(source, flags.has(NOW));
      return;
    }
    int online = platform.players().online().size();
    Transition transition = seasons.end(flags.has(NOW));
    if (transition instanceof Transition.Refused refused) {
      refuse(source, refused);
    } else if (flags.has(NOW)) {
      source.reply(messages.get("end.now", "season", season(), "count", Integer.toString(online)));
    }
  }

  void launch(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    Flags.Parsed parsed = Flags.parse(arguments.optionalString("options"), Set.of(DRY_RUN));
    if (parsed instanceof Flags.Parsed.Unknown unknown) {
      source.reply(messages.get("launch.unknown-flag", "flag", unknown.word()));
      return;
    }
    if (((Flags.Parsed.Ok) parsed).has(DRY_RUN)) {
      launchDryRun(source);
      return;
    }
    if (seasons.launch() instanceof Transition.Refused refused) {
      refuse(source, refused);
    }
  }

  void archive(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String name = arguments.string("player");
    if (services.login().isEmpty() || seasons.state().isEmpty()) {
      source.reply(messages.get("archive.unavailable"));
      return;
    }
    Optional<PlayerRef> player = platform.players().findByName(name);
    if (player.isEmpty()) {
      source.reply(messages.get("archive.offline", "name", name));
      return;
    }
    PlayerRef online = player.get();
    LoginPlan.Plan plan =
        LoginPlan.decide(services.players().find(online.id()), true, seasons.state().get());
    if (!(plan instanceof LoginPlan.Plan.Archive target)) {
      source.reply(messages.get("archive.nothing", "name", online.name()));
      return;
    }
    String season = Integer.toString(target.season());
    var _ =
        services
            .login()
            .get()
            .archiveAndMove(online.id(), target)
            .whenComplete((outcome, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      Messages now = messages();
                      if (failure != null) {
                        source.reply(
                            now.get(
                                "archive.failed",
                                "name",
                                online.name(),
                                "season",
                                season,
                                "reason",
                                String.valueOf(failure.getMessage())));
                        return;
                      }
                      source.reply(
                          switch (outcome) {
                            case Archiver.Outcome.Archived a ->
                                now.get(
                                    "archive.archived", "name", online.name(), "season", season);
                            case Archiver.Outcome.AlreadyArchived a ->
                                now.get("archive.nothing", "name", online.name());
                            case Archiver.Outcome.Offline o ->
                                now.get("archive.offline", "name", online.name());
                            case Archiver.Outcome.Busy b ->
                                now.get("archive.busy", "name", online.name());
                            case Archiver.Outcome.Failed f ->
                                now.get(
                                    "archive.failed",
                                    "name",
                                    online.name(),
                                    "season",
                                    season,
                                    "reason",
                                    f.reason());
                          });
                    }));
  }

  void unarchive(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String name = arguments.string("player");
    int season = arguments.integer("season");
    Flags.Parsed parsed = Flags.parse(arguments.optionalString("options"), Set.of(FORCE));
    if (parsed instanceof Flags.Parsed.Unknown unknown) {
      source.reply(messages.get("unarchive.unknown-flag", "flag", unknown.word()));
      return;
    }
    boolean force = ((Flags.Parsed.Ok) parsed).has(FORCE);
    if (services.unarchiver().isEmpty()) {
      source.reply(messages.get("archive.unavailable"));
      return;
    }
    Optional<PlayerRef> player = platform.players().findByName(name);
    if (player.isEmpty()) {
      source.reply(messages.get("unarchive.offline", "name", name));
      return;
    }
    PlayerRef online = player.get();
    String number = Integer.toString(season);
    String actor = actor(source);
    var _ =
        services
            .unarchiver()
            .get()
            .unarchive(online, season, force, actor)
            .whenComplete((outcome, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      Messages now = messages();
                      if (failure != null) {
                        source.reply(
                            now.get(
                                "unarchive.failed",
                                "name",
                                online.name(),
                                "season",
                                number,
                                "reason",
                                String.valueOf(failure.getMessage())));
                        return;
                      }
                      source.reply(
                          switch (outcome) {
                            case Unarchiver.Outcome.Restored r ->
                                r.displacedFile().isEmpty()
                                    ? now.get(
                                        "unarchive.done", "name", online.name(), "season", number)
                                    : now.get(
                                        "unarchive.done-displaced",
                                        "name",
                                        online.name(),
                                        "season",
                                        number,
                                        "count",
                                        Integer.toString(r.displacedItems()),
                                        "file",
                                        r.displacedFile().get().getFileName().toString());
                            case Unarchiver.Outcome.Offline o ->
                                now.get("unarchive.offline", "name", online.name());
                            case Unarchiver.Outcome.NoArchive n ->
                                now.get(
                                    "unarchive.no-archive",
                                    "name",
                                    online.name(),
                                    "season",
                                    number);
                            case Unarchiver.Outcome.NotEmpty n ->
                                now.get("unarchive.not-empty", "name", online.name());
                            case Unarchiver.Outcome.Imported i ->
                                now.get(
                                    "unarchive.imported", "name", online.name(), "season", number);
                            case Unarchiver.Outcome.Busy b ->
                                now.get("archive.busy", "name", online.name());
                            case Unarchiver.Outcome.Failed f ->
                                now.get(
                                    "unarchive.failed",
                                    "name",
                                    online.name(),
                                    "season",
                                    number,
                                    "reason",
                                    f.reason());
                          });
                    }));
  }

  void reprocess(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    String name = arguments.string("player");
    if (services.recovery().isEmpty()) {
      source.reply(messages.get("archive.unavailable"));
      return;
    }
    Optional<PlayerId> id =
        platform
            .players()
            .findByName(name)
            .map(PlayerRef::id)
            .or(() ->
                services.players().all().stream()
                    .filter(record -> record.lastName().equalsIgnoreCase(name))
                    .map(PlayerRecord::id)
                    .findFirst());
    JournalRecovery recovery = services.recovery().get();
    if (id.isEmpty() || recovery.open(id.get()).isEmpty()) {
      source.reply(messages.get("reprocess.nothing", "name", name));
      return;
    }
    if (platform.players().find(id.get()).isEmpty()) {
      source.reply(
          messages.get(
              "reprocess.offline",
              "name",
              name,
              "count",
              Integer.toString(recovery.open(id.get()).size())));
      return;
    }
    var _ =
        recovery
            .retry(id.get())
            .whenComplete((finished, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      int left = recovery.open(id.get()).size();
                      if (failure != null || left > 0) {
                        String reason =
                            failure != null
                                ? String.valueOf(failure.getMessage())
                                : recovery.failures().entrySet().stream()
                                    .filter(e -> e.getKey().player().equals(id))
                                    .map(Map.Entry::getValue)
                                    .findFirst()
                                    .orElse("see the server log");
                        source.reply(
                            messages()
                                .get(
                                    "reprocess.failed",
                                    "name",
                                    name,
                                    "count",
                                    Integer.toString(left),
                                    "reason",
                                    reason));
                      } else {
                        source.reply(
                            messages()
                                .get(
                                    "reprocess.done",
                                    "name",
                                    name,
                                    "count",
                                    Integer.toString(finished)));
                      }
                    }));
  }

  void cancel(CommandSource source, Arguments arguments) {
    if (seasons.cancel() instanceof Transition.Refused refused) {
      refuse(source, refused);
    }
  }

  void reload(CommandSource source, Arguments arguments) {
    var _ =
        writer
            .submit(loader::load)
            .whenComplete((result, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      if (failure != null || result.snapshot().isEmpty()) {
                        reloadFailed(source, failure, result);
                        return;
                      }
                      ConfigSnapshot snapshot = result.snapshot().get();
                      config.set(snapshot);
                      seasons.reloaded(snapshot);
                      source.reply(messages().get("config.reloaded"));
                      for (String warning : result.warnings()) {
                        source.reply(messages().get("config.warning", "warning", warning));
                      }
                    }));
  }

  void validate(CommandSource source, Arguments arguments) {
    var _ =
        writer
            .submit(loader::load)
            .whenComplete((result, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      if (failure != null) {
                        LOG.error("/season validate failed", failure);
                      }
                      if (failure == null && result.snapshot().isPresent()) {
                        source.reply(messages().get("config.valid"));
                      } else {
                        source.reply(messages().get("config.invalid"));
                        if (result != null) {
                          problems(source, result.problems());
                        }
                      }
                    }));
  }

  /**
   * A failed reload goes to whoever ran it, to the other online config admins and to the server
   * log, each problem with its file, line or path, and message.
   */
  private void reloadFailed(
      CommandSource source, @Nullable Throwable failure, ConfigLoader.@Nullable Result result) {
    Messages messages = messages();
    List<Message> details = new ArrayList<>();
    if (failure != null) {
      LOG.error("/season reload failed", failure);
      details.add(
          messages.get("config.reload-error", "error", String.valueOf(failure.getMessage())));
    } else if (result != null) {
      for (ConfigProblem problem : result.problems()) {
        details.add(messages.get(problem.messageKey(), problem.placeholders()));
      }
    }
    source.reply(messages.get("config.reload-failed"));
    details.forEach(source::reply);

    String name =
        source
            .player()
            .flatMap(id -> platform.players().find(id))
            .map(PlayerRef::name)
            .orElse(messages.text("config.console-name"));
    List<Message> lines = new ArrayList<>();
    lines.add(messages.get("config.reload-failed-by", "name", name));
    lines.addAll(details);
    announcer.admins(PERMISSION_ADMIN_CONFIG, source.player(), lines);
  }

  private void dryRun(CommandSource source, boolean now) {
    Messages messages = messages();
    Duration countdown = now ? Duration.ZERO : config.get().config().schedule().endCountdown();
    Transition would =
        SeasonMachine.apply(seasons.state(), new SeasonEvent.End(countdown), clock.instant());
    if (would instanceof Transition.Refused refused) {
      source.reply(
          messages.get(
              "end.dry-run.refused",
              "reason",
              messages.text(refused.messageKey(), "season", season())));
      return;
    }
    if (config.get().anyGalleryLeftOut()) {
      source.reply(
          messages.get(
              "end.dry-run.refused",
              "reason",
              messages.text("end.galleries-left-out", "season", season())));
    }
    source.reply(messages.get("end.dry-run.header", "season", season()));
    if (now) {
      source.reply(messages.get("end.dry-run.now"));
    } else {
      source.reply(
          messages.get("end.dry-run.countdown", "when", HumanTime.duration(messages, countdown)));
    }
    List<String> names =
        platform.players().online().stream().map(PlayerRef::name).sorted().toList();
    if (names.isEmpty()) {
      source.reply(messages.get("end.dry-run.no-players"));
    } else {
      source.reply(
          messages.get(
              "end.dry-run.players",
              "count",
              Integer.toString(names.size()),
              "names",
              String.join(", ", names)));
    }
    hallDryRun(source, messages);
    spawnDryRun(source, messages);
    source.reply(messages.get("end.dry-run.next", "season", season()));
  }

  /** How many cells are left for the players who earned one and have none. */
  private void hallDryRun(CommandSource source, Messages messages) {
    int current = seasons.state().map(SeasonState::season).orElse(0);
    int free = services.hall().freeCells(current).size();
    int without = services.hall().withoutCell(current).size();
    if (free >= without) {
      source.reply(
          messages.get(
              "hof.dry-run.cells",
              "free",
              Integer.toString(free),
              "season",
              season(),
              "players",
              Integer.toString(without)));
    } else {
      source.reply(
          messages.get(
              "hof.dry-run.shortfall",
              "free",
              Integer.toString(free),
              "season",
              season(),
              "players",
              Integer.toString(without),
              "short",
              Integer.toString(without - free)));
    }
  }

  /**
   * A next spawn inside one of the next season's zones, which launch would refuse, or beyond the
   * strongholds' reach, which it wouldn't.
   */
  private void spawnDryRun(CommandSource source, Messages messages) {
    int next = seasons.state().map(SeasonState::season).orElse(0) + 1;
    Optional<SeasonFile> file = config.get().season(next);
    Optional<Position> far = file.flatMap(SeasonFile::spawn).filter(SeasonFile::beyondStrongholds);
    if (far.isPresent()) {
      Position spawn = far.get();
      source.reply(
          messages.get(
              "end.dry-run.spawn-far",
              "next",
              Integer.toString(next),
              "x",
              Integer.toString(spawn.blockX()),
              "y",
              Integer.toString(spawn.blockY()),
              "z",
              Integer.toString(spawn.blockZ()),
              "distance",
              Long.toString(SeasonFile.distanceFromOrigin(spawn))));
    }
    Optional<Zone> holding = file.flatMap(SeasonFile::zoneHoldingSpawn);
    if (holding.isEmpty()) {
      return;
    }
    Position spawn = file.get().spawn().orElseThrow();
    source.reply(
        messages.get(
            "end.dry-run.spawn-in-zone",
            "next",
            Integer.toString(next),
            "x",
            Integer.toString(spawn.blockX()),
            "y",
            Integer.toString(spawn.blockY()),
            "z",
            Integer.toString(spawn.blockZ()),
            "zone",
            holding.get().id()));
  }

  private void launchDryRun(CommandSource source) {
    Messages messages = messages();
    Optional<String> refusal = seasons.launchRefusal();
    String next = seasons.state().map(s -> Integer.toString(s.season() + 1)).orElse("?");
    if (refusal.isPresent()) {
      source.reply(
          messages.get(
              "launch.dry-run.refused",
              "reason",
              messages.text(refusal.get(), "season", season(), "next", next)));
      return;
    }
    Position spawn =
        config.get().season(Integer.parseInt(next)).flatMap(file -> file.spawn()).orElseThrow();
    source.reply(messages.get("launch.dry-run.header", "season", next));
    source.reply(
        messages.get(
            "launch.dry-run.players",
            "count",
            Integer.toString(platform.players().online().size()),
            "x",
            Integer.toString(spawn.blockX()),
            "y",
            Integer.toString(spawn.blockY()),
            "z",
            Integer.toString(spawn.blockZ()),
            "dimension",
            spawn.dimension()));
  }

  /** The players line for the last finished season, and the season's furthest block. */
  private void playerStatus(CommandSource source, Messages messages, SeasonState state) {
    int finished = LoginPlan.isBetweenSeasons(state.phase()) ? state.season() : state.season() - 1;
    if (finished >= 1
        && (state.launchedAt().isPresent() || LoginPlan.isBetweenSeasons(state.phase()))) {
      long archived =
          services.players().all().stream().filter(r -> r.archivedThrough() >= finished).count();
      long waiting =
          services.players().all().stream()
              .filter(r -> r.archivedThrough() < finished && r.played(finished))
              .count();
      source.reply(
          messages.get(
              "status.players",
              "archived",
              Long.toString(archived),
              "season",
              Integer.toString(finished),
              "waiting",
              Long.toString(waiting)));
    }
    if (state.launchedAt().isPresent() && !LoginPlan.isBetweenSeasons(state.phase())) {
      source.reply(
          messages.get("status.pets", "count", Long.toString(services.petsWaiting().getAsLong())));
    }
    if (!LoginPlan.isBetweenSeasons(state.phase())) {
      Optional<FurthestTracker.SeasonBest> best = services.furthest().seasonBest(state.season());
      if (best.isEmpty()) {
        source.reply(messages.get("status.no-furthest"));
      } else {
        Position at = best.get().best().at();
        source.reply(
            messages.get(
                "status.furthest",
                "distance",
                Long.toString(best.get().best().distance()),
                "name",
                best.get().name(),
                "x",
                Integer.toString(at.blockX()),
                "z",
                Integer.toString(at.blockZ())));
      }
    }
    services
        .recovery()
        .ifPresent(recovery ->
            recovery
                .failures()
                .forEach((operation, reason) ->
                    source.reply(
                        messages.get(
                            "status.recovery-failed",
                            "name",
                            operation
                                .player()
                                .flatMap(id -> services.players().find(id))
                                .map(PlayerRecord::lastName)
                                .orElse("?"),
                            "season",
                            Integer.toString(operation.season()),
                            "reason",
                            reason))));
  }

  private String actor(CommandSource source) {
    return source
        .player()
        .flatMap(id -> platform.players().find(id))
        .map(PlayerRef::name)
        .orElse(messages().text("config.console-name"));
  }

  private void plannedEnd(CommandSource source, String prefix, SeasonState state) {
    Messages messages = messages();
    if (state.plannedEnd().isEmpty()) {
      source.reply(messages.get(prefix + ".no-planned-end"));
      return;
    }
    ZonedDateTime end = state.plannedEnd().get();
    source.reply(
        messages.get(
            prefix + ".planned-end",
            "time",
            HumanTime.time(end, ZoneId.systemDefault()),
            "utc",
            HumanTime.time(end, ZoneOffset.UTC)));
  }

  private void journalStatus(CommandSource source, Messages messages) {
    if (journal.isEmpty()) {
      source.reply(
          messages.get("status.journal-unavailable", "error", journalError.orElse("not opened")));
      return;
    }
    JournalHealth health = journal.get().health();
    int pending = JournalRecovery.openOperations(journal.get().entries()).size();
    source.reply(
        messages.get(
            "status.journal",
            "entries",
            Integer.toString(health.entries()),
            "sequence",
            Long.toString(health.lastSequence()),
            "pending",
            Integer.toString(pending)));
    if (health.damagedLines() > 0) {
      source.reply(
          messages.get("status.journal-damaged", "count", Integer.toString(health.damagedLines())));
    }
    health
        .lastError()
        .ifPresent(error -> source.reply(messages.get("status.journal-error", "error", error)));
  }

  private void problems(CommandSource source, List<ConfigProblem> problems) {
    Messages messages = messages();
    for (ConfigProblem problem : problems) {
      source.reply(messages.get(problem.messageKey(), problem.placeholders()));
    }
  }

  private void reply(CommandSource source, Transition transition, String doneKey) {
    if (transition instanceof Transition.Refused refused) {
      refuse(source, refused);
    } else {
      source.reply(messages().get(doneKey, "season", season()));
    }
  }

  private void refuse(CommandSource source, Transition.Refused refused) {
    String next = seasons.state().map(s -> Integer.toString(s.season() + 1)).orElse("?");
    source.reply(messages().get(refused.messageKey(), "season", season(), "next", next));
  }

  private static String phase(Messages messages, Phase phase) {
    String key =
        switch (phase) {
          case Phase.Active active ->
              active.stage() == Stage.FRONTIER ? "phase.active-frontier" : "phase.active-open";
          case Phase.Countdown countdown -> "phase.ending-countdown";
          case Phase.Archiving archiving -> "phase.ending-archiving";
          case Phase.Intermission intermission -> "phase.intermission";
        };
    return messages.text(key);
  }

  private String season() {
    return seasons.state().map(s -> Integer.toString(s.season())).orElse("?");
  }

  private Messages messages() {
    return config.get().messages();
  }
}
