// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One word in a command tree: a literal such as {@code end}, or an argument such as {@code
 * <season>}. A node runs its action when it is the last word typed, and is shown only to sources
 * that hold its permission.
 *
 * @param name the literal itself, or the argument's name
 * @param argument empty for a literal
 */
public record CommandNode(
    String name,
    Optional<ArgumentType> argument,
    Optional<String> permission,
    List<CommandNode> children,
    Optional<CommandAction> action) {
  public CommandNode {
    if (name.isBlank() || name.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("A command word is one word: '" + name + "'");
    }
    children = List.copyOf(children);
    if (children.isEmpty() && action.isEmpty()) {
      throw new IllegalArgumentException("'" + name + "' has neither children nor an action");
    }
    if (argument.orElse(null) instanceof ArgumentType.GreedyArgument && !children.isEmpty()) {
      throw new IllegalArgumentException(
          "'" + name + "' takes the rest of the line, so it ends it");
    }
  }

  public boolean isLiteral() {
    return argument.isEmpty();
  }

  public static Builder literal(String literal) {
    return new Builder(literal, null);
  }

  public static Builder argument(String name, ArgumentType type) {
    return new Builder(name, type);
  }

  /** Builds a {@link CommandNode}. */
  public static final class Builder {
    private final String name;
    private final @Nullable ArgumentType argument;
    private @Nullable String permission;
    private final List<CommandNode> children = new ArrayList<>();
    private @Nullable CommandAction action;

    private Builder(String name, @Nullable ArgumentType argument) {
      this.name = name;
      this.argument = argument;
    }

    public Builder permission(String node) {
      this.permission = node;
      return this;
    }

    public Builder then(CommandNode child) {
      children.add(child);
      return this;
    }

    public Builder then(Builder child) {
      return then(child.build());
    }

    public Builder executes(CommandAction action) {
      this.action = action;
      return this;
    }

    public CommandNode build() {
      return new CommandNode(
          name,
          Optional.ofNullable(argument),
          Optional.ofNullable(permission),
          children,
          Optional.ofNullable(action));
    }
  }
}
