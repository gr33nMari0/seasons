// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.Seasons;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.Scheduler;
import com.petepeterepeat.seasons.core.scenario.ScenarioClock;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.scenario.ScenarioScheduler;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.integrations.discord.DiscordWebhooks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/** The Seasons plugin's entry point: wires the Paper ports into the core. */
public final class SeasonsPlugin extends JavaPlugin {
  /** What the Paper adapter can do. Paper fires events for nearly every world change. */
  static final Capabilities CAPABILITIES =
      new Capabilities(
          EnumSet.of(
              Capability.MANNEQUINS,
              Capability.PER_PLAYER_WORLD_BORDER,
              Capability.FULL_PROTECTION_COVERAGE));

  private @Nullable ExecutorService async;
  private @Nullable Seasons seasons;

  @Override
  public void onEnable() {
    Server server = getServer();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    async = executor;

    PaperText text = new PaperText();
    PaperItemCodec codec = new PaperItemCodec();
    PaperPlayers players = new PaperPlayers(server, text, new NamespacedKey(this, "celebration"));
    NamespacedKey archived = new NamespacedKey(this, "archived");
    PaperInventories inventories = new PaperInventories(players, codec, archived);
    PaperDisplays displays =
        new PaperDisplays(server, text, codec, new NamespacedKey(this, "display"), archived);
    PaperAdvancements advancements = new PaperAdvancements(server, players);
    PaperEvents events = new PaperEvents(this, text, inventories, players, displays, advancements);
    PaperMenus menus = new PaperMenus(server, players, codec, text, archived);
    PaperStorage storage = new PaperStorage(this, server);
    Clock clock = Clock.systemUTC();
    Scheduler scheduler = new PaperScheduler(this, server, players, executor);
    // Only on a scenario test server, and only from the JVM's start-up flags (docs/adr/0003).
    Optional<ScenarioHooks> hooks = Optional.empty();
    if (Boolean.getBoolean(ScenarioHooks.PROPERTY)) {
      ScenarioClock movable = new ScenarioClock(clock);
      ScenarioScheduler catchingUp = new ScenarioScheduler(scheduler, movable);
      hooks =
          Optional.of(new ScenarioHooks(() -> Runtime.getRuntime().halt(0), movable, catchingUp));
      clock = movable;
      scheduler = catchingUp;
    }
    Platform platform =
        new Platform(
            "Paper",
            CAPABILITIES,
            players,
            inventories,
            advancements,
            new PaperWorld(),
            new PaperEntities(server),
            displays,
            menus,
            new PaperCommands(this, text),
            new PaperPermissions(players),
            events,
            scheduler,
            new PaperDragonFight(),
            new PaperStatistics(players),
            storage,
            new PaperImports(this, server, codec));

    server.getPluginManager().registerEvents(events, this);
    server.getPluginManager().registerEvents(events.hallGuard(), this);
    server.getPluginManager().registerEvents(events.zoneGuard(), this);
    server.getPluginManager().registerEvents(menus, this);
    server.getPluginManager().registerEvents(advancements, this);

    start(platform, clock, hooks);
    Clock seasonsClock = clock;
    Optional<ScenarioHooks> scenarioHooks = hooks;
    hooks.ifPresent(scenario -> {
      getSLF4JLogger()
          .warn(
              "Scenario hooks are on (-D{}=true). Never run a real server like this.",
              ScenarioHooks.PROPERTY);
      scenario.onReset(() -> restartFresh(platform, storage, seasonsClock, scenarioHooks));
      server
          .getServicesManager()
          .register(ScenarioHooks.class, scenario, this, ServicePriority.Normal);
    });
  }

  private void start(Platform platform, Clock clock, Optional<ScenarioHooks> hooks) {
    Seasons started =
        new Seasons(
            platform,
            getPluginMeta().getVersion(),
            clock,
            new DiscordWebhooks(),
            System.getenv(),
            new SerialWriter(),
            hooks);
    started.start();
    seasons = started;
    getServer()
        .getServicesManager()
        .register(SeasonsApi.class, started, this, ServicePriority.Normal);
  }

  /**
   * The scenario reset (docs/adr/0003): stops Seasons, deletes its config and data folders, and
   * starts it again as on a fresh install. Only the scenario hooks call this.
   */
  private void restartFresh(
      Platform platform, PaperStorage storage, Clock clock, Optional<ScenarioHooks> hooks) {
    Seasons running = seasons;
    if (running != null) {
      getServer().getServicesManager().unregister(SeasonsApi.class, running);
      running.stop();
      seasons = null;
    }
    deleteTree(storage.configDirectory());
    deleteTree(storage.worldDataDirectory());
    start(platform, clock, hooks);
  }

  private static void deleteTree(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Can't delete " + root, e);
    }
  }

  @Override
  public void onDisable() {
    getServer().getServicesManager().unregisterAll(this);
    Seasons running = seasons;
    if (running != null) {
      running.stop();
      seasons = null;
    }
    ExecutorService executor = async;
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          getSLF4JLogger().warn("Background work still running at shutdown");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
