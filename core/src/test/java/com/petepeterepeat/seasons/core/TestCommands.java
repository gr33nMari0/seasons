// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runs command lines against a command tree, as an adapter would. */
final class TestCommands {
  private TestCommands() {}

  static void run(CommandNode root, TestSource source, String command) {
    CommandNode node = root;
    String[] words = command.split(" ");
    Map<String, Object> values = new HashMap<>();
    for (int i = 1; i < words.length; i++) {
      String word = words[i];
      Optional<CommandNode> literal =
          node.children().stream().filter(c -> c.isLiteral() && c.name().equals(word)).findFirst();
      if (literal.isPresent()) {
        node = literal.get();
        continue;
      }
      CommandNode argument =
          node.children().stream()
              .filter(c -> !c.isLiteral())
              .findFirst()
              .orElseThrow(() -> new AssertionError("no command at '" + word + "' in " + command));
      if (argument.argument().get() instanceof ArgumentType.GreedyArgument) {
        values.put(argument.name(), String.join(" ", List.of(words).subList(i, words.length)));
        node = argument;
        break;
      }
      values.put(
          argument.name(),
          argument.argument().get() instanceof ArgumentType.IntegerArgument
              ? (Object) Integer.parseInt(word)
              : word);
      node = argument;
    }
    node.action().orElseThrow().run(source, new Arguments(values));
  }
}
