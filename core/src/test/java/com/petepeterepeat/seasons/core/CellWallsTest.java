// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
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
 * Cells whose bounds take in their walls (1.4.1 plan, H1): the warning, the check, the owner kept
 * off the walls, and the shrink (questions 341 to 343).
 */
class CellWallsTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String FACES = "floor, roof, north, south, west, east";

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef alex;

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
        galleries.resolve("walled.yml"),
        """
        schema-version: 1
        id: walled
        season: 2
        bounds: { min: [0, 64, 0], max: [40, 80, 20] }
        entry: { pos: [1, 64, 1] }
        cells:
          c1:
            anchors: []
          c2:
            anchors: []
        """);
    room(10);
    room(20);
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
    alex = fake.join("Alex");
    fake.sessions.joined(alex, false);
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  /** A hollow stone room 7 × 8 × 7 from x, z 2 to 8, y 64 to 71, with a doorway north. */
  private void room(int x) {
    for (int dx = 0; dx <= 6; dx++) {
      for (int y = 64; y <= 71; y++) {
        for (int z = 2; z <= 8; z++) {
          boolean shell = dx == 0 || dx == 6 || y == 64 || y == 71 || z == 2 || z == 8;
          boolean door = z == 2 && dx == 3 && (y == 65 || y == 66);
          fake.setBlock(
              x + dx,
              y,
              z,
              shell && !door
                  ? FakePlatform.STONE
                  : com.petepeterepeat.seasons.core.port.BlockInfo.AIR);
        }
      }
    }
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  private void walled(String cell, int x) {
    hof("/hof cell bounds walled:" + cell + " " + x + " 64 2 " + (x + 6) + " 71 8");
    hof("/hof cell anchor walled:" + cell + " figure " + (x + 3) + " 65 6 180");
  }

  private static String text(Decision decision) {
    return decision instanceof Decision.Deny deny
        ? deny.message().map(Messages::plain).orElse("(quietly)")
        : "allowed";
  }

  @Test
  void boundsTakingInTheWallsAreWarnedAboutAndChecked() {
    assertThat(hof("/hof cell bounds walled:c1 10 64 2 16 71 8"))
        .containsExactly(
            "Cell walled:c1 now spans 10, 64, 2 to 16, 71, 8.",
            "Cell walled:c1's bounds look like they include its walls ("
                + FACES
                + "). A cell's bounds are the space inside: run /hof cell shrink walled:c1.");
    // The space inside: no warning.
    assertThat(hof("/hof cell bounds walled:c2 21 65 3 25 70 7"))
        .containsExactly("Cell walled:c2 now spans 21, 65, 3 to 25, 70, 7.");

    assertThat(hof("/hof hall check"))
        .contains(
            "Cell walled:c1's bounds look like they include its walls ("
                + FACES
                + "): /hof cell shrink walled:c1.")
        .noneMatch(line -> line.startsWith("Cell walled:c2's bounds look"));
  }

  @Test
  void anOwnerDecoratingCantBreakTheWallsButBreaksWhatTheyPlaced() {
    walled("c1", 10);
    hof("/hof cell assign walled:c1 Alex");
    run("/season decorate open");

    for (Position wall :
        List.of(
            Position.of(OVERWORLD, 16, 67, 5),
            Position.of(OVERWORLD, 13, 64, 5),
            Position.of(OVERWORLD, 13, 71, 5))) {
      assertThat(text(fake.handler.decide(new Intent.BreakBlock(alex.id(), wall))))
          .isEqualTo("That's part of your cell's walls, floor or roof: only builders change it.");
    }
    // What they place inside, even against a wall, is theirs to break.
    fake.setBlock(15, 65, 5, FakePlatform.STONE);
    assertThat(
            fake.handler.decide(
                new Intent.BreakBlock(alex.id(), Position.of(OVERWORLD, 15, 65, 5))))
        .isEqualTo(Decision.allow());

    // Turned off, the walls are theirs too, as before 1.4.1.
    run("/season config set cells.protectShell false");
    assertThat(
            fake.handler.decide(
                new Intent.BreakBlock(alex.id(), Position.of(OVERWORLD, 16, 67, 5))))
        .isEqualTo(Decision.allow());
  }

  @Test
  void aFaceTheOwnerFillsDuringTheWindowStaysTheirs() {
    hof("/hof cell bounds walled:c2 21 65 3 25 70 7");
    hof("/hof cell anchor walled:c2 figure 23 65 6 180");
    hof("/hof cell assign walled:c2 Alex");
    run("/season decorate open");
    // Seen once, with the inside empty: no walls.
    assertThat(
            fake.handler.decide(
                new Intent.PlaceBlock(
                    alex.id(), Position.of(OVERWORLD, 25, 65, 5), "minecraft:stone")))
        .isEqualTo(Decision.allow());
    for (int y = 65; y <= 70; y++) {
      for (int z = 3; z <= 7; z++) {
        fake.setBlock(25, y, z, FakePlatform.STONE);
      }
    }
    assertThat(
            fake.handler.decide(
                new Intent.BreakBlock(alex.id(), Position.of(OVERWORLD, 25, 67, 5))))
        .isEqualTo(Decision.allow());
  }

  @Test
  void aCellShrinksAfterAPreviewAndUndoPutsItBack() {
    walled("c1", 10);

    assertThat(hof("/hof cell shrink walled:c1"))
        .containsExactly(
            "Shrinking walled:c1 to 11, 65, 3 to 15, 70, 7 ("
                + FACES
                + "): run /hof cell shrink confirm within 60 seconds, or /hof cell shrink cancel.");
    assertThat(hof("/hof cell shrink confirm"))
        .startsWith("Cell walled:c1 now spans 11, 65, 3 to 15, 70, 7.");
    assertThat(hof("/hof cell shrink walled:c1"))
        .containsExactly(
            "Cell walled:c1's bounds don't look like they include its walls: add --all to shrink"
                + " every face anyway.");
    assertThat(hof("/hof cell shrink confirm"))
        .containsExactly(
            "There's no shrink to confirm: run /hof cell shrink or /hof gallery shrink first.");
    // Every face, by two: the figure would be left below the floor.
    assertThat(hof("/hof cell shrink walled:c1 2 --all"))
        .containsExactly(
            "Can't shrink walled:c1: its figure anchor at 13, 65, 6 would be outside it. Move it"
                + " in first.");
    hof("/hof cell shrink cancel");
  }

  @Test
  void aShrinkThatWouldLeaveAnAnchorOutsideIsRefused() {
    walled("c1", 10);
    hof("/hof cell anchor walled:c1 archive 16 66 5");

    assertThat(hof("/hof cell shrink walled:c1"))
        .containsExactly(
            "Can't shrink walled:c1: its archive anchor at 16, 66, 5 would be outside it. Move it"
                + " in first.");
  }

  @Test
  void aGalleryShrinksEveryWalledCellAtOnce() {
    walled("c1", 10);
    walled("c2", 20);

    assertThat(hof("/hof gallery shrink walled"))
        .containsExactly(
            "Shrinking 2 cells of walled (c1, c2): run /hof gallery shrink confirm within 60"
                + " seconds, or /hof gallery shrink cancel.");
    assertThat(hof("/hof gallery shrink confirm")).startsWith("Shrank 2 cells of walled.");
    assertThat(hof("/hof hall check")).noneMatch(line -> line.contains("look like they include"));
    assertThat(hof("/hof undo")).singleElement().asString().startsWith("Put ");
    assertThat(hof("/hof hall check"))
        .anyMatch(line -> line.startsWith("Cell walled:c2's bounds look"));
  }
}
