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
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The launch button (questions 172). */
class LaunchButtonTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Position BUTTON = Position.of(OVERWORLD, 2, 64, 2);
  private static final Position SPAWN = Position.of(OVERWORLD, 500.5, 64, 300.5);

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef admin;

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
        launch: { pos: [2, 64, 2] }
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
    admin = fake.join("Admin");
    fake.sessions.joined(admin, false);
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  /** Ends Season 2 and sets Season 3's spawn, so it can launch. */
  private void intermission() {
    run("/season end --now");
    fake.player(admin).position = SPAWN;
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(admin.id())), "/season spawn set");
  }

  private Decision press(PlayerRef player) {
    return fake.handler.decide(new Intent.UseBlock(player.id(), BUTTON, true));
  }

  private List<String> heard(PlayerRef player) {
    return fake.sent.stream()
        .filter(s -> s.player().equals(player.id()))
        .map(s -> Messages.plain(s.message()))
        .toList();
  }

  private boolean launched() {
    return seasons.currentSeason().orElse(0) == 3;
  }

  @Test
  void twoPressesWithin10SecondsLaunch() {
    intermission();

    assertThat(press(admin)).as("no redstone signal").isNotEqualTo(Decision.allow());
    assertThat(heard(admin).getLast())
        .isEqualTo("Press the launch button again within 10 seconds to launch Season 3.");
    assertThat(launched()).isFalse();

    fake.clock.advance(Duration.ofSeconds(5));
    press(admin);

    assertThat(launched()).isTrue();
  }

  @Test
  void oneThatTimesOutDoesNothing() {
    intermission();
    press(admin);

    fake.clock.advance(Duration.ofSeconds(11));
    press(admin);

    assertThat(launched()).isFalse();
    assertThat(heard(admin).getLast()).startsWith("Press the launch button again");
  }

  @Test
  void aPlayerWithoutTheNodeIsRefusedAndAudited() throws IOException {
    intermission();
    PlayerRef sam = fake.join("Sam");
    fake.withoutAdmin.add(sam.id());

    press(sam);
    press(sam);

    assertThat(launched()).isFalse();
    assertThat(heard(sam).getLast()).isEqualTo("Only an admin can launch the next season.");
    assertThat(Files.readString(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .contains("\"action\":\"hall-blocked\"", "\"actor\":\"Sam\"", "launch at 2, 64, 2");
  }

  @Test
  void outsideTheIntermissionItSaysWhy() {
    press(admin);

    assertThat(heard(admin).getLast())
        .isEqualTo("Season 2 can only be launched from the Intermission.");
  }

  @Test
  void launchsRefusalsStillApply() {
    run("/season end --now");
    press(admin);
    press(admin);

    assertThat(launched()).isFalse();
    assertThat(heard(admin).getLast()).startsWith("Set Season 3's spawn first");
  }
}
