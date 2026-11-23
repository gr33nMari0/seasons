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
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** {@code /web link|sessions|logout|privacy} through Paper's Commands API, players only. */
final class PaperWebCommands {
  static final String PERMISSION = "seasons.web";
  static final String ADMIN_PERMISSION = "seasons.web.admin";

  private final Plugin plugin;
  private final Supplier<Web> web;
  private final Supplier<WebCommands> commands;

  PaperWebCommands(Plugin plugin, Supplier<Web> web, Supplier<WebCommands> commands) {
    this.plugin = plugin;
    this.web = web;
    this.commands = commands;
  }

  LiteralCommandNode<CommandSourceStack> node() {
    return Commands.literal("web")
        .requires(s -> s.getSender() instanceof Player && s.getSender().hasPermission(PERMISSION))
        .then(
            Commands.literal("link")
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
                                      p.hasPermission(ADMIN_PERMISSION),
                                      StringArgumentType.getString(c, "code")));
                        })))
        .then(
            Commands.literal("sessions")
                .executes(c -> tell(c, commands.get().sessions(player(c).getUniqueId()))))
        .then(
            Commands.literal("logout")
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
        .build();
  }

  private static Player player(CommandContext<CommandSourceStack> c) {
    return (Player) c.getSource().getSender();
  }

  private int tell(CommandContext<CommandSourceStack> c, List<Line> lines) {
    send(player(c), lines);
    return Command.SINGLE_SUCCESS;
  }

  private void send(Player player, List<Line> lines) {
    Messages messages = web.get().messages();
    for (Line line : lines) {
      TagResolver.Builder resolver = TagResolver.builder();
      line.values().forEach((k, v) -> resolver.resolver(Placeholder.unparsed(k, v)));
      player.sendMessage(
          MiniMessage.miniMessage().deserialize(messages.get(line.key()), resolver.build()));
    }
  }
}
