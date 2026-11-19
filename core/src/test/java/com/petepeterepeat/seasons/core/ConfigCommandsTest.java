// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /season config get} and {@code set}: config.yml's settings read as they are in force and
 * written back in place, through the same edit path as the other command edits; secrets never.
 */
class ConfigCommandsTest {
  private static final String URL = "https://discord.example/api/webhooks/1/secret-token";

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;

  @BeforeEach
  void start() {
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

  private Path configFile() {
    return dir.resolve("plugins/Seasons/config.yml");
  }

  private String audit() throws IOException {
    Path file = dir.resolve("world/seasons/audit/2026-11.jsonl");
    return Files.exists(file) ? Files.readString(file, UTF_8) : "";
  }

  @Test
  void getShowsASettingInForceOrEverySettingInASection() {
    assertThat(run("/season config get hof.entry.cooldown"))
        .containsExactly("hof.entry.cooldown = 0s");
    assertThat(run("/season config get schedule.reminders"))
        .containsExactly("schedule.reminders = [7d, 1d, 1h, 10m]");
    assertThat(run("/season config get hof.entry"))
        .startsWith("hof.entry:", "hof.entry.bedDistance = 3")
        .contains("hof.entry.rateLimit = 5s");
    assertThat(run("/season config get hof.nope"))
        .containsExactly(
            "There's no setting called hof.nope. Settings are the keys of config.yml, such as"
                + " hof.entry.cooldown.");
  }

  @Test
  void setWritesTheValueInPlaceKeepsABackupAndIsInForceAndAuditedAtOnce() throws IOException {
    String bundled = Files.readString(configFile(), UTF_8);

    assertThat(run("/season config set hof.entry.cooldown 30s"))
        .containsExactly("Set hof.entry.cooldown to 30s in config.yml (it was 0s).");

    assertThat(Files.readString(configFile(), UTF_8))
        .isEqualTo(bundled.replace("cooldown: 0s", "cooldown: 30s"));
    assertThat(run("/season config get hof.entry.cooldown"))
        .containsExactly("hof.entry.cooldown = 30s");
    assertThat(audit())
        .contains(
            "\"actor\":\"The console\",\"action\":\"config\",\"target\":\"config.yml"
                + " hof.entry.cooldown\",\"result\":\"ok\",\"details\":\"0s -> 30s\"");
    try (Stream<Path> backups = Files.list(dir.resolve("world/seasons/backups"))) {
      assertThat(backups).anyMatch(p -> p.getFileName().toString().startsWith("config.yml"));
    }
    assertThat(run("/season config set hof.entry.cooldown 30s"))
        .containsExactly("hof.entry.cooldown is already 30s.");
  }

  @Test
  void eachRefusalLeavesTheFileAloneAndIsNotAudited() throws IOException {
    String bundled = Files.readString(configFile(), UTF_8);

    assertThat(run("/season config set hof.entry 5"))
        .containsExactly(
            "hof.entry is a section: set one of its keys, such as hof.entry.bedDistance.");
    assertThat(run("/season config set schema-version 2").getFirst())
        .startsWith("There's no setting called schema-version.");
    assertThat(run("/season config set pets.maxPerPlayer 11"))
        .containsExactly(
            "pets.maxPerPlayer wasn't changed: config.yml, at pets.maxPerPlayer: must be between 0"
                + " and 10, not 11");
    assertThat(run("/season config set pets.maxPerPlayer lots").getFirst())
        .startsWith("pets.maxPerPlayer wasn't changed: config.yml, at pets.maxPerPlayer");
    assertThat(run("/season config set cells.access walls").getFirst())
        .startsWith(
            "cells.access wasn't changed: config.yml, at cells.access: 'walls' is not one of");
    assertThat(run("/season config set pets.maxPerPlayer { a: 1 }"))
        .containsExactly("pets.maxPerPlayer wasn't changed: set one key at a time, not a section");

    assertThat(Files.readString(configFile(), UTF_8)).isEqualTo(bundled);
    assertThat(audit()).doesNotContain("\"action\":\"config\"");
    assertThat(run("/season config get pets.maxPerPlayer"))
        .containsExactly("pets.maxPerPlayer = 5");
  }

  @Test
  void aBrokenHandEditNotYetReloadedMakesSetFailAndIsPutBack() throws IOException {
    String broken = Files.readString(configFile(), UTF_8) + "extra:\n  list: [trident\n";
    Files.writeString(configFile(), broken, UTF_8);

    assertThat(run("/season config set pets.maxPerPlayer 4").getFirst())
        .matches("pets\\.maxPerPlayer wasn't changed: config\\.yml, line \\d+, column \\d+: .*");

    assertThat(Files.readString(configFile(), UTF_8)).isEqualTo(broken);
    assertThat(run("/season config get pets.maxPerPlayer"))
        .containsExactly("pets.maxPerPlayer = 5");
  }

  @Test
  void aKeyTheFileLacksIsAddedUnderItsSection() throws IOException {
    Files.writeString(
        configFile(),
        "schema-version: 1\n# Ours.\nschedule:\n  endCountdown: 2m   # short\n",
        UTF_8);
    run("/season reload");

    assertThat(run("/season config set pets.maxPerPlayer 3"))
        .containsExactly("Set pets.maxPerPlayer to 3 in config.yml (it was 5).");

    assertThat(Files.readString(configFile(), UTF_8))
        .isEqualTo(
            """
            schema-version: 1
            # Ours.
            schedule:
              endCountdown: 2m   # short
            pets:
              maxPerPlayer: 3
            """);
  }

  @Test
  void secretsAreNeverShownOrSetAndNeitherIsAnythingElseInTheFile() throws IOException {
    Files.writeString(
        dir.resolve("plugins/Seasons/secrets.yml"),
        "schema-version: 1\ndiscordWebhook: \"" + URL + "\"\n",
        UTF_8);
    Files.writeString(
        configFile(), Files.readString(configFile(), UTF_8) + "stash: \"" + URL + "\"\n", UTF_8);
    assertThat(run("/season reload")).noneMatch(line -> line.contains(URL));

    String refused =
        "Webhook URLs live in secrets.yml or the environment; Seasons never shows or sets them."
            + " If you typed one here, it is now in the server log; rotate it.";
    for (String command :
        List.of(
            "/season config get discordWebhook",
            "/season config get adminWebhook",
            "/season config get secrets.discordWebhook",
            "/season config get zones.webhook",
            "/season config set discordWebhook https://example.com/x",
            "/season config set secrets.adminWebhook https://example.com/x")) {
      assertThat(run(command)).as(command).containsExactly(refused);
    }
    assertThat(run("/season config get stash").getFirst())
        .startsWith("There's no setting called stash.");
    assertThat(run("/season config get schedule"))
        .noneMatch(line -> line.contains(URL) || line.contains("stash"));
    assertThat(audit()).doesNotContain(URL);
  }
}
