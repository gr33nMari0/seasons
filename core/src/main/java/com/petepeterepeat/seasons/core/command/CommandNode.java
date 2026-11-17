// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One literal in a command tree. A node runs its action when it is the last word typed, and is
 * shown only to sources that hold its permission.
 */
public record CommandNode(
    String literal,
    Optional<String> permission,
    List<CommandNode> children,
    Optional<CommandAction> action) {
  public CommandNode {
    if (literal.isBlank() || literal.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("A literal is one word: '" + literal + "'");
    }
    children = List.copyOf(children);
    if (children.isEmpty() && action.isEmpty()) {
      throw new IllegalArgumentException("'" + literal + "' has neither children nor an action");
    }
  }

  public static Builder literal(String literal) {
    return new Builder(literal);
  }

  /** Builds a {@link CommandNode}. */
  public static final class Builder {
    private final String literal;
    private @Nullable String permission;
    private final List<CommandNode> children = new ArrayList<>();
    private @Nullable CommandAction action;

    private Builder(String literal) {
      this.literal = literal;
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
          literal, Optional.ofNullable(permission), children, Optional.ofNullable(action));
    }
  }
}
