// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.core.Seasons;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Platform;
import java.util.EnumSet;
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

  @Override
  public void onEnable() {
    Server server = getServer();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    async = executor;

    PaperText text = new PaperText();
    PaperItemCodec codec = new PaperItemCodec();
    PaperPlayers players = new PaperPlayers(server, text);
    PaperEvents events = new PaperEvents(text);
    PaperMenus menus = new PaperMenus(server, players, codec, text);
    PaperStorage storage = new PaperStorage(this, server);
    Platform platform =
        new Platform(
            "Paper",
            CAPABILITIES,
            players,
            new PaperInventories(players, codec),
            new PaperAdvancements(server, players),
            new PaperWorld(),
            new PaperEntities(server),
            new PaperDisplays(server, text, new NamespacedKey(this, "display")),
            menus,
            new PaperCommands(this, text),
            new PaperPermissions(players),
            events,
            new PaperScheduler(this, server, players, executor),
            new PaperDragonFight(),
            new PaperStatistics(players),
            storage);

    server.getPluginManager().registerEvents(events, this);
    server.getPluginManager().registerEvents(menus, this);

    Seasons seasons =
        new Seasons(
            platform, getPluginMeta().getVersion(), Messages.load(storage.configDirectory()));
    seasons.start();
    server.getServicesManager().register(SeasonsApi.class, seasons, this, ServicePriority.Normal);
  }

  @Override
  public void onDisable() {
    getServer().getServicesManager().unregisterAll(this);
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
