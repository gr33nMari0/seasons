// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * {@code /scenario}: the scenario hooks Seasons offers only on a server started with {@code
 * -Dseasons.scenario=true} (docs/adr/0003).
 *
 * <ul>
 *   <li>{@code crash-at <POINT> <player>} halts the server at that step of archiving them
 *   <li>{@code archive <player> <season>} prints what their archive and record hold
 *   <li>{@code open-archive <viewer> <owner> <season>} opens an archive view
 * </ul>
 */
final class ScenarioCommands implements BasicCommand {
  private final Plugin plugin;

  ScenarioCommands(Plugin plugin) {
    this.plugin = plugin;
  }

  Optional<ScenarioHooks> hooks() {
    RegisteredServiceProvider<ScenarioHooks> registration =
        plugin.getServer().getServicesManager().getRegistration(ScenarioHooks.class);
    return Optional.ofNullable(registration).map(RegisteredServiceProvider::getProvider);
  }

  @Override
  public void execute(CommandSourceStack stack, String[] args) {
    CommandSender sender = stack.getSender();
    Optional<ScenarioHooks> found = hooks();
    if (found.isEmpty()) {
      sender.sendMessage("Scenario hooks are off: start the server with -Dseasons.scenario=true");
      return;
    }
    ScenarioHooks hooks = found.get();
    try {
      switch (args.length > 0 ? args[0] : "") {
        case "crash-at" -> {
          CrashPoint point = CrashPoint.valueOf(args[1].toUpperCase(Locale.ROOT).replace('-', '_'));
          hooks.arm(point, id(args[2]));
          sender.sendMessage("Armed crash point " + point + " for " + args[2]);
        }
        case "archive" -> {
          var _ =
              hooks
                  .summary(id(args[1]), args[1], Integer.parseInt(args[2]))
                  .whenComplete((lines, failure) ->
                      plugin
                          .getServer()
                          .getGlobalRegionScheduler()
                          .execute(
                              plugin,
                              () -> {
                                if (failure != null) {
                                  sender.sendMessage("Scenario archive failed: " + failure);
                                } else {
                                  lines.forEach(sender::sendMessage);
                                }
                              }));
        }
        case "open-archive" -> {
          Player viewer = plugin.getServer().getPlayerExact(args[1]);
          if (viewer == null) {
            sender.sendMessage(args[1] + " isn't online");
            return;
          }
          var _ =
              hooks
                  .openArchive(
                      new PlayerId(viewer.getUniqueId()), id(args[2]), Integer.parseInt(args[3]))
                  .whenComplete((opened, failure) ->
                      sender.sendMessage(
                          failure == null && opened
                              ? "Opened " + args[2] + "'s Season " + args[3] + " archive"
                              : "No archive to open: " + failure));
        }
        default -> sender.sendMessage("Usage: /scenario crash-at|archive|open-archive ...");
      }
    } catch (RuntimeException e) {
      sender.sendMessage("Scenario command failed: " + e);
    }
  }

  @Override
  public String permission() {
    return "seasons.scenario";
  }

  /** A player's id from their name: online, cached, or as an offline-mode server derives it. */
  private PlayerId id(String name) {
    Player online = plugin.getServer().getPlayerExact(name);
    if (online != null) {
      return new PlayerId(online.getUniqueId());
    }
    OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
    if (cached != null) {
      return new PlayerId(cached.getUniqueId());
    }
    return new PlayerId(
        UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
  }
}
