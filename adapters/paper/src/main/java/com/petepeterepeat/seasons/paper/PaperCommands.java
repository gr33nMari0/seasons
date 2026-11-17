// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Commands;
import com.petepeterepeat.seasons.core.port.PlayerId;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Registers core command trees with Brigadier through Paper's Commands API. */
final class PaperCommands implements Commands {
  private final List<CommandNode> roots = new ArrayList<>();
  private final PaperText text;

  PaperCommands(JavaPlugin plugin, PaperText text) {
    this.text = text;
    plugin
        .getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> {
              for (CommandNode root : roots) {
                event.registrar().register(toBrigadier(root).build());
              }
            });
  }

  @Override
  public void register(CommandNode root) {
    roots.add(root);
  }

  LiteralArgumentBuilder<CommandSourceStack> toBrigadier(CommandNode node) {
    LiteralArgumentBuilder<CommandSourceStack> builder =
        io.papermc.paper.command.brigadier.Commands.literal(node.literal());
    node.permission()
        .ifPresent(
            permission -> builder.requires(source -> source.getSender().hasPermission(permission)));
    node.action()
        .ifPresent(action ->
            builder.executes(context -> {
              action.run(source(context.getSource().getSender()));
              return Command.SINGLE_SUCCESS;
            }));
    for (CommandNode child : node.children()) {
      builder.then(toBrigadier(child));
    }
    return builder;
  }

  private CommandSource source(CommandSender sender) {
    return new CommandSource() {
      @Override
      public Optional<PlayerId> player() {
        return sender instanceof Player player
            ? Optional.of(new PlayerId(player.getUniqueId()))
            : Optional.empty();
      }

      @Override
      public void reply(Message message) {
        sender.sendMessage(text.render(message));
      }
    };
  }
}
