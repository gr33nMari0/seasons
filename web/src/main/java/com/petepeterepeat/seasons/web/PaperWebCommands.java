// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /web link|sessions|logout|privacy} for players, and {@code /web
 * discovery|reload|check|upgrade} for admins and the console, through Paper's Commands API.
 */
final class PaperWebCommands {
  static final String PERMISSION = "seasons.web";
  static final String ADMIN_PERMISSION = "seasons.web.admin";
  static final String DISCOVERY_PERMISSION = "seasons.web.discovery";
  static final String SETUP_PERMISSION = "seasons.web.admin.setup";
  static final String RELOAD_PERMISSION = "seasons.web.admin.reload";

  private final Plugin plugin;
  private final Supplier<Web> web;
  private final Supplier<WebCommands> commands;
  private final Supplier<DiscoveryLookups> lookups;

  PaperWebCommands(
      Plugin plugin,
      Supplier<Web> web,
      Supplier<WebCommands> commands,
      Supplier<DiscoveryLookups> lookups) {
    this.plugin = plugin;
    this.web = web;
    this.commands = commands;
    this.lookups = lookups;
  }

  /** A player who holds {@code seasons.web}: the player commands. */
  private static boolean playerWithWeb(CommandSourceStack s) {
    return s.getSender() instanceof Player && s.getSender().hasPermission(PERMISSION);
  }

  /** The console, or a holder of {@code seasons.web.discovery}: the discovery commands. */
  private static boolean mayDiscover(CommandSourceStack s) {
    return !(s.getSender() instanceof Player) || s.getSender().hasPermission(DISCOVERY_PERMISSION);
  }

  /** The console, or a holder of {@code seasons.web.admin.reload}: reload and upgrade. */
  private static boolean mayReload(CommandSourceStack s) {
    return !(s.getSender() instanceof Player) || s.getSender().hasPermission(RELOAD_PERMISSION);
  }

  /** The console, or a holder of {@code seasons.web.admin.setup}: the check. */
  private static boolean mayCheck(CommandSourceStack s) {
    return !(s.getSender() instanceof Player) || s.getSender().hasPermission(SETUP_PERMISSION);
  }

  LiteralCommandNode<CommandSourceStack> node() {
    return Commands.literal("web")
        .requires(s -> playerWithWeb(s) || mayDiscover(s) || mayReload(s) || mayCheck(s))
        .then(
            Commands.literal("reload")
                .requires(PaperWebCommands::mayReload)
                .executes(c -> tell(c, commands.get().reload())))
        .then(
            Commands.literal("check")
                .requires(PaperWebCommands::mayCheck)
                .executes(c -> {
                  CommandSender sender = c.getSource().getSender();
                  send(sender, List.of(Line.of("check.checking")));
                  DiscoveryLookups look = lookups.get();
                  var _ =
                      commands
                          .get()
                          .check(look, look, look)
                          .thenAccept(lines ->
                              plugin
                                  .getServer()
                                  .getGlobalRegionScheduler()
                                  .execute(plugin, () -> send(sender, lines)));
                  return Command.SINGLE_SUCCESS;
                }))
        .then(
            Commands.literal("upgrade")
                .requires(PaperWebCommands::mayReload)
                .executes(c -> tell(c, commands.get().upgrade(false)))
                .then(
                    Commands.literal("--dry-run")
                        .executes(c -> tell(c, commands.get().upgrade(true)))))
        .then(
            Commands.literal("link")
                .requires(PaperWebCommands::playerWithWeb)
                .executes(c -> tell(c, commands.get().linkUsage()))
                .then(
                    Commands.argument("code", StringArgumentType.greedyString())
                        .executes(c -> {
                          Player p = player(c);
                          return tell(
                              c,
                              commands
                                  .get()
                                  .link(
                                      p.getUniqueId(),
                                      p.getName(),
                                      Grants.from(p::hasPermission),
                                      StringArgumentType.getString(c, "code")));
                        })))
        .then(
            Commands.literal("sessions")
                .requires(PaperWebCommands::playerWithWeb)
                .executes(c -> tell(c, commands.get().sessions(player(c).getUniqueId()))))
        .then(
            Commands.literal("logout")
                .requires(PaperWebCommands::playerWithWeb)
                .executes(c -> {
                  Player p = player(c);
                  return tell(
                      c, commands.get().logout(p.getUniqueId(), p.getName(), Optional.empty()));
                })
                .then(
                    Commands.argument("device", StringArgumentType.word())
                        .executes(c -> {
                          Player p = player(c);
                          return tell(
                              c,
                              commands
                                  .get()
                                  .logout(
                                      p.getUniqueId(),
                                      p.getName(),
                                      Optional.of(StringArgumentType.getString(c, "device"))));
                        })))
        .then(
            Commands.literal("privacy")
                .requires(PaperWebCommands::playerWithWeb)
                .executes(c -> {
                  Player p = player(c);
                  var _ =
                      commands
                          .get()
                          .privacy(p.getUniqueId())
                          .thenAccept(lines ->
                              plugin
                                  .getServer()
                                  .getGlobalRegionScheduler()
                                  .execute(plugin, () -> send(p, lines)));
                  return Command.SINGLE_SUCCESS;
                }))
        .then(
            Commands.literal("discovery")
                .requires(PaperWebCommands::mayDiscover)
                .executes(c -> tell(c, commands.get().discovery()))
                .then(
                    Commands.literal("check")
                        .executes(c -> {
                          CommandSender sender = c.getSource().getSender();
                          send(sender, List.of(Line.of("discovery.checking")));
                          DiscoveryLookups look = lookups.get();
                          var _ =
                              commands
                                  .get()
                                  .discoveryCheck(look, look)
                                  .thenAccept(lines ->
                                      plugin
                                          .getServer()
                                          .getGlobalRegionScheduler()
                                          .execute(plugin, () -> send(sender, lines)));
                          return Command.SINGLE_SUCCESS;
                        })))
        .build();
  }

  private static Player player(CommandContext<CommandSourceStack> c) {
    return (Player) c.getSource().getSender();
  }

  private int tell(CommandContext<CommandSourceStack> c, List<Line> lines) {
    send(c.getSource().getSender(), lines);
    return Command.SINGLE_SUCCESS;
  }

  private void send(CommandSender player, List<Line> lines) {
    Messages messages = web.get().messages();
    for (Line line : lines) {
      TagResolver.Builder resolver = TagResolver.builder();
      line.values().forEach((k, v) -> resolver.resolver(Placeholder.unparsed(k, v)));
      player.sendMessage(
          MiniMessage.miniMessage().deserialize(messages.get(line.key()), resolver.build()));
    }
  }
}
