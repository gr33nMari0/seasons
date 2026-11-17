// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Scheduler;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/**
 * Runs core work through Paper's region-aware schedulers, which also exist on Folia, and slow work
 * on Seasons' own virtual-thread executor.
 */
final class PaperScheduler implements Scheduler {
  private static final long MILLIS_PER_TICK = 50;

  private final Plugin plugin;
  private final Server server;
  private final PaperPlayers players;
  private final ExecutorService async;

  PaperScheduler(Plugin plugin, Server server, PaperPlayers players, ExecutorService async) {
    this.plugin = plugin;
    this.server = server;
    this.players = players;
    this.async = async;
  }

  @Override
  public void runGlobal(Runnable task) {
    server.getGlobalRegionScheduler().execute(plugin, task);
  }

  @Override
  public void runGlobalLater(Duration delay, Runnable task) {
    long ticks = Math.max(1, delay.toMillis() / MILLIS_PER_TICK);
    server.getGlobalRegionScheduler().runDelayed(plugin, scheduled -> task.run(), ticks);
  }

  @Override
  public void runForPlayer(PlayerId id, Runnable task) {
    players
        .player(id)
        .ifPresent(player -> player.getScheduler().run(plugin, scheduled -> task.run(), null));
  }

  @Override
  public void runAt(Position position, Runnable task) {
    server.getRegionScheduler().execute(plugin, PaperPositions.toLocation(position), task);
  }

  @Override
  public void runAsync(Runnable task) {
    async.execute(task);
  }
}
