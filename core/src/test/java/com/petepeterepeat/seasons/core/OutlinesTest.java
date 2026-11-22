// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
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

/** The Hall's outlines for admins, and the wand's action bar (questions 273 and 276, A68). */
class OutlinesTest {
  private static final String OVERWORLD = "minecraft:overworld";

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef admin;
  private PlayerRef visitor;

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
        bounds: { min: [0, 60, 0], max: [40, 80, 20] }
        entry: { pos: [1, 64, 1] }
        cells:
          c1:
            bounds: { min: [4, 64, 4], max: [8, 69, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
        """);
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
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), "/season init 2");
    admin = fake.join("Admin");
    visitor = fake.join("Visitor");
    fake.denied.put(visitor.id(), java.util.Set.of("seasons.admin.hof"));
    fake.player(admin).position = Position.of(OVERWORLD, 10.5, 64, 10.5);
    fake.player(visitor).position = Position.of(OVERWORLD, 10.5, 64, 10.5);
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private void hof(PlayerRef player, String command) {
    TestCommands.run(fake.commands.get(1), new TestSource(Optional.of(player.id())), command);
  }

  private List<String> kinds(PlayerRef player) {
    return fake.outlined.getOrDefault(player.id(), List.of()).stream()
        .map(o -> o.kind() + o.label().map(l -> " " + l).orElse(""))
        .toList();
  }

  @Test
  void anAdminHoldingTheWandSeesTheHallAndNobodyElseDoes() {
    hof(admin, "/hof wand");
    fake.tick();
    assertThat(kinds(admin)).contains("gallery season-2", "cell season-2:c1", "entry");
    assertThat(kinds(visitor)).isEmpty();
    assertThat(fake.player(admin).actionBars)
        .extracting(TestSource::plain)
        .contains("Wand (block mode): a selection only");

    // The wand's cell is told apart, and named on the action bar.
    hof(admin, "/hof cell new season-2 c2");
    fake.tick();
    assertThat(fake.player(admin).actionBars)
        .extracting(TestSource::plain)
        .contains("Wand (block mode): next selection sets season-2:c2's bounds");

    // Put away, and nothing is shown; /hof show on shows it without the wand.
    fake.wandAway.add(admin.id());
    fake.tick();
    assertThat(kinds(admin)).isEmpty();
    hof(admin, "/hof show on");
    assertThat(kinds(admin)).contains("cell season-2:c1");
    hof(admin, "/hof show off");
    assertThat(kinds(admin)).isEmpty();
  }

  @Test
  void onlyWhatIsInRangeNearestFirst() {
    hof(admin, "/hof show on");
    fake.player(admin).position = Position.of(OVERWORLD, 500.5, 64, 500.5);
    fake.tick();
    assertThat(fake.outlined.get(admin.id())).isNull();
    fake.player(admin).position = Position.of(OVERWORLD, 6.5, 64, 6.5);
    fake.tick();
    List<Displays.Outline> boxes = fake.outlined.get(admin.id());
    assertThat(boxes.getFirst().kind()).isIn("gallery", "cell");
  }
}
