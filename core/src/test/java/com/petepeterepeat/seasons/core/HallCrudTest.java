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

/** Listing, showing, moving and removing the Hall's parts and seasons (questions 269, A65). */
class HallCrudTest {
  private static final String OVERWORLD = "minecraft:overworld";

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef alex;

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
        bounds: { min: [0, 60, 0], max: [20, 80, 20] }
        cells:
          c1:
            bounds: { min: [4, 64, 4], max: [8, 69, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
          c2:
            bounds: { min: [10, 64, 4], max: [14, 69, 8] }
            anchors:
              - { type: figure, pos: [12, 64, 7] }
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
    season("/season init 2");
    alex = fake.join("Alex");
    fake.sessions.joined(alex, false);
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private List<String> season(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  @Test
  void anOwnedCellOrGalleryIsNeverRemoved() {
    hof("/hof cell assign season-2:c1 Alex");
    assertThat(hof("/hof cell remove season-2:c1"))
        .containsExactly("Cell season-2:c1 is owned by Alex for Season 2; unassign it first.");
    assertThat(hof("/hof gallery remove season-2"))
        .containsExactly(
            "Gallery season-2 can't be removed while cell c1 is owned; unassign it first.");
    assertThat(hof("/hof cell remove season-2:c2")).containsExactly("Removed cell season-2:c2.");
    assertThat(hof("/hof cell list season-2")).containsExactly("Cell c1: Alex");
    hof("/hof cell unassign season-2:c1");
    assertThat(hof("/hof gallery remove season-2")).containsExactly("Removed gallery season-2.");
    assertThat(dir.resolve("world/seasons/galleries/season-2.yml")).doesNotExist();
    assertThat(dir.resolve("world/seasons/backups/galleries")).isNotEmptyDirectory();
  }

  @Test
  void cellsAreListedFreeOrOwnedAndPlayersAndSeasonsListed() {
    hof("/hof cell assign season-2:c2 Alex");
    assertThat(hof("/hof cell list season-2 free")).containsExactly("Cell c1: free");
    assertThat(hof("/hof cell list season-2 owned")).containsExactly("Cell c2: Alex");
    assertThat(season("/season players"))
        .containsExactly("Alex: played Season 2; not archived yet; cell season-2:c2");
    assertThat(season("/season players 2 archived")).containsExactly("No such players.");
    assertThat(season("/season list"))
        .containsExactly(
            "Season 2 (on record, Active, Open stage): no spawn; galleries season-2; 0 archived.");
  }

  @Test
  void whereThingsAre() {
    hof("/hof cell assign season-2:c1 Alex");
    assertThat(hof("/hof locate Alex"))
        .containsExactly("Alex's cell season-2:c1: 4, 64, 4 to 8, 69, 8.");
    assertThat(hof("/hof locate season-2"))
        .containsExactly("Gallery season-2: 0, 60, 0 to 20, 80, 20.");
    TestSource source = new TestSource(Optional.of(alex.id()));
    fake.player(alex).position = Position.of(OVERWORLD, 5.5, 64, 5.5);
    TestCommands.run(fake.commands.get(1), source, "/hof locate");
    assertThat(source.text()).containsExactly("You're in season-2:c1.");
  }

  @Test
  void theScheduleIsShownAndCleared() {
    assertThat(season("/season schedule show"))
        .containsExactly("No planned end set. /season schedule end sets one.");
    season("/season schedule end 2099-01-01T00:00Z");
    assertThat(season("/season schedule show").getFirst()).startsWith("Planned end: ");
    assertThat(season("/season schedule clear"))
        .containsExactly("Season 2 has no planned end now.");
    assertThat(season("/season schedule show"))
        .containsExactly("No planned end set. /season schedule end sets one.");
  }
}
