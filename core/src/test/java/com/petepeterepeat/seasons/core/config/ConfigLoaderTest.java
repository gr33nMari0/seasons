// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.config.SeasonsConfig.AdvancementPolicy;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.ReturnFallback;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {
  @TempDir Path dir;

  private Path configDir() {
    return dir.resolve("plugins/Seasons");
  }

  private Path worldDir() {
    return dir.resolve("world/seasons");
  }

  private ConfigLoader loader(Map<String, String> env) {
    return new ConfigLoader(configDir(), worldDir(), env);
  }

  private void write(String file, String text) throws IOException {
    Path path = configDir().resolve(file);
    Files.createDirectories(path.getParent());
    Files.writeString(path, text);
  }

  @Test
  void theBundledDefaultsMatchTheSpec() {
    SeasonsConfig config = ConfigLoader.defaults().config();

    assertThat(config.schedule().autoEnd()).isFalse();
    assertThat(config.schedule().reminders())
        .containsExactly(
            Duration.ofDays(7), Duration.ofDays(1), Duration.ofHours(1), Duration.ofMinutes(10));
    assertThat(config.schedule().endCountdown()).isEqualTo(Duration.ofMinutes(10));
    assertThat(config.resets().advancements()).isEqualTo(AdvancementPolicy.ARCHIVE_AND_RESET);
    assertThat(config.hof().returnSafety().fallbacks())
        .containsExactly(ReturnFallback.BED, ReturnFallback.NEARBY, ReturnFallback.SPAWN);
    assertThat(config.decorating().afterArchive()).isEqualTo(Duration.ofMinutes(15));
    assertThat(config.cells().minPlaytime()).isZero();
  }

  @Test
  void writesDefaultsAndLoadsThem() throws IOException {
    ConfigLoader loader = loader(Map.of());
    loader.writeDefaults();

    ConfigLoader.Result result = loader.load();

    assertThat(result.problems()).isEmpty();
    assertThat(result.warnings()).isEmpty();
    assertThat(configDir().resolve("config.yml")).exists();
    assertThat(configDir().resolve("secrets.yml")).exists();
    assertThat(configDir().resolve("messages.yml")).exists();
    assertThat(result.snapshot())
        .get()
        .extracting(ConfigSnapshot::secrets)
        .isEqualTo(Secrets.none());
  }

  @Test
  void aServerFileOnlyNeedsTheKeysItChanges() throws IOException {
    write("config.yml", "schema-version: 1\nschedule:\n  endCountdown: 2m\n");

    SeasonsConfig config = loader(Map.of()).load().snapshot().orElseThrow().config();

    assertThat(config.schedule().endCountdown()).isEqualTo(Duration.ofMinutes(2));
    assertThat(config.schedule().reminders()).hasSize(4);
    assertThat(config.pets().maxPerPlayer()).isEqualTo(5);
  }

  @Test
  void accessModesNotBuiltYetAreRefused() throws IOException {
    write("config.yml", "schema-version: 1\ncells:\n  access: bounds\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isEmpty();
    assertThat(result.problems())
        .singleElement()
        .satisfies(problem -> assertThat(problem.message()).startsWith("only open is built yet"));
  }

  @Test
  void aSyntaxErrorReportsFileLineAndColumn() throws IOException {
    write("config.yml", "schema-version: 1\nschedule:\n  endCountdown: 3m\n  reminders: [7d, 1d\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isEmpty();
    assertThat(result.problems())
        .singleElement()
        .satisfies(problem -> {
          assertThat(problem.file()).isEqualTo("config.yml");
          assertThat(problem.line()).isPresent();
          assertThat(problem.line().get().line()).isBetween(4, 5);
          assertThat(problem.message()).isNotBlank();
        });
  }

  @Test
  void anInvalidValueReportsItsPath() throws IOException {
    write("config.yml", "schema-version: 1\nschedule:\n  endCountdown: soon\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.problems())
        .singleElement()
        .satisfies(problem -> {
          assertThat(problem.path()).contains("schedule.endCountdown");
          assertThat(problem.message()).contains("'soon' is not a duration");
        });
  }

  @Test
  void validationCatchesOutOfRangeValues() throws IOException {
    write(
        "config.yml",
        "schema-version: 1\npets:\n  maxPerPlayer: 11\nschedule:\n  endCountdown: 0\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.problems())
        .extracting(problem -> problem.path().orElseThrow())
        .containsExactlyInAnyOrder("pets.maxPerPlayer", "schedule.endCountdown");
  }

  @Test
  void unknownKeysWarn() throws IOException {
    write(
        "config.yml",
        "schema-version: 1\nschedule:\n  autoEnd: false\n  colour: blue\nmystery: 1\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.problems()).isEmpty();
    assertThat(result.warnings())
        .containsExactlyInAnyOrder(
            "config.yml: unknown key schedule.colour is ignored",
            "config.yml: unknown key mystery is ignored");
  }

  @Test
  void aNewerSchemaIsRefused() throws IOException {
    write("config.yml", "schema-version: 2\n");

    assertThat(loader(Map.of()).load().problems())
        .singleElement()
        .satisfies(problem -> assertThat(problem.path()).contains("schema-version"));
  }

  @Test
  void webhooksMustBeHttpsAndAreNeverQuoted() throws IOException {
    write(
        "secrets.yml",
        "schema-version: 1\ndiscordWebhook: \"http://example.com/api/webhooks/1/secret\"\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.problems())
        .singleElement()
        .satisfies(problem -> {
          assertThat(problem.path()).contains("discordWebhook");
          assertThat(problem.message()).doesNotContain("secret");
        });
  }

  @Test
  void theEnvironmentOverridesTheSecretsFile() throws IOException {
    write("secrets.yml", "schema-version: 1\ndiscordWebhook: \"https://a.example/file\"\n");

    Secrets secrets =
        loader(Map.of(ConfigLoader.DISCORD_WEBHOOK_ENV, "https://b.example/env"))
            .load()
            .snapshot()
            .orElseThrow()
            .secrets();

    assertThat(secrets.discordWebhook()).contains(URI.create("https://b.example/env"));
    assertThat(secrets.adminWebhook()).isEmpty();
    assertThat(secrets.toString()).doesNotContain("example");
  }

  @Test
  void seasonFilesNeedATimeZone() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    Files.writeString(
        worldDir().resolve("seasons/2.yml"),
        "schema-version: 1\nseason: 2\nschedule:\n  plannedEnd: \"2026-12-05T20:00-05:00\"\n");
    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        "schema-version: 1\nseason: 3\nschedule:\n  plannedEnd: \"2026-12-05T20:00\"\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.problems())
        .singleElement()
        .satisfies(problem -> {
          assertThat(problem.file()).isEqualTo("seasons/3.yml");
          assertThat(problem.path()).contains("schedule.plannedEnd");
        });

    Files.delete(worldDir().resolve("seasons/3.yml"));
    assertThat(loader(Map.of()).load().snapshot().orElseThrow().season(2))
        .contains(new SeasonFile(2, Optional.of(ZonedDateTime.parse("2026-12-05T20:00-05:00"))));
  }

  @Test
  void durationsRoundTrip() {
    for (String text : List.of("7d", "1d", "1h30m", "10m", "10s", "0")) {
      assertThat(Durations.format(Durations.parse(text))).isEqualTo(text);
    }
  }
}
