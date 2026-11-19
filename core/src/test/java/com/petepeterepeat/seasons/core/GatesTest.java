// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.hall.Anchor;
import com.petepeterepeat.seasons.core.hall.AnchorType;
import com.petepeterepeat.seasons.core.hall.Bounds;
import com.petepeterepeat.seasons.core.hall.Cell;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.GalleryRules;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.BlockInfo;
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

/** Access mode gate (questions 169): gates open and shut with decorating, journalled first. */
class GatesTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final List<Position> PLANE =
      List.of(
          Position.of(OVERWORLD, 5, 64, 5),
          Position.of(OVERWORLD, 6, 64, 5),
          Position.of(OVERWORLD, 5, 65, 5),
          Position.of(OVERWORLD, 6, 65, 5));
  private static final Position INSIDE = Position.of(OVERWORLD, 6.5, 64, 7.5);
  private static final Position ENTRY = new Position(OVERWORLD, 1.5, 64, 1.5, 0f, 0f);

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
        galleries.resolve("season-2.yml"),
        """
        schema-version: 1
        id: season-2
        season: 2
        bounds: { min: [0, 60, 0], max: [30, 80, 20] }
        entry: { pos: [1, 64, 1] }
        access: gate
        cells:
          c1:
            bounds: { min: [4, 60, 4], max: [8, 70, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
              - { type: gate, pos: [5, 64, 5] }
              - { type: gate, pos: [6, 65, 5] }
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
    alex = fake.join("Alex");
    fake.sessions.joined(alex, false);
    TestCommands.run(fake.commands.get(1), TestSource.console(), "/hof cell assign c1 Alex");
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  private boolean gateIs(String type) {
    return PLANE.stream().allMatch(at -> fake.blockAt(at).type().equals(type));
  }

  private String journal() throws IOException {
    return Files.readString(dir.resolve("world/seasons/data/journal.log"));
  }

  private String audit() throws IOException {
    return Files.readString(dir.resolve("world/seasons/audit/2026-11.jsonl"));
  }

  @Test
  void aGateIsOpenOnlyWhileItsOwnerMayDecorate() {
    fake.tick();
    assertThat(gateIs("minecraft:iron_bars")).as("locked: shut").isTrue();

    run("/season decorate open");
    fake.tick();
    assertThat(gateIs("minecraft:air")).as("decorating: open").isTrue();

    run("/season decorate close");
    fake.tick();
    assertThat(gateIs("minecraft:iron_bars")).isTrue();
  }

  @Test
  void everyChangeIsJournalledFirst() throws IOException {
    fake.tick();
    run("/season decorate open");
    fake.tick();
    fake.tick();

    assertThat(journal())
        .containsOnlyOnce("season-2:c1/shut")
        .containsOnlyOnce("season-2:c1/open")
        .contains("GATE_SET");
  }

  @Test
  void aChangedGateIsPutBackAndAudited() throws IOException {
    fake.tick();
    fake.setBlock(5, 64, 5, FakePlatform.STONE);

    fake.tick();

    assertThat(gateIs("minecraft:iron_bars")).isTrue();
    assertThat(audit()).contains("\"action\":\"reconcile\"").contains("gate shut");
  }

  @Test
  void aGateIsNeverSetOverABlockWithContents() throws IOException {
    fake.tick();
    run("/season decorate open");
    fake.tick();
    fake.setBlock(5, 64, 5, FakePlatform.CHEST);

    run("/season decorate close");
    fake.tick();
    fake.tick();

    assertThat(fake.blockAt(PLANE.getFirst()).type())
        .as("the chest stays")
        .isEqualTo("minecraft:chest");
    assertThat(PLANE.stream().skip(1).map(at -> fake.blockAt(at).type()))
        .as("nothing of the gate is set while it is there")
        .containsOnly("minecraft:air");
    assertThat(journal()).containsOnlyOnce("season-2:c1/shut");
    assertThat(audit()).containsOnlyOnce("\"action\":\"gate\"").contains("contents at 5, 64, 5");

    fake.setBlock(5, 64, 5, BlockInfo.AIR);
    fake.tick();
    assertThat(gateIs("minecraft:iron_bars")).as("set once the chest is gone").isTrue();
  }

  @Test
  void aGateThatDoesntStaySetIsLeftAloneUntilAReload() throws IOException {
    fake.tick();
    for (int i = 0; i < 10; i++) {
      fake.setBlock(5, 64, 5, FakePlatform.STONE);
      fake.tick();
    }

    assertThat(journal().split("season-2:c1/shut", -1))
        .as("three tries in all, since no pass found it set")
        .hasSize(4);
    assertThat(audit()).containsOnlyOnce("unsteady at");

    run("/season reload");
    fake.tick();
    assertThat(gateIs("minecraft:iron_bars")).as("tried again after a reload").isTrue();
  }

  @Test
  void aGatePlaneMayNotCoverAnchorsOrTheGallerysOwnPoints() {
    Bounds bounds = Bounds.between(OVERWORLD, 0, 60, 0, 10, 70, 10);
    Cell gated =
        Cell.create("c1")
            .withBounds(bounds)
            .withAnchor(Anchor.of(AnchorType.GATE, Position.of(OVERWORLD, 2, 64, 2)))
            .withAnchor(Anchor.of(AnchorType.GATE, Position.of(OVERWORLD, 4, 65, 2)))
            .withAnchor(Anchor.of(AnchorType.ARCHIVE, Position.of(OVERWORLD, 3, 64, 2)));
    Gallery gallery =
        Gallery.create("g", 2, OVERWORLD)
            .withBounds(Bounds.between(OVERWORLD, 0, 60, 0, 30, 70, 30))
            .withCell(gated)
            .withLaunch(Optional.of(Position.of(OVERWORLD, 4, 64, 2)));

    assertThat(GalleryRules.check(gallery))
        .extracting(GalleryRules.Problem::message)
        .containsExactly(
            "the gate's plane holds the archive anchor of c1 at 3, 64, 2, which the gate would be"
                + " set over",
            "the gate's plane holds the launch button at 4, 64, 2, which the gate would be set"
                + " over");
  }

  @Test
  void aPlayerInsideWhenTheGateShutsGoesToTheGalleryEntry() {
    run("/season decorate open");
    fake.tick();
    fake.player(alex).position = INSIDE;

    run("/season decorate close");
    fake.tick();

    assertThat(fake.player(alex).position).isEqualTo(ENTRY);
    assertThat(
            fake.sent.stream()
                .filter(s -> s.player().equals(alex.id()))
                .map(s -> Messages.plain(s.message())))
        .contains("The cell's gate shut, so you're at the gallery entry.");
  }

  @Test
  void gateBlocksAreHallBlocks() {
    run("/season decorate open");
    fake.tick();
    fake.player(alex).position = INSIDE;

    assertThat(
            fake.handler.decide(
                new Intent.PlaceBlock(alex.id(), PLANE.getFirst(), "minecraft:stone")))
        .as("not even the owner builds in the open gate")
        .isNotEqualTo(Decision.allow());
    assertThat(fake.handler.decide(new Intent.PlaceBlock(alex.id(), INSIDE, "minecraft:stone")))
        .isEqualTo(Decision.allow());
  }

  @Test
  void aGateIsAPlaneInsideItsCell() {
    Bounds bounds = Bounds.between(OVERWORLD, 0, 60, 0, 10, 70, 10);
    Cell oneCorner =
        Cell.create("one")
            .withBounds(bounds)
            .withAnchor(Anchor.of(AnchorType.GATE, Position.of(OVERWORLD, 2, 64, 2)));
    Cell thick =
        Cell.create("thick")
            .withBounds(bounds)
            .withAnchor(Anchor.of(AnchorType.GATE, Position.of(OVERWORLD, 2, 64, 2)))
            .withAnchor(Anchor.of(AnchorType.GATE, Position.of(OVERWORLD, 4, 66, 4)));
    Gallery gallery =
        Gallery.create("g", 2, OVERWORLD)
            .withBounds(Bounds.between(OVERWORLD, 0, 60, 0, 30, 70, 30))
            .withCell(oneCorner);
    Gallery other =
        Gallery.create("h", 2, OVERWORLD)
            .withBounds(Bounds.between(OVERWORLD, 0, 60, 0, 10, 70, 10))
            .withCell(thick);

    assertThat(GalleryRules.check(gallery))
        .extracting(GalleryRules.Problem::message)
        .containsExactly("a gate needs two gate anchors, at opposite corners of its plane");
    assertThat(GalleryRules.check(other))
        .extracting(GalleryRules.Problem::message)
        .containsExactly("the gate must be a plane one block thick");
  }
}
