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

/**
 * Players away for the whole of Seasons' part of the season still get a cell and a figure (1.4.1,
 * questions 339), and a cell's displays follow its anchors at once (340).
 */
class LateReturnersTest {
  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;

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
        cells:
          c1:
            bounds: { min: [4, 64, 4], max: [8, 70, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
          c2:
            bounds: { min: [10, 64, 4], max: [14, 70, 8] }
            anchors:
              - { type: figure, pos: [12, 64, 7] }
        """);
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private void start() {
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
  }

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  /** A player who played here before Seasons, then left. */
  private PlayerRef fromBefore(String name) {
    PlayerRef player = fake.join(name);
    fake.player(player).inventory = FakeItems.gear();
    fake.player(player).saves = 1;
    fake.leave(player);
    return player;
  }

  private PlayerRef back(String name) {
    PlayerRef player = fake.join(name);
    fake.sessions.joined(player, true);
    return player;
  }

  private void setSpawn(int x, int z) {
    PlayerRef admin = fake.join("Admin");
    fake.player(admin).position = Position.of("minecraft:overworld", x + 0.3, -60, z + 0.7);
    TestSource source = new TestSource(Optional.of(admin.id()));
    TestCommands.run(fake.commands.getFirst(), source, "/season spawn set");
    fake.leave(admin);
  }

  private List<FakePlatform.Drawn> figures() {
    return fake.displays.values().stream().filter(d -> d.kind().equals("figure")).toList();
  }

  /** A cell's Season 2 owner, as /hof gallery info names them. */
  private Optional<String> owner(String cell) {
    return hof("/hof gallery info season-2").stream()
        .filter(line -> line.startsWith("Cell " + cell + ": "))
        .map(line -> line.substring(("Cell " + cell + ": ").length()).replaceAll("[ ,;.].*", ""))
        .filter(name -> !name.equals("free"))
        .findFirst();
  }

  @Test
  void aPlayerFromBeforeSeasonsBackInTheIntermissionGetsACellAndAFigure() {
    fromBefore("OldTimer");
    start();
    run("/season init 2");
    run("/season decorate open");
    run("/season end --now");

    back("OldTimer");

    assertThat(List.of(owner("c1"), owner("c2"))).contains(Optional.of("OldTimer"));
    assertThat(figures()).singleElement().satisfies(f -> assertThat(f.tag()).contains("season-2"));
  }

  @Test
  void aPlayerFromBeforeSeasonsBackAfterLaunchGetsACellAndAFigure() {
    fromBefore("OldTimer");
    start();
    run("/season init 2");
    run("/season end --now");
    setSpawn(300, 300);
    run("/season launch");

    back("OldTimer");

    assertThat(List.of(owner("c1"), owner("c2"))).contains(Optional.of("OldTimer"));
    assertThat(figures()).hasSize(1);
  }

  @Test
  void movingAFigureAnchorRedrawsTheFigureThere() {
    fromBefore("OldTimer");
    start();
    run("/season init 2");
    run("/season end --now");
    back("OldTimer");
    String cell = owner("c1").filter("OldTimer"::equals).isPresent() ? "c1" : "c2";
    int x = cell.equals("c1") ? 5 : 11;

    hof("/hof cell anchor season-2:" + cell + " figure " + x + " 64 5 90");

    assertThat(figures())
        .singleElement()
        .satisfies(f -> {
          assertThat(f.at().blockX()).isEqualTo(x);
          assertThat(f.at().blockZ()).isEqualTo(5);
        });
  }
}
