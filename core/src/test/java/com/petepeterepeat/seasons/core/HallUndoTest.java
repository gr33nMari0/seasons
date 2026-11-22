// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
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

/** Swapping, moving, renaming cells, rows, and /hof undo (questions 274, 275 and 277; A69, A70). */
class HallUndoTest {
  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef alex;
  private PlayerRef sam;

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
        bounds: { min: [0, 60, 0], max: [60, 80, 30] }
        cells:
          c1:
            bounds: { min: [1, 64, 1], max: [7, 69, 7] }
            anchors:
              - { type: figure, pos: [4, 64, 2], yaw: 0 }
          c2:
            bounds: { min: [9, 64, 1], max: [15, 69, 7] }
            anchors:
              - { type: figure, pos: [12, 64, 2], yaw: 0 }
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
    alex = fake.join("Alex");
    sam = fake.join("Sam");
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private List<String> hof(PlayerRef player, String command) {
    TestSource source = new TestSource(Optional.of(player.id()));
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  private String file() throws IOException {
    return Files.readString(dir.resolve("world/seasons/galleries/season-2.yml"));
  }

  @Test
  void swapExchangesPlacesNotIdsOrOwners() throws IOException {
    hof(alex, "/hof cell assign season-2:c1 Sam");
    assertThat(hof(alex, "/hof cell swap season-2:c1 season-2:c2"))
        .startsWith("Swapped the places of season-2:c1 and season-2:c2.");
    assertThat(hof(alex, "/hof cell info season-2:c1").getFirst()).contains("Sam");
    assertThat(file())
        .containsPattern("c1:\\n        bounds:\\n            min:\\n            - 9");
  }

  @Test
  void moveRenameAndUndo() throws IOException {
    assertThat(hof(alex, "/hof cell move season-2:c2 1 0 0"))
        .startsWith("Moved season-2:c2 by 1, 0, 0.");
    assertThat(hof(alex, "/hof cell rename season-2:c2 c5"))
        .startsWith("Renamed season-2:c2 to season-2:c5.");
    assertThat(hof(alex, "/hof undo"))
        .containsExactly("Put galleries/season-2.yml back as it was before your last edit.");
    assertThat(file()).contains("c2:").doesNotContain("c5:");
    assertThat(hof(alex, "/hof undo")).containsExactly("You have no Hall edit to undo.");
  }

  @Test
  void undoIsRefusedOnceSomeoneElseChangedTheFile() {
    hof(alex, "/hof cell rename season-2:c2 c5");
    hof(sam, "/hof cell new season-2 c9");
    assertThat(hof(alex, "/hof undo"))
        .containsExactly(
            "galleries/season-2.yml changed after your last edit, so it can't be undone.");
  }

  @Test
  void aRowIsPreviewedThenWrittenAndUndoneInOne() throws IOException {
    hof(alex, "/hof sel pos1 1 64 1");
    hof(alex, "/hof sel pos2 7 69 7");
    assertThat(hof(alex, "/hof cell row season-2 3 --along +x --across +z 3 --like c1"))
        .containsExactly(
            "A row of 4 new cells in season-2, c3 to c6, is shown as outlines for 60 seconds:"
                + " /hof cell row confirm writes it.");
    // c1 and c2 are already where the first two near cells go, so they stay as they are.
    assertThat(hof(alex, "/hof cell row confirm").getFirst()).startsWith("Added 4 cells");
    assertThat(file()).contains("c3:", "c4:", "c5:", "c6:");
    assertThat(hof(alex, "/hof undo").getFirst()).startsWith("Put galleries/season-2.yml back");
    assertThat(file()).doesNotContain("c3:");
  }
}
