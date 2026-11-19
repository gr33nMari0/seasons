// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapModel;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /season marker add} and {@code remove}: shared waypoints in markers.yml, told apart
 * ignoring case, audited, and saved whether or not a web map is installed.
 */
class MarkerCommandsTest {
  private static final Position TOWER = Position.of("minecraft:overworld", 100.5, 64, -199.5);

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef alex;

  private void start(MapPublisher maps) {
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            Webhooks.NONE,
            maps,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
    alex = fake.join("Alex");
    fake.player(alex).position = TOWER;
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private List<String> runAsAlex(String command) {
    TestSource source = new TestSource(Optional.of(alex.id()));
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private String markersFile() throws IOException {
    return Files.readString(dir.resolve("world/seasons/markers.yml"), UTF_8);
  }

  @Test
  void waypointsAreAddedAndRemovedByNameIgnoringCase() throws IOException {
    start(MapPublisher.NONE);

    assertThat(runAsAlex("/season marker add megaprojects Peter's Tower"))
        .as("waypoints need no season, and say when no web map will show them")
        .containsExactly(
            "Added waypoint Peter's Tower to megaprojects at 100, 64, -200 in minecraft:overworld.",
            "BlueMap isn't installed, so it shows once BlueMap is.");
    assertThat(markersFile()).contains("schema-version: 1", "megaprojects:", "Peter's Tower:");
    assertThat(run("/season status"))
        .contains("BlueMap isn't installed, so no map markers are published.");

    assertThat(runAsAlex("/season marker add megaprojects PETER'S TOWER"))
        .containsExactly("megaprojects already has a waypoint called Peter's Tower.");
    assertThat(run("/season marker remove megaprojects peter's tower"))
        .containsExactly("Removed waypoint Peter's Tower from megaprojects.");
    assertThat(markersFile()).doesNotContain("megaprojects");
  }

  @Test
  void eachRefusal() {
    start(MapPublisher.NONE);

    assertThat(run("/season marker add hubs Hub"))
        .containsExactly("Stand where the waypoint goes and run this in game.");
    assertThat(runAsAlex("/season marker add Mega_Projects Hub").getFirst())
        .startsWith("Waypoint sets are lower-case letters");
    assertThat(runAsAlex("/season marker add hubs " + "x".repeat(65)))
        .containsExactly("Waypoint names are 1 to 64 characters.");
    assertThat(run("/season marker remove hubs Hub"))
        .containsExactly("hubs has no waypoint called Hub.");
  }

  @Test
  void aNameIsKeptAsWrittenAndEveryEditIsAudited() throws IOException {
    start(publishedTo(3));

    assertThat(runAsAlex("/season marker add hubs <red>Spawn hub</red>"))
        .as("with a web map running, nothing more is said")
        .containsExactly(
            "Added waypoint <red>Spawn hub</red> to hubs at 100, 64, -200 in minecraft:overworld.");
    run("/season marker remove hubs <RED>spawn HUB</RED>");

    assertThat(Files.readAllLines(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .filteredOn(line -> line.contains("\"action\":\"config\""))
        .extracting(line -> line.replaceFirst("^\\{\"time\":\"[^\"]*\",", ""))
        .containsExactly(
            "\"actor\":\"Alex\",\"action\":\"config\",\"target\":\"markers.yml"
                + " sets.hubs.<red>Spawn hub</red>\",\"result\":\"ok\",\"details\":\"none -> 100,"
                + " 64, -200 in minecraft:overworld\"}",
            "\"actor\":\"The console\",\"action\":\"config\",\"target\":\"markers.yml"
                + " sets.hubs.<red>Spawn hub</red>\",\"result\":\"ok\",\"details\":\"100, 64, -200"
                + " in minecraft:overworld -> none\"}");
  }

  @Test
  void anEditThatDoesntTakeIsPutBackAndNotAudited() throws IOException {
    start(MapPublisher.NONE);
    Path markers = dir.resolve("world/seasons/markers.yml");
    Path config = dir.resolve("plugins/Seasons/config.yml");
    String good = Files.readString(config, UTF_8);

    // Another file broken by hand and not reloaded: the load fails, and markers.yml, which wasn't
    // there, still isn't.
    Files.writeString(config, "schedule: [nonsense\n", UTF_8);
    assertThat(runAsAlex("/season marker add hubs Hub"))
        .singleElement()
        .asString()
        .startsWith("The waypoint wasn't saved: config.yml, line ");
    assertThat(markers).doesNotExist();
    Files.writeString(config, good, UTF_8);

    // A hand edit not reloaded yet holds "tower", so a command adding "Tower" makes a file the load
    // refuses; it is put back as the hand edit left it.
    runAsAlex("/season marker add megaprojects Hub");
    String handEdited =
        """
        schema-version: 1
        sets:
          megaprojects:
            Hub: { pos: [100, 64, -200] }
            tower: { pos: [1, 64, 1] }
        """;
    Files.writeString(markers, handEdited, UTF_8);
    assertThat(runAsAlex("/season marker add megaprojects Tower"))
        .singleElement()
        .asString()
        .startsWith("The waypoint wasn't saved: markers.yml, at sets.megaprojects.")
        .contains("already has a waypoint by that name, ignoring case");
    assertThat(markersFile()).isEqualTo(handEdited);

    assertThat(run("/season reload")).first().asString().startsWith("Reloaded ");
    assertThat(run("/season marker remove megaprojects TOWER"))
        .containsExactly("Removed waypoint tower from megaprojects.");
    assertThat(Files.readAllLines(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .filteredOn(line -> line.contains("\"action\":\"config\""))
        .extracting(line -> line.replaceFirst("^.*\"target\":\"([^\"]*)\".*$", "$1"))
        .as("only the edits that took")
        .containsExactly(
            "markers.yml sets.megaprojects.Hub", "markers.yml sets.megaprojects.tower");
  }

  @Test
  void aBrokenMarkersFileIsLeftOutAtStartupAndNothingElse() throws IOException {
    Path seasonFile = dir.resolve("world/seasons/seasons/2.yml");
    Files.createDirectories(seasonFile.getParent());
    Files.writeString(
        seasonFile,
        """
        schema-version: 1
        season: 2
        zones:
          base: { dimension: minecraft:overworld, shape: { cylinder: { center: [500, 500], radius: 30 } } }
        """,
        UTF_8);
    Path markers = dir.resolve("world/seasons/markers.yml");
    Files.writeString(
        markers,
        "schema-version: 1\nsets:\n  hubs: { Hub: { pos: [1, 2, 3] }, HUB: { pos: [4, 5, 6] } }\n",
        UTF_8);
    start(MapPublisher.NONE);
    run("/season init 2");

    assertThat(run("/season zone list"))
        .as("the season files loaded")
        .first()
        .asString()
        .isEqualTo("Season 2's zones:");
    assertThat(run("/season status"))
        .anyMatch(line ->
            line.startsWith(
                    "markers.yml couldn't be loaded at startup, so it is left out and no shared"
                        + " waypoints are shown")
                && line.contains("markers.yml, at sets.hubs.HUB:"))
        .noneMatch(line -> line.startsWith("Config problem at startup"));
    String leftOut =
        "markers.yml failed to load at startup, so the waypoints can't be changed by command."
            + " Fix the file and run /season reload.";
    assertThat(runAsAlex("/season marker add hubs Spawn")).containsExactly(leftOut);
    assertThat(run("/season marker remove hubs Hub")).containsExactly(leftOut);

    // A reload swaps every file in or none, so it fails until markers.yml is fixed.
    assertThat(run("/season reload")).first().asString().startsWith("Reload failed");
    Files.writeString(
        markers, "schema-version: 1\nsets:\n  hubs: { Hub: { pos: [1, 2, 3] } }\n", UTF_8);
    assertThat(run("/season reload")).first().asString().startsWith("Reloaded ");
    assertThat(run("/season status")).noneMatch(line -> line.startsWith("markers.yml"));
    assertThat(run("/season marker remove hubs Hub"))
        .containsExactly("Removed waypoint Hub from hubs.");
  }

  /** A publisher that says it has published to so many maps, and publishes nothing. */
  private static MapPublisher publishedTo(int maps) {
    return new MapPublisher() {
      @Override
      public void publish(MapModel model) {}

      @Override
      public Status status() {
        return new Status.Published(0, maps);
      }

      @Override
      public void close() {}
    };
  }
}
