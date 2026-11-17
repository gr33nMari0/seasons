// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeasonsTest {
  private final List<CommandNode> registered = new ArrayList<>();
  private IntentHandler handler = intent -> Decision.deny(Message.of("unset"));

  private Seasons start(Capabilities capabilities) {
    Platform platform =
        new Platform(
            "Test",
            capabilities,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            registered::add,
            null,
            h -> handler = h,
            null,
            null,
            null,
            null);
    Seasons seasons = new Seasons(platform, "1.2.3", Messages.defaults());
    seasons.start();
    return seasons;
  }

  @Test
  void registersTheSeasonCommandWithAnAdminOnlyStatus() {
    start(new Capabilities(EnumSet.allOf(Capability.class)));

    assertThat(registered)
        .singleElement()
        .satisfies(root -> {
          assertThat(root.literal()).isEqualTo("season");
          assertThat(root.children())
              .anySatisfy(child -> {
                assertThat(child.literal()).isEqualTo("status");
                assertThat(child.permission()).contains(Seasons.PERMISSION_ADMIN_SEASON);
              });
        });
  }

  @Test
  void statusReportsVersionNoSeasonAndCapabilityGaps() {
    start(new Capabilities(EnumSet.of(Capability.MANNEQUINS)));
    List<Message> replies = new ArrayList<>();

    status()
        .run(
            new CommandSource() {
              @Override
              public Optional<PlayerId> player() {
                return Optional.empty();
              }

              @Override
              public void reply(Message message) {
                replies.add(message);
              }
            });

    Messages messages = Messages.defaults();
    assertThat(replies)
        .extracting(Message::template)
        .containsExactly(
            messages.get("status.header").template(),
            messages.get("status.no-season").template(),
            messages.get("status.capability-gap").template(),
            messages.get("status.capability-gap").template());
    assertThat(replies.getFirst().placeholders()).containsEntry("version", "1.2.3");
  }

  @Test
  void allowsEverythingForNow() {
    start(new Capabilities(EnumSet.noneOf(Capability.class)));
    PlayerId player = new PlayerId(UUID.randomUUID());

    assertThat(
            handler.decide(
                new Intent.BreakBlock(player, Position.of("minecraft:overworld", 0, 64, 0))))
        .isEqualTo(Decision.allow());
  }

  @Test
  void hasNoSeasonBeforeInit() {
    assertThat(start(new Capabilities(EnumSet.noneOf(Capability.class))).currentSeason()).isEmpty();
  }

  private com.petepeterepeat.seasons.core.command.CommandAction status() {
    return registered.getFirst().children().stream()
        .filter(child -> child.literal().equals("status"))
        .findFirst()
        .flatMap(CommandNode::action)
        .orElseThrow();
  }
}
