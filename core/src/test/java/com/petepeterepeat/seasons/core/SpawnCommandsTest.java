// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static java.nio.charset.StandardCharsets.UTF_8;
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

/**
 * {@code /season spawn set} and the spawn candidates (A41, M3 part 3): both go in the next season's
 * file, never inside one of its zones, and every edit is audited.
 */
class SpawnCommandsTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Position NORTH = Position.of(OVERWORLD, 4000.5, 64, -1999.5);
  private static final Position EAST = Position.of(OVERWORLD, 6000.5, 64, 1000.5);

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

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  /** Runs a command as Alex, standing at a position. */
  private List<String> runAt(Position at, String command) {
    fake.player(alex).position = at;
    TestSource source = new TestSource(Optional.of(alex.id()));
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private String seasonFile(int season) throws IOException {
    return Files.readString(dir.resolve("world/seasons/seasons/" + season + ".yml"), UTF_8);
  }

  @Test
  void candidatesGoInTheNextSeasonsFileAndAreListedWithTheirDistance() throws IOException {
    assertThat(run("/season spawn candidate list").getFirst()).startsWith("No season recorded");
    run("/season init 2");
    assertThat(run("/season spawn candidate list"))
        .containsExactly(
            "Season 3 has no spawn candidates yet. Stand at one and run /season spawn candidate"
                + " add with its name.");

    assertThat(runAt(NORTH, "/season spawn candidate add north-mesa"))
        .containsExactly(
            "Added spawn candidate north-mesa for Season 3 at 4000, 64, -2000 in"
                + " minecraft:overworld.");
    runAt(EAST, "/season spawn candidate add east-jungle");
    assertThat(seasonFile(3)).contains("spawnCandidates:", "north-mesa:", "east-jungle:");
    assertThat(run("/season spawn candidate list"))
        .containsExactly(
            "Season 3's spawn candidates:",
            "- east-jungle: 6000, 64, 1000 in minecraft:overworld, 6083 blocks from 0, 0",
            "- north-mesa: 4000, 64, -2000 in minecraft:overworld, 4472 blocks from 0, 0");

    assertThat(run("/season spawn set east-jungle"))
        .as("from the console too")
        .containsExactly(
            "Season 3's spawn is now candidate east-jungle: 6000, 64, 1000 in minecraft:overworld.");
    assertThat(run("/season spawn candidate list"))
        .contains(
            "- east-jungle: 6000, 64, 1000 in minecraft:overworld, 6083 blocks from 0, 0 (the"
                + " spawn)");
    assertThat(run("/season spawn candidate remove north-mesa"))
        .containsExactly("Removed spawn candidate north-mesa from Season 3.");
    String file = seasonFile(3);
    assertThat(file).doesNotContain("north-mesa").contains("east-jungle:", "spawn:", "- 6000");
  }

  @Test
  void aSpawnBeyondTheStrongholdsIsSetWithAWarningThatTheDryRunRepeats() {
    run("/season init 2");
    Position far = Position.of(OVERWORLD, 25000.5, 64, 0.5);
    assertThat(runAt(far, "/season spawn candidate add far-east"))
        .containsExactly(
            "Added spawn candidate far-east for Season 3 at 25000, 64, 0 in minecraft:overworld.",
            "That's 25000 blocks from 0, 0, beyond 24,000: strongholds may be out of reach.");
    assertThat(run("/season spawn candidate list"))
        .contains(
            "- far-east: 25000, 64, 0 in minecraft:overworld, 25000 blocks from 0, 0 (beyond"
                + " 24,000)");
    assertThat(runAt(far, "/season spawn set"))
        .containsExactly(
            "Season 3's spawn is now 25000, 64, 0 in minecraft:overworld.",
            "That's 25000 blocks from 0, 0, beyond 24,000: strongholds may be out of reach.");

    assertThat(run("/season end --dry-run"))
        .as("Alex played Season 2 and there are no cells: a shortfall")
        .contains(
            "Hall of Fame: only 0 free cells in Season 2's galleries for 1 players without one,"
                + " so 1 would be archived without displays.",
            "Season 3's spawn at 25000, 64, 0 is 25000 blocks from 0, 0, beyond 24,000:"
                + " strongholds may be out of reach.");
    assertThat(run("/season reload"))
        .contains(
            "seasons/3.yml: Season 3's spawn at 25000, 64, 0 is 25000 blocks from 0, 0, beyond"
                + " 24,000; strongholds may be out of reach");
  }

  @Test
  void eachRefusal() {
    assertThat(run("/season spawn candidate add north-mesa").getFirst())
        .startsWith("No season recorded");
    run("/season init 2");
    assertThat(run("/season spawn candidate add north-mesa"))
        .containsExactly("Stand where the candidate is and run this in game.");
    assertThat(run("/season spawn set"))
        .containsExactly(
            "Stand where the spawn should be and run this in game, or name a spawn candidate.");
    runAt(NORTH, "/season spawn candidate add north-mesa");
    assertThat(runAt(EAST, "/season spawn candidate add north-mesa"))
        .containsExactly("Season 3 already has a spawn candidate called north-mesa.");
    assertThat(runAt(EAST, "/season spawn candidate add North_Mesa").getFirst())
        .startsWith("Candidate names are lower-case letters");
    assertThat(run("/season spawn candidate remove nowhere"))
        .containsExactly("Season 3 has no spawn candidate called nowhere.");
    assertThat(run("/season spawn set nowhere"))
        .containsExactly("Season 3 has no spawn candidate called nowhere.");
  }

  @Test
  void neitherTheSpawnNorACandidateGoesInsideAZone() throws IOException {
    run("/season init 2");
    Position pen = Position.of(OVERWORLD, 3000.5, 64, 0.5);
    runAt(pen, "/season zone add pen cylinder 20");
    Position inPen = Position.of(OVERWORLD, 3005.5, 64, 0.5);
    assertThat(runAt(inPen, "/season spawn candidate add stray"))
        .containsExactly(
            "Season 3's spawn candidates can't be inside zone pen; every candidate must stay"
                + " outside every zone.");
    assertThat(runAt(inPen, "/season spawn set"))
        .containsExactly(
            "Season 3's spawn can't be inside zone pen; the spawn must stay outside every zone.");

    Position mesa = Position.of(OVERWORLD, 3200.5, 64, 0.5);
    runAt(mesa, "/season spawn candidate add mesa");
    assertThat(runAt(mesa, "/season zone add ring cylinder 20"))
        .containsExactly(
            "That zone would hold Season 3's spawn candidate mesa at 3200, 64, 0; every candidate"
                + " must stay outside every zone.");

    // A hand edit can put one inside, and setting the spawn to it is refused.
    Path file = dir.resolve("world/seasons/seasons/3.yml");
    Files.writeString(
        file,
        seasonFile(3)
            .replaceFirst(
                "spawnCandidates:\n( +)", "spawnCandidates:\n$1stray: { pos: [3005, 64, 0] }\n$1"));
    assertThat(run("/season reload"))
        .contains(
            "seasons/3.yml: Season 3's spawn candidate stray at 3005, 64, 0 is inside zone pen;"
                + " every candidate must stay outside every zone");
    assertThat(run("/season spawn set stray"))
        .containsExactly(
            "Season 3's spawn can't be inside zone pen; the spawn must stay outside every zone.");
  }

  @Test
  void everyEditIsAudited() throws IOException {
    run("/season init 2");
    runAt(NORTH, "/season spawn candidate add north-mesa");
    runAt(EAST, "/season spawn set");
    run("/season spawn set north-mesa");
    run("/season spawn candidate remove north-mesa");

    assertThat(Files.readAllLines(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .filteredOn(line -> line.contains("\"action\":\"config\""))
        .extracting(line -> line.replaceFirst("^\\{\"time\":\"[^\"]*\",", ""))
        .containsExactly(
            "\"actor\":\"Alex\",\"action\":\"config\",\"target\":\"seasons/3.yml"
                + " spawnCandidates.north-mesa\",\"result\":\"ok\",\"details\":\"none -> 4000, 64,"
                + " -2000 in minecraft:overworld\"}",
            "\"actor\":\"Alex\",\"action\":\"config\",\"target\":\"seasons/3.yml"
                + " spawn\",\"result\":\"ok\",\"details\":\"none -> 6000, 64, 1000 in"
                + " minecraft:overworld\"}",
            "\"actor\":\"The console\",\"action\":\"config\",\"target\":\"seasons/3.yml"
                + " spawn\",\"result\":\"ok\",\"details\":\"6000, 64, 1000 in minecraft:overworld"
                + " -> 4000, 64, -2000 in minecraft:overworld\"}",
            "\"actor\":\"The console\",\"action\":\"config\",\"target\":\"seasons/3.yml"
                + " spawnCandidates.north-mesa\",\"result\":\"ok\",\"details\":\"4000, 64, -2000"
                + " in minecraft:overworld -> none\"}");
  }
}
