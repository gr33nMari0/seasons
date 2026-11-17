// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.Seasons;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.integrations.discord.DiscordWebhooks;
import java.time.Clock;
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
    PaperEvents events = new PaperEvents(this, text, inventories, players, displays);
    PaperMenus menus = new PaperMenus(server, players, codec, text, archived);
    PaperAdvancements advancements = new PaperAdvancements(server, players);
    PaperStorage storage = new PaperStorage(this, server);
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
            new PaperScheduler(this, server, players, executor),
            new PaperDragonFight(),
            new PaperStatistics(players),
            storage,
            new PaperImports(this, server, codec));

    server.getPluginManager().registerEvents(events, this);
    server.getPluginManager().registerEvents(menus, this);
    server.getPluginManager().registerEvents(advancements, this);

    // Only on a scenario test server, and only from the JVM's start-up flags (docs/adr/0003).
    Optional<ScenarioHooks> hooks =
        Boolean.getBoolean(ScenarioHooks.PROPERTY)
            ? Optional.of(new ScenarioHooks(() -> Runtime.getRuntime().halt(0)))
            : Optional.empty();
    Seasons started =
        new Seasons(
            platform,
            getPluginMeta().getVersion(),
            Clock.systemUTC(),
            new DiscordWebhooks(),
            System.getenv(),
            new SerialWriter(),
            hooks);
    started.start();
    seasons = started;
    server.getServicesManager().register(SeasonsApi.class, started, this, ServicePriority.Normal);
    hooks.ifPresent(scenario -> {
      getSLF4JLogger()
          .warn(
              "Scenario hooks are on (-D{}=true). Never run a real server like this.",
              ScenarioHooks.PROPERTY);
      server
          .getServicesManager()
          .register(ScenarioHooks.class, scenario, this, ServicePriority.Normal);
    });
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
