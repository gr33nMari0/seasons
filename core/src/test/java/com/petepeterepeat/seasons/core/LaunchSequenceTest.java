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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The deaths display (Stats, season cards and awards in the spec), made at each launch. */
class LaunchSequenceTest {
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
            Optional.of(TestHooks.of(() -> {})));
    seasons.start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private List<String> run(String command, Optional<PlayerRef> as) {
    TestSource source = new TestSource(as.map(PlayerRef::id));
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private void launch(PlayerRef admin) {
    run("/season end --now", Optional.empty());
    run("/season spawn set", Optional.of(admin));
    run("/season launch", Optional.empty());
  }

  private void display(String slot) throws IOException {
    Path config = dir.resolve("plugins/Seasons/config.yml");
    Files.createDirectories(config.getParent());
    Files.writeString(config, "schema-version: 1\ndeaths:\n  display: " + slot + "\n");
    assertThat(run("/season reload", Optional.empty())).first().asString().startsWith("Reloaded");
  }

  @Test
  void eachLaunchMakesItsOwnDeathsObjective() throws IOException {
    start();
    PlayerRef admin = fake.join("Admin");
    fake.player(admin).position = Position.of("minecraft:overworld", 500, -60, 300);
    run("/season init 2", Optional.empty());
    // Season 2 was never launched, so a reload makes no objective for it.
    display("sidebar");
    assertThat(fake.objectives).isEmpty();

    display("list");
    launch(admin);
    assertThat(fake.objectives).containsExactly("seasons_deaths_s3");
    assertThat(fake.shownObjectives).containsExactly(Map.entry("list", "seasons_deaths_s3"));

    // A reload moves it at once.
    display("sidebar");
    assertThat(fake.shownObjectives).containsExactly(Map.entry("sidebar", "seasons_deaths_s3"));

    // The next launch makes Season 4's in its place and keeps Season 3's, off the screen.
    launch(admin);
    assertThat(fake.objectives).containsExactly("seasons_deaths_s3", "seasons_deaths_s4");
    assertThat(fake.shownObjectives).containsExactly(Map.entry("sidebar", "seasons_deaths_s4"));

    display("none");
    assertThat(fake.shownObjectives).isEmpty();
    assertThat(fake.objectives).containsExactly("seasons_deaths_s3", "seasons_deaths_s4");
  }
}
