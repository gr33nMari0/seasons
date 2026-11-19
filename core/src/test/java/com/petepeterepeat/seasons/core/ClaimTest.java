// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** claim assignment and /hof claim (questions 167). */
class ClaimTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Position IN_C2 = Position.of(OVERWORLD, 11.5, 64, 5.5);

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef alex;
  private PlayerRef sam;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void setUp() throws IOException {
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    Files.writeString(
        galleries.resolve("season-2.yml"),
        """
        schema-version: 1
        id: season-2
        season: 2
        bounds: { min: [0, 60, 0], max: [30, 80, 20] }
        entry: { pos: [1, 64, 1] }
        assignment: claim
        cells:
          c1:
            bounds: { min: [4, 60, 4], max: [8, 70, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
          c2:
            bounds: { min: [10, 60, 4], max: [14, 70, 8] }
            anchors:
              - { type: figure, pos: [12, 64, 7] }
          c3:
            bounds: { min: [16, 60, 4], max: [20, 70, 8] }
            anchors:
              - { type: figure, pos: [18, 64, 7] }
        """);
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
    run("/season init 2");
    alex = join("Alex");
    sam = join("Sam");
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  private List<String> claim(PlayerRef player, String command) {
    TestSource source = new TestSource(Optional.of(player.id()));
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    fake.sessions.joined(player, false);
    return player;
  }

  private String owner(String cell) {
    return hof("/hof cell info " + cell).getFirst();
  }

  @Test
  void claimTakesAFreeCellInAClaimGalleryDuringTheWindow() {
    run("/season decorate open");
    assertThat(owner("c1")).as("nobody is handed a cell").contains("free");
    assertThat(fake.sent.stream().map(s -> s.message().template()))
        .anyMatch(t -> t.contains("Claim your Hall of Fame cell"));

    assertThat(claim(alex, "/hof claim c1"))
        .containsExactly("Your Hall of Fame cell for Season 2 is c1 in season-2.");
    assertThat(owner("c1")).contains("owner Alex");
  }

  @Test
  void eachClaimRefusal() {
    assertThat(claim(alex, "/hof claim c1"))
        .containsExactly("Cells can only be claimed while the decorating window is open.");
    run("/season decorate open");
    claim(alex, "/hof claim c1");
    assertThat(claim(sam, "/hof claim c1")).containsExactly("c1 is taken.");
    assertThat(claim(alex, "/hof claim c2"))
        .containsExactly("You already have a cell, c1. Swap it with /hof swap instead.");
    assertThat(claim(sam, "/hof claim c9")).containsExactly("There is no cell c9.");
    PlayerRef late = fake.join("Late");
    assertThat(claim(late, "/hof claim c2")).containsExactly("You haven't played Season 2 yet.");
    assertThat(claim(sam, "/hof claim"))
        .containsExactly("Free cells: c2, c3. Claim one with /hof claim [cell].");
  }

  @Test
  void claimWithNoCellTakesTheOneTheyStandIn() {
    run("/season decorate open");
    fake.player(sam).position = IN_C2;

    assertThat(claim(sam, "/hof claim"))
        .containsExactly("Your Hall of Fame cell for Season 2 is c2 in season-2.");
  }

  @Test
  void noCellAtArchiveTimeGetsARandomFreeOne() {
    run("/season decorate open");
    claim(alex, "/hof claim c1");

    run("/season end --now");

    assertThat(List.of(owner("c2"), owner("c3"))).anyMatch(info -> info.contains("owner Sam"));
  }

  @Test
  void claimsAreAudited() throws IOException {
    run("/season decorate open");
    claim(alex, "/hof claim c1");

    assertThat(Files.readString(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .contains("\"action\":\"claim\"", "\"target\":\"season-2:c1\"", "\"actor\":\"Alex\"");
  }
}
