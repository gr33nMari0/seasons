// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Figure;
import com.petepeterepeat.seasons.core.port.PlayerRef;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The armour-stand figure (questions 170) and a figure's pose (171). */
class FiguresTest {
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

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private void start(Capabilities capabilities, String gallery, String cell) throws IOException {
    writeGallery(gallery, cell);
    seasons =
        new Seasons(
            fake.platform(capabilities, dir),
            "1.2.3",
            fake.clock,
            webhooks,
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
    run("/season init 2");
    PlayerRef alex = fake.join("Alex");
    fake.sessions.joined(alex, false);
    fake.player(alex).inventory = FakeItems.gear();
    TestCommands.run(fake.commands.get(1), TestSource.console(), "/hof cell assign c1 Alex");
    run("/season end --now");
  }

  private void writeGallery(String gallery, String cell) throws IOException {
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
        %s
        cells:
          c1:
            bounds: { min: [4, 60, 4], max: [8, 70, 8] }
            %s
            anchors:
              - { type: figure, pos: [6, 64, 7] }
              - { type: trophy, pos: [5, 64, 7] }
        """
            .formatted(gallery, cell));
  }

  private static Capabilities all() {
    return new Capabilities(EnumSet.allOf(Capability.class));
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  private List<FakePlatform.Drawn> drawn(String kind) {
    return fake.displays.values().stream().filter(d -> d.kind().equals(kind)).toList();
  }

  private Figure figure() {
    return drawn("figure").getFirst().figure().orElseThrow();
  }

  @Test
  void anArmourStandGalleryDrawsTheFallbackWithTheHelmetOnATrophy() throws IOException {
    start(all(), "renderer: armour-stand", "");

    assertThat(figure().kind()).isEqualTo(Figure.Kind.ARMOUR_STAND);
    assertThat(figure().equipment().getFirst()).as("the head is the adapter's").isEmpty();
    assertThat(drawn("item"))
        .singleElement()
        .satisfies(item -> assertThat(item.tag()).endsWith("/trophy"));
  }

  @Test
  void aMannequinKeepsItsHelmetAndTakesItsCellsPose() throws IOException {
    start(all(), "", "pose: crouching");

    assertThat(figure().kind()).isEqualTo(Figure.Kind.MANNEQUIN);
    assertThat(figure().pose()).contains("crouching");
    assertThat(figure().equipment().getFirst()).isPresent();
    assertThat(drawn("item")).isEmpty();
  }

  @Test
  void withoutMannequinsFiguresFallBack() throws IOException {
    start(new Capabilities(EnumSet.noneOf(Capability.class)), "", "pose: crouching");

    assertThat(figure().kind()).isEqualTo(Figure.Kind.ARMOUR_STAND);
    assertThat(figure().pose()).as("an armour stand stands").isEmpty();
  }

  @Test
  void aFigureOfTheOtherRendererIsDrawnAgain() throws IOException {
    start(all(), "", "");
    Path file = dir.resolve("world/seasons/galleries/season-2.yml");
    Files.writeString(
        file, Files.readString(file).replace("cells:", "renderer: armour-stand\ncells:"));
    run("/season reload");

    TestCommands.run(fake.commands.get(1), TestSource.console(), "/hof reconcile season-2");
    fake.tick();
    fake.tick();

    assertThat(drawn("figure")).hasSize(1);
    assertThat(figure().kind()).isEqualTo(Figure.Kind.ARMOUR_STAND);
    assertThat(Files.readString(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .contains("drew the figure again");
  }
}
