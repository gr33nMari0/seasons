// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.ConfigProblem;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.season.RolloverArchiver;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.SeasonStore;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
  private final RolloverArchiver archiver;
  private final Map<String, String> environment;
  private final SerialWriter writer;
  private final ConfigHolder config = new ConfigHolder(ConfigLoader.defaults());

  private @Nullable SeasonService service;
  private @Nullable SeasonCommands commands;

  /**
   * @param archiver archives players at season end; M1 part 2 replaces the stub
   * @param environment environment variables, for the webhook overrides
   * @param writer the file writer thread; Seasons closes it at {@link #stop}
   */
  public Seasons(
      Platform platform,
      String version,
      Clock clock,
      Webhooks webhooks,
      RolloverArchiver archiver,
      Map<String, String> environment,
      SerialWriter writer) {
    this.platform = platform;
    this.version = version;
    this.clock = clock;
    this.webhooks = webhooks;
    this.archiver = archiver;
    this.environment = Map.copyOf(environment);
    this.writer = writer;
  }

  /**
   * Loads config and state, replays the journal, registers commands and starts the season
   * lifecycle. Runs on the server thread while the plugin enables.
   */
  public void start() {
    Path world = platform.storage().worldDataDirectory();
    ConfigLoader loader =
        new ConfigLoader(platform.storage().configDirectory(), world, environment);
    List<ConfigProblem> startupProblems = loadConfig(loader);

    Journal journal = null;
    Optional<String> journalError = Optional.empty();
    try {
      journal = Journal.open(world.resolve("data/journal.log"), writer, clock);
      new JournalRecovery(journal).recoverAll();
    } catch (IOException | RuntimeException e) {
      journalError = Optional.of(String.valueOf(e.getMessage()));
      LOG.error("Can't open the journal", e);
    }

    Announcer announcer =
        new Announcer(platform.players(), platform.permissions(), webhooks, config);
    SeasonService seasons =
        new SeasonService(
            platform.players(),
            platform.scheduler(),
            clock,
            new SeasonStore(world.resolve("data/season.json"), writer),
            writer,
            config,
            announcer,
            archiver,
            world.resolve("seasons"),
            world.resolve("backups"));
    service = seasons;
    seasons.start();

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
            startupProblems);
    commands = built;
    platform.events().handle(intent -> Decision.allow());
    platform.commands().register(built.root());
  }

  /** Stops the lifecycle and finishes queued writes and posts. */
  public void stop() {
    if (service != null) {
      service.stop();
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
