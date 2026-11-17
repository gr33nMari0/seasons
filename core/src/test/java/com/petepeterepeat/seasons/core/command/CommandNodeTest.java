// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandNodeTest {
  @Test
  void buildsATree() {
    CommandNode root =
        CommandNode.literal("season")
            .then(
                CommandNode.literal("status")
                    .permission("seasons.admin.season")
                    .executes((s, a) -> {}))
            .then(
                CommandNode.literal("init")
                    .then(
                        CommandNode.argument("season", ArgumentType.integer(1, 99))
                            .executes((s, a) -> {})))
            .build();

    assertThat(root.name()).isEqualTo("season");
    assertThat(root.isLiteral()).isTrue();
    assertThat(root.permission()).isEmpty();
    assertThat(root.children())
        .first()
        .satisfies(child -> {
          assertThat(child.name()).isEqualTo("status");
          assertThat(child.permission()).contains("seasons.admin.season");
          assertThat(child.action()).isPresent();
        });
    assertThat(root.children().get(1).children())
        .singleElement()
        .satisfies(arg -> {
          assertThat(arg.isLiteral()).isFalse();
          assertThat(arg.argument()).contains(new ArgumentType.IntegerArgument(1, 99));
        });
  }

  @Test
  void refusesAWordWithSpaces() {
    assertThatThrownBy(() -> CommandNode.literal("season end").executes((s, a) -> {}).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesALeafWithNoAction() {
    assertThatThrownBy(() -> CommandNode.literal("status").build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aGreedyArgumentEndsTheLine() {
    assertThatThrownBy(() ->
            CommandNode.argument("rest", ArgumentType.greedy())
                .then(CommandNode.literal("more").executes((s, a) -> {}))
                .build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void argumentsByName() {
    Arguments arguments = new Arguments(Map.of("season", 2, "time", "2026-12-05T20:00Z"));

    assertThat(arguments.integer("season")).isEqualTo(2);
    assertThat(arguments.string("time")).isEqualTo("2026-12-05T20:00Z");
    assertThat(arguments.optionalString("options")).isEmpty();
    assertThatThrownBy(() -> arguments.string("missing"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flags() {
    Set<String> allowed = Set.of("--dry-run", "--now");

    assertThat(Flags.parse(Optional.empty(), allowed)).isEqualTo(new Flags.Parsed.Ok(Set.of()));
    assertThat(Flags.parse(Optional.of(" --now  --dry-run "), allowed))
        .isEqualTo(new Flags.Parsed.Ok(Set.of("--now", "--dry-run")));
    assertThat(Flags.parse(Optional.of("--now --force"), allowed))
        .isEqualTo(new Flags.Parsed.Unknown("--force"));
  }
}
