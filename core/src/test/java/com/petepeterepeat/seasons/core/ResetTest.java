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
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /season reset} (questions 283, A76). */
class ResetTest {
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
    console("/season init 2");
    alex = fake.join("Alex");
    fake.sessions.joined(alex, false);
    TestCommands.run(
        fake.commands.get(1), TestSource.console(), "/hof cell assign season-2:c1 Alex");
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private List<String> console(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  @Test
  void onlyOperatorsAndTheConsole() {
    TestSource player = new TestSource(Optional.of(alex.id()));
    TestCommands.run(fake.commands.getFirst(), player, "/season reset");
    assertThat(player.text()).containsExactly("Only a server operator can reset Seasons.");
  }

  @Test
  void eachStepMustFollowTheLastWithinAMinute() {
    assertThat(console("/season reset confirm"))
        .containsExactly("No reset is waiting; run /season reset first.");
    console("/season reset");
    fake.clock.advance(Duration.ofSeconds(61));
    assertThat(console("/season reset confirm DELETE"))
        .containsExactly("No reset is waiting; run /season reset first.");
  }

  @Test
  void refusedWhileASeasonIsEnding() {
    console("/season end");
    assertThat(console("/season reset").getFirst()).startsWith("Seasons can't be reset while");
  }

  @Test
  void keepHallKeepsTheLayoutWithoutOwnersAndTheBackupComesFirst() throws IOException {
    assertThat(console("/season reset --keep-hall").getLast())
        .startsWith("A backup is written first.");
    assertThat(console("/season reset confirm"))
        .containsExactly("From the console, run /season reset confirm DELETE to reset Seasons.");
    List<String> done = console("/season reset confirm DELETE");
    assertThat(done.getLast()).startsWith("Seasons was reset and has stopped.");
    try (Stream<Path> zips = Files.list(dir.resolve("plugins/Seasons/reset-backups"))) {
      assertThat(zips.toList()).singleElement().asString().endsWith(".zip");
    }
    assertThat(dir.resolve("world/seasons/data/season.json")).doesNotExist();
    String gallery = Files.readString(dir.resolve("world/seasons/galleries/season-2.yml"));
    assertThat(gallery).contains("c1:").doesNotContain("owners");
  }
}
