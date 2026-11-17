// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommandNodeTest {
  @Test
  void buildsATree() {
    CommandNode root =
        CommandNode.literal("season")
            .then(
                CommandNode.literal("status").permission("seasons.admin.season").executes(s -> {}))
            .build();

    assertThat(root.literal()).isEqualTo("season");
    assertThat(root.permission()).isEmpty();
    assertThat(root.children())
        .singleElement()
        .satisfies(child -> {
          assertThat(child.literal()).isEqualTo("status");
          assertThat(child.permission()).contains("seasons.admin.season");
          assertThat(child.action()).isPresent();
        });
  }

  @Test
  void refusesALiteralWithSpaces() {
    assertThatThrownBy(() -> CommandNode.literal("season end").executes(s -> {}).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesALeafWithNoAction() {
    assertThatThrownBy(() -> CommandNode.literal("status").build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
