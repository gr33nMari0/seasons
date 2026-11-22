// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Suggester;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Commands;
import com.petepeterepeat.seasons.core.port.PlayerId;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Registers core command trees with Brigadier through Paper's Commands API. */
final class PaperCommands implements Commands {
  /**
   * The command trees by root name. Commands run whatever tree is registered under their path when
   * they run, so a Seasons started again by the scenario reset (docs/adr/0003) answers the commands
   * Brigadier already knows.
   */
  private final Map<String, Root> roots = new LinkedHashMap<>();

  private record Root(CommandNode node, List<String> aliases) {}

  private final PaperText text;

  PaperCommands(JavaPlugin plugin, PaperText text) {
    this.text = text;
    plugin
        .getLifecycleManager()
        .registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> {
              for (Root root : roots.values()) {
                if (toBrigadier(root.node(), List.of(), List.of()).build()
                    instanceof LiteralCommandNode<CommandSourceStack> literal) {
                  event.registrar().register(literal, null, root.aliases());
                }
              }
            });
  }

  @Override
  public void register(CommandNode root, List<String> aliases) {
    roots.put(root.name(), new Root(root, List.copyOf(aliases)));
  }

  /** The node now registered at {@code path}, root name first. */
  private Optional<CommandNode> current(List<String> path) {
    Root root = roots.get(path.getFirst());
    if (root == null) {
      return Optional.empty();
    }
    CommandNode node = root.node();
    for (String name : path.subList(1, path.size())) {
      Optional<CommandNode> child =
          node.children().stream().filter(c -> c.name().equals(name)).findFirst();
      if (child.isEmpty()) {
        return Optional.empty();
      }
      node = child.get();
    }
    return Optional.of(node);
  }

  ArgumentBuilder<CommandSourceStack, ?> toBrigadier(
      CommandNode node, List<CommandNode> scope, List<String> parents) {
    List<String> path = new ArrayList<>(parents);
    path.add(node.name());
    ArgumentBuilder<CommandSourceStack, ?> builder =
        switch (node.argument().orElse(null)) {
          case null -> io.papermc.paper.command.brigadier.Commands.literal(node.name());
          case ArgumentType.IntegerArgument integer ->
              suggesting(
                  io.papermc.paper.command.brigadier.Commands.argument(
                      node.name(), IntegerArgumentType.integer(integer.min(), integer.max())),
                  node,
                  path);
          case ArgumentType.WordArgument word ->
              suggesting(
                  io.papermc.paper.command.brigadier.Commands.argument(
                      node.name(), StringArgumentType.word()),
                  node,
                  path);
          case ArgumentType.IdArgument id ->
              suggesting(
                  io.papermc.paper.command.brigadier.Commands.argument(
                      node.name(), new IdArgument()),
                  node,
                  path);
          case ArgumentType.GreedyArgument greedy ->
              suggesting(
                  io.papermc.paper.command.brigadier.Commands.argument(
                      node.name(), StringArgumentType.greedyString()),
                  node,
                  path);
        };
    List<CommandNode> inScope = new ArrayList<>(scope);
    if (!node.isLiteral()) {
      inScope.add(node);
    }
    node.permission()
        .ifPresent(
            permission -> builder.requires(source -> source.getSender().hasPermission(permission)));
    if (node.action().isPresent()) {
      builder.executes(context -> {
        current(path)
            .flatMap(CommandNode::action)
            .ifPresent(action ->
                action.run(source(context.getSource().getSender()), arguments(context, inScope)));
        return Command.SINGLE_SUCCESS;
      });
    }
    for (CommandNode child : node.children()) {
      builder.then(toBrigadier(child, inScope, path));
    }
    return builder;
  }

  /**
   * An argument with its tab completion (questions 266): the core's words for the last word typed,
   * those that start with it.
   */
  private <T> RequiredArgumentBuilder<CommandSourceStack, T> suggesting(
      RequiredArgumentBuilder<CommandSourceStack, T> argument,
      CommandNode node,
      List<String> path) {
    if (node.suggester().isEmpty()) {
      return argument;
    }
    return argument.suggests((context, suggestions) -> {
      Optional<Suggester> suggester = current(path).flatMap(CommandNode::suggester);
      if (suggester.isEmpty()) {
        return suggestions.buildFuture();
      }
      String typed = suggestions.getRemaining();
      int last = typed.lastIndexOf(' ') + 1;
      String word = typed.substring(last).toLowerCase(java.util.Locale.ROOT);
      SuggestionsBuilder offset = suggestions.createOffset(suggestions.getStart() + last);
      for (String candidate :
          suggester.get().suggest(source(context.getSource().getSender()), typed)) {
        if (candidate.toLowerCase(java.util.Locale.ROOT).startsWith(word)) {
          offset.suggest(candidate);
        }
      }
      return offset.buildFuture();
    });
  }

  private static Arguments arguments(
      CommandContext<CommandSourceStack> context, List<CommandNode> scope) {
    Map<String, Object> values = new HashMap<>();
    for (CommandNode argument : scope) {
      Class<?> type =
          argument.argument().orElseThrow() instanceof ArgumentType.IntegerArgument
              ? Integer.class
              : String.class;
      values.put(argument.name(), context.getArgument(argument.name(), type));
    }
    return new Arguments(values);
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

  /**
   * One word that may hold a colon, such as {@code season-2:c1}, which Brigadier's own word
   * refuses. Clients see it as a resource key, which allows the same characters.
   */
  private static final class IdArgument implements CustomArgumentType<String, Key> {
    @Override
    public String parse(StringReader reader) {
      int start = reader.getCursor();
      while (reader.canRead() && reader.peek() != ' ') {
        reader.skip();
      }
      return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public com.mojang.brigadier.arguments.ArgumentType<Key> getNativeType() {
      return ArgumentTypes.key();
    }
  }
}
