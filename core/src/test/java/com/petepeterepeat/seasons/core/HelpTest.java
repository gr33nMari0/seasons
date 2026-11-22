// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Help from the command trees and next-step hints (questions 267 and 268, A64). */
class HelpTest {
  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef alex;

  @BeforeEach
  void setUp() {
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            Webhooks.NONE,
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
    alex = fake.join("Alex");
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private List<String> season(TestSource source, String command) {
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private List<String> hof(TestSource source, String command) {
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  @Test
  void everyCommandNodeHasHelpText() {
    CommandHelp help = seasons.commandHelp().orElseThrow();
    Messages messages = Messages.defaults();
    // An argument's wording may be for its command, its root or any command.
    assertThat(help.keys())
        .isNotEmpty()
        .allMatch(
            key -> java.util.Arrays.stream(key.split("\\|")).anyMatch(messages::has),
            "is in messages.yml");
  }

  @Test
  void usageNamesEachArgumentsTypeAndChoices() {
    List<String> lines = hof(TestSource.console(), "/hof help cell");
    assertThat(lines.getFirst()).isEqualTo("Seasons help: /hof cell");
    assertThat(lines)
        .anyMatch(l -> l.matches("/hof cell bounds <cell: [^>]+> \\[<corners: [^>]+>\\] - \\S.*"))
        .anyMatch(l -> l.matches("/hof cell gate <cell: [^>]+> <state: \\(open\\|shut\\)> - \\S.*"))
        .anyMatch(l ->
            l.startsWith(
                "/hof cell anchor <cell: gallery:cell> <type:"
                    + " (figure|archive|plaque|trophy|entry|gate)> [<at:"))
        .anyMatch(l ->
            l.matches("/hof cell assign <cell: [^>]+> <player: [^>]+> \\[<season: [^>]+>\\] - .*"));
    assertThat(season(TestSource.console(), "/season help"))
        .anyMatch(l -> l.startsWith("/season end [<options: "))
        .anyMatch(l -> l.startsWith("/season spawn set at <at: "));
  }

  @Test
  void partOfACommandListsWhatFollowsAndAWrongWordSaysSo() {
    assertThat(hof(TestSource.console(), "/hof cell")).first().isEqualTo("Seasons help: /hof cell");
    assertThat(hof(TestSource.console(), "/hof cell bogus"))
        .startsWith("There's no /hof cell bogus.", "Seasons help: /hof cell");
    // An argument part on its own is its command's help.
    assertThat(hof(TestSource.console(), "/hof cell anchor season-2:c1"))
        .first()
        .isEqualTo("Seasons help: /hof cell anchor");
  }

  @Test
  void aPlayerSeesOnlyWhatTheyMayRun() {
    fake.denied.put(
        alex.id(),
        java.util.Set.of("seasons.admin.hof", "seasons.admin.season", "seasons.admin.config"));
    List<String> lines = hof(new TestSource(Optional.of(alex.id())), "/hof help");
    assertThat(lines).anyMatch(l -> l.startsWith("/hof leave - "));
    assertThat(lines).noneMatch(l -> l.startsWith("/hof gallery") || l.startsWith("/hof cell"));
  }

  @Test
  void nextStepsFollowSetupStepsForThoseWhoMayRunThem() {
    season(TestSource.console(), "/season init 2");
    TestSource admin = new TestSource(Optional.of(alex.id())).withHints();
    hof(admin, "/hof gallery create season-3 3");
    assertThat(admin.text())
        .containsExactly(
            "Created gallery season-3 for Season 3.",
            "Next: /hof gallery bounds season-3, /hof gallery entry season-3");
    // The console gets no hints.
    assertThat(hof(TestSource.console().withHints(), "/hof gallery create season-4 4"))
        .containsExactly("Created gallery season-4 for Season 4.");
  }
}
