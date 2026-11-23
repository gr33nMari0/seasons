// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.petepeterepeat.seasons.api.SeasonsApi;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bstats.bukkit.Metrics;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The Seasons Web plugin's entry point: checks Seasons API, then runs {@link Web}, its {@code /web}
 * commands, the server-health sampler and the admin check. Reads Seasons only through Seasons API,
 * and changes nothing in the world.
 */
public final class SeasonsWebPlugin extends JavaPlugin implements Listener {
  /**
   * Seasons Web's plugin id on bstats.org ("SeasonsMC Web"). It sends only what bStats always does;
   * whether Seasons Web is installed is one of Seasons' own charts.
   */
  static final int BSTATS_PLUGIN_ID = 35500;

  /** How often online players' seasons.web.admin is looked at again. */
  static final Duration ADMIN_CHECK = Duration.ofSeconds(30);

  /** How often server health is sampled. */
  static final Duration HEALTH_SAMPLE = Duration.ofSeconds(5);

  private @Nullable Metrics metrics;
  private @Nullable Web web;
  private @Nullable WebCommands commands;
  private volatile Optional<SeasonsApi> api = Optional.empty();
  private volatile Optional<Routes.Health> health = Optional.empty();
  private @Nullable ScheduledTask adminCheck;
  private @Nullable ScheduledTask sampler;

  @Override
  public void onEnable() {
    Optional<SeasonsApi> found = registered();
    if (found.isEmpty()) {
      getSLF4JLogger().error("Seasons API is not registered; disabling Seasons Web");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    Optional<String> apiVersion = ApiVersions.of(found.get());
    if (apiVersion.filter(ApiVersions::supported).isEmpty()) {
      getSLF4JLogger()
          .error(
              "Seasons Web {} needs Seasons API {}, but Seasons {} has {}; disabling Seasons Web",
              getPluginMeta().getVersion(),
              ApiVersions.RANGE,
              found.get().version(),
              apiVersion.orElse("an older one, from before 1.2"));
      getServer().getPluginManager().disablePlugin(this);
      return;
    }
    api = found;
    boolean scenario = Boolean.getBoolean("seasons.scenario");
    Clock clock = scenario ? new OffsetClock(Clock.systemUTC()) : Clock.systemUTC();
    Path world = getServer().getWorlds().getFirst().getWorldFolder().toPath();
    Web running =
        new Web(
            getDataPath(),
            world.resolve("seasons-web"),
            getSLF4JLogger(),
            clock,
            () -> api,
            () -> health,
            () -> PlainTextComponentSerializer.plainText().serialize(getServer().motd()).strip(),
            getPluginMeta().getVersion());
    web = running;
    commands = new WebCommands(running, () -> api, clock);
    running.start();
    running.follow(api);
    var _ = running.reload();
    getServer().getPluginManager().registerEvents(this, this);
    PaperWebCommands paper =
        new PaperWebCommands(this, () -> requireWeb(), () -> requireCommands());
    getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event ->
                event
                    .registrar()
                    .register(paper.node(), "Seasons Web: link this dashboard, devices, privacy"));
    adminCheck =
        getServer()
            .getGlobalRegionScheduler()
            .runAtFixedRate(this, t -> checkAdmins(), 20, ADMIN_CHECK.toSeconds() * 20);
    sampler =
        getServer()
            .getGlobalRegionScheduler()
            .runAtFixedRate(this, t -> sample(), 1, HEALTH_SAMPLE.toSeconds() * 20);
    if (scenario) {
      getSLF4JLogger()
          .warn("Scenario hooks are on (-Dseasons.scenario=true); never on a real server");
      OffsetClock moved = (OffsetClock) clock;
      getServer()
          .getServicesManager()
          .register(
              WebScenarioHooks.class,
              new WebScenarioHooks() {
                @Override
                public String reload() {
                  return requireWeb().reload();
                }

                @Override
                public void reset() {
                  moved.resetOffset();
                  requireWeb().reset();
                }

                @Override
                public void advance(Duration by) {
                  moved.advance(by);
                }
              },
              this,
              ServicePriority.Normal);
    }
    metrics = new Metrics(this, BSTATS_PLUGIN_ID);
  }

  private Web requireWeb() {
    Web running = web;
    if (running == null) {
      throw new IllegalStateException("Seasons Web isn't running");
    }
    return running;
  }

  private WebCommands requireCommands() {
    WebCommands running = commands;
    if (running == null) {
      throw new IllegalStateException("Seasons Web isn't running");
    }
    return running;
  }

  private Optional<SeasonsApi> registered() {
    RegisteredServiceProvider<SeasonsApi> provider =
        getServer().getServicesManager().getRegistration(SeasonsApi.class);
    return provider == null ? Optional.empty() : Optional.of(provider.getProvider());
  }

  /** Seasons registers its API again after a scenario reset; follow whichever is registered. */
  @EventHandler
  void onRegister(ServiceRegisterEvent event) {
    if (event.getProvider().getService() == SeasonsApi.class) {
      follow();
    }
  }

  @EventHandler
  void onUnregister(ServiceUnregisterEvent event) {
    if (event.getProvider().getService() == SeasonsApi.class) {
      follow();
    }
  }

  private void follow() {
    api = registered();
    Web running = web;
    if (running != null) {
      running.follow(api);
    }
  }

  /** A player who joins is checked for seasons.web.admin at once. */
  @EventHandler
  void onJoin(PlayerJoinEvent event) {
    Web running = web;
    if (running != null) {
      Player p = event.getPlayer();
      running
          .sessions()
          .setAdmin(p.getUniqueId(), p.hasPermission(PaperWebCommands.ADMIN_PERMISSION));
    }
  }

  /**
   * Whether each online player holds seasons.web.admin, kept on their sessions; an offline player
   * keeps what they had when last seen (docs/questions.md 299). Server thread.
   */
  private void checkAdmins() {
    Web running = web;
    if (running == null) {
      return;
    }
    for (Player p : getServer().getOnlinePlayers()) {
      running
          .sessions()
          .setAdmin(p.getUniqueId(), p.hasPermission(PaperWebCommands.ADMIN_PERMISSION));
    }
  }

  /** Server health for admins, sampled on the server thread. */
  private void sample() {
    Runtime runtime = Runtime.getRuntime();
    health =
        Optional.of(
            new Routes.Health(
                java.util.Arrays.stream(getServer().getTPS()).boxed().toList(),
                getServer().getAverageTickTime(),
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory()));
  }

  @Override
  public void onDisable() {
    if (adminCheck != null) {
      adminCheck.cancel();
    }
    if (sampler != null) {
      sampler.cancel();
    }
    Web running = web;
    web = null;
    if (running != null) {
      running.close();
    }
    Metrics started = metrics;
    if (started != null) {
      started.shutdown();
      metrics = null;
    }
  }
}
