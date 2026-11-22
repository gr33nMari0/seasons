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
  void everyAccessModeLoads() throws IOException {
    for (String access : List.of("open", "bounds", "gate")) {
      write("config.yml", "schema-version: 1\ncells:\n  access: " + access + "\n");

      ConfigLoader.Result result = loader(Map.of()).load();

      assertThat(result.problems()).as(access).isEmpty();
      assertThat(result.snapshot().orElseThrow().config().cells().access().name())
          .isEqualToIgnoringCase(access);
    }
  }

  @Test
  void unknownItemsAreProblemsWithTheirPath() throws IOException {
    write(
        "config.yml",
        """
        schema-version: 1
        firsts:
          rareItems: [trident, minecraft:not_a_thing]
        cells:
          containers: [chest, not_a_box]
          gateBlock: lava
        """);

    ConfigLoader.Result result =
        new ConfigLoader(
                configDir(),
                worldDir(),
                Map.of(),
                dimension -> true,
                id -> !id.contains("not_"),
                id -> !id.equals("minecraft:lava"))
            .load();

    assertThat(result.snapshot()).isEmpty();
    assertThat(result.problems())
        .extracting(ConfigProblem::path, ConfigProblem::message)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                Optional.of("firsts.rareItems"),
                "minecraft:not_a_thing isn't an item this server knows"),
            org.assertj.core.groups.Tuple.tuple(
                Optional.of("cells.containers"), "not_a_box isn't an item this server knows"),
            org.assertj.core.groups.Tuple.tuple(
                Optional.of("cells.gateBlock"),
                "lava can't be a gate: it must be a solid block that doesn't fall and holds"
                    + " nothing"));
  }

  @Test
  void requireManualFalseIsRefused() throws IOException {
    write("config.yml", "schema-version: 1\nlaunch:\n  requireManual: false\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isEmpty();
    assertThat(result.problems())
        .singleElement()
        .satisfies(problem -> assertThat(problem.message()).startsWith("only true is built"));
  }

  @Test
  void deathsDisplayIsOneOfFour() throws IOException {
    for (String slot : List.of("list", "sidebar", "below_name", "none")) {
      write("config.yml", "schema-version: 1\ndeaths:\n  display: " + slot + "\n");
      assertThat(loader(Map.of()).load().snapshot())
          .get()
          .extracting(s -> s.config().deaths().display())
          .isEqualTo(slot);
    }
    write("config.yml", "schema-version: 1\ndeaths:\n  display: tab\n");

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isEmpty();
    assertThat(result.problems())
        .extracting(ConfigProblem::path, ConfigProblem::message)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                Optional.of("deaths.display"),
                "must be list, sidebar, below_name or none, not tab"));
  }

  @Test
  void awardsLoadAndBadOnesSayWhere() throws IOException {
    ConfigLoader.Result bundled = loader(Map.of()).load();
    assertThat(bundled.snapshot().get().config().awards())
        .containsOnlyKeys(
            "most-deaths",
            "furthest-travelled",
            "most-dedicated",
            "deepest-pockets",
            "dragon-slayer",
            "furthest-builder",
            "most-advancements");
    assertThat(bundled.snapshot().get().config().awards().get("most-dedicated").parsed())
        .contains(AwardMeasure.Named.PLAY_TIME);

    write(
        "config.yml",
        """
        schema-version: 1
        awards:
          deepest-pockets: { measure: off }
          most-jumps: { measure: minecraft:jump, item: rabbit_foot }
        """);
    ConfigLoader.Result ours = loader(Map.of()).load();
    assertThat(ours.problems()).isEmpty();
    assertThat(ours.warnings()).isEmpty();
    var awards = ours.snapshot().get().config().awards();
    assertThat(awards.get("deepest-pockets").on()).isFalse();
    assertThat(awards.get("most-jumps").parsed())
        .contains(new AwardMeasure.Statistic("minecraft:jump"));
    // The server's own award is a setting too, for /season config.
    assertThat(ours.snapshot().get().settings())
        .containsEntry("awards.most-jumps.measure", "minecraft:jump")
        .containsEntry("awards.most-jumps.item", "rabbit_foot")
        // YAML reads a bare off as false, which is off too.
        .containsEntry("awards.deepest-pockets.measure", "false");

    write(
        "config.yml",
        """
        schema-version: 1
        awards:
          most-deaths: { measure: kills }
          Most Hops: { measure: minecraft:jump, item: rabbit_foot }
          no-item: { measure: deaths }
          bad-item: { measure: deaths, item: not_a_thing }
          odd: { measure: deaths, item: stick, colour: red }
        """);
    ConfigLoader.Result bad =
        new ConfigLoader(
                configDir(),
                worldDir(),
                Map.of(),
                dimension -> true,
                id -> !id.contains("not_"),
                id -> true)
            .load();

    assertThat(bad.snapshot()).isEmpty();
    assertThat(bad.problems())
        .extracting(ConfigProblem::path, ConfigProblem::message)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                Optional.of("awards.most-deaths.measure"),
                "kills isn't a measure: use deaths, travelled, play-time, diamonds, dragon,"
                    + " furthest-builder, advancements, off or a statistic id such as"
                    + " minecraft:jump"),
            org.assertj.core.groups.Tuple.tuple(
                Optional.of("awards.Most Hops"),
                "award ids are lower-case letters, digits and dashes, such as most-deaths"),
            org.assertj.core.groups.Tuple.tuple(Optional.of("awards.no-item.item"), "is missing"),
            org.assertj.core.groups.Tuple.tuple(
                Optional.of("awards.bad-item.item"),
                "not_a_thing isn't an item this server knows"));
    assertThat(bad.warnings()).contains("config.yml: unknown key awards.odd.colour is ignored");
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
  void bedDistanceIsZeroToSixteen() throws IOException {
    write("config.yml", "schema-version: 1\nhof:\n  entry: { bedDistance: 17 }\n");

    assertThat(loader(Map.of()).load().problems())
        .extracting(problem -> problem.path().orElseThrow())
        .containsExactly("hof.entry.bedDistance");
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
  void aSpawnInsideAZoneLoadsWithAWarningAndABrokenZoneDoesNot() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        """
        schema-version: 1
        season: 3
        spawn: { dimension: minecraft:overworld, pos: [3000, -60, 0] }
        zones:
          pen: { dimension: minecraft:overworld, shape: { cylinder: { center: [3000, 0], radius: 20 } } }
        """);

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().get().season(3).orElseThrow().zones()).containsKey("pen");
    assertThat(result.warnings())
        .containsExactly(
            "seasons/3.yml: Season 3's spawn at 3000, -60, 0 is inside zone pen; /season launch is"
                + " refused until one of them moves");

    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        "schema-version: 1\nseason: 3\nzones:\n  pen: { shape: { cylinder: { center: [0, 0] } } }\n");
    assertThat(loader(Map.of()).load().problems())
        .singleElement()
        .satisfies(problem -> {
          assertThat(problem.file()).isEqualTo("seasons/3.yml");
          assertThat(problem.path()).contains("zones.pen.shape.cylinder.radius");
        });
  }

  @Test
  void aSpawnOrCandidateBeyondAnOutsideZoneOrInAHeightZoneLoadsWithAWarning() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        """
        schema-version: 1
        season: 3
        spawn: { dimension: minecraft:overworld, pos: [3000, -60, 0] }
        spawnCandidates:
          mesa: { pos: [3010, 80, 0] }
          dune: { pos: [3005, 60, 0] }
        zones:
          frontier: { shape: { outside: { circle: { center: [3300, 0], radius: 295 } } } }
          sky: { shape: { y-above: 70 } }
        """);

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isPresent();
    assertThat(result.warnings())
        .containsExactlyInAnyOrder(
            "seasons/3.yml: Season 3's spawn at 3000, -60, 0 is inside zone frontier; /season"
                + " launch is refused until one of them moves",
            "seasons/3.yml: Season 3's spawn candidate mesa at 3010, 80, 0 is inside zone sky;"
                + " every candidate must stay outside every zone");
  }

  @Test
  void spawnCandidatesLoadAndOneInsideAZoneIsAWarning() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        """
        schema-version: 1
        season: 3
        spawnCandidates:
          north-mesa: { pos: [3200, 80, -20100] }
          east-jungle: { dimension: minecraft:overworld, pos: [19400, 70, 4100], yaw: 90 }
          stray: { pos: [3005, -60, 0] }
        zones:
          pen: { dimension: minecraft:overworld, shape: { cylinder: { center: [3000, 0], radius: 20 } } }
        """);

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isPresent();
    assertThat(result.snapshot().get().season(3).orElseThrow().spawnCandidates())
        .containsOnlyKeys("east-jungle", "north-mesa", "stray")
        .containsEntry(
            "north-mesa",
            new com.petepeterepeat.seasons.core.port.Position(
                "minecraft:overworld", 3200, 80, -20100, 0f, 0f));
    assertThat(result.warnings())
        .containsExactly(
            "seasons/3.yml: Season 3's spawn candidate stray at 3005, -60, 0 is inside zone pen;"
                + " every candidate must stay outside every zone");
  }

  @Test
  void aSpawnBeyond24000BlocksFromTheOriginIsAWarning() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        "schema-version: 1\nseason: 3\nspawn: { pos: [25000, -60, 0] }\n");
    Files.writeString(
        worldDir().resolve("seasons/4.yml"),
        "schema-version: 1\nseason: 4\nspawn: { pos: [16970, 70, 16970] }\n");
    Files.writeString(
        worldDir().resolve("seasons/5.yml"),
        """
        schema-version: 1
        season: 5
        spawn: { dimension: minecraft:the_nether, pos: [25000, 70, 0] }
        """);

    ConfigLoader.Result result = loader(Map.of()).load();

    assertThat(result.snapshot()).isPresent();
    assertThat(result.warnings())
        .as("only the overworld's strongholds count, and 16970, 16970 is within 24,000")
        .containsExactly(
            "seasons/3.yml: Season 3's spawn at 25000, -60, 0 is 25000 blocks from 0, 0, beyond"
                + " 24,000; strongholds may be out of reach");
  }

  @Test
  void aCandidateWithABadNamePositionOrDimensionIsAProblem() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    ConfigLoader server =
        new ConfigLoader(
            configDir(), worldDir(), Map.of(), dimension -> !dimension.endsWith("the_neter"));
    for (String candidate :
        List.of(
            "North_Mesa: { pos: [1, 2, 3] }",
            "north-mesa: { pos: [1, 2] }",
            "north-mesa: {}",
            "north-mesa: { dimension: minecraft:the_neter, pos: [1, 2, 3] }")) {
      Files.writeString(
          worldDir().resolve("seasons/3.yml"),
          "schema-version: 1\nseason: 3\nspawnCandidates:\n  " + candidate + "\n");
      assertThat(server.load().problems())
          .as(candidate)
          .singleElement()
          .satisfies(problem -> {
            assertThat(problem.file()).isEqualTo("seasons/3.yml");
            assertThat(problem.path().orElseThrow()).startsWith("spawnCandidates.");
          });
    }
  }

  @Test
  void waypointsLoadFromMarkersYmlAndABrokenOneIsAProblem() throws IOException {
    Files.createDirectories(worldDir());
    Files.writeString(
        worldDir().resolve("markers.yml"),
        """
        schema-version: 1
        sets:
          megaprojects:
            Peter's Tower: { dimension: minecraft:overworld, pos: [100, 64, -200] }
            "<red>Hub</red>": { pos: [0, 70, 0] }
          farms:
            Iron Farm: { dimension: minecraft:the_nether, pos: [10, 70, -20] }
        """);

    ConfigLoader.Result result = loader(Map.of()).load();

    var waypoints = result.snapshot().orElseThrow().waypoints();
    assertThat(waypoints.sets()).containsOnlyKeys("farms", "megaprojects");
    assertThat(waypoints.sets().get("megaprojects"))
        .extracting(w -> w.name())
        .containsExactly("<red>Hub</red>", "Peter's Tower");
    assertThat(waypoints.find("megaprojects", "PETER'S TOWER").orElseThrow().at())
        .isEqualTo(
            new com.petepeterepeat.seasons.core.port.Position(
                "minecraft:overworld", 100, 64, -200, 0f, 0f));

    ConfigLoader server =
        new ConfigLoader(
            configDir(), worldDir(), Map.of(), dimension -> !dimension.endsWith("the_neter"));
    for (String broken :
        List.of(
            "Mega_Projects: { Hub: { pos: [1, 2, 3] } }",
            "hubs: { Half: { pos: [1, 2] } }",
            "hubs: { Hub: { pos: [1, 2, 3] }, HUB: { pos: [4, 5, 6] } }",
            "hubs: { Hub: { dimension: minecraft:the_neter, pos: [1, 2, 3] } }",
            "hubs: { \"" + "x".repeat(65) + "\": { pos: [1, 2, 3] } }")) {
      Files.writeString(
          worldDir().resolve("markers.yml"), "schema-version: 1\nsets:\n  " + broken + "\n");
      assertThat(server.load().problems())
          .as(broken)
          .singleElement()
          .satisfies(problem -> {
            assertThat(problem.file()).isEqualTo("markers.yml");
            assertThat(problem.path().orElseThrow()).startsWith("sets.");
          });
    }
  }

  @Test
  void aBrokenMarkersFileIsLeftOutAtStartupButStopsAReload() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        """
        schema-version: 1
        season: 3
        zones:
          base: { dimension: minecraft:overworld, shape: { cylinder: { center: [500, 500], radius: 30 } } }
        """);
    Files.writeString(
        worldDir().resolve("markers.yml"),
        "schema-version: 1\nsets:\n  hubs: { Hub: { pos: [1, 2] } }\n");

    ConfigLoader.Result startup = loader(Map.of()).loadAtStartup();

    ConfigSnapshot snapshot = startup.snapshot().orElseThrow();
    assertThat(snapshot.season(3).orElseThrow().zones()).containsOnlyKeys("base");
    assertThat(snapshot.waypoints().sets()).isEmpty();
    assertThat(snapshot.markersLeftOut())
        .singleElement()
        .satisfies(problem -> {
          assertThat(problem.file()).isEqualTo("markers.yml");
          assertThat(problem.path()).contains("sets.hubs.Hub");
        });
    assertThat(startup.problems()).isEmpty();

    ConfigLoader.Result reload = loader(Map.of()).load();
    assertThat(reload.snapshot()).isEmpty();
    assertThat(reload.problems())
        .singleElement()
        .satisfies(problem -> assertThat(problem.file()).isEqualTo("markers.yml"));
  }

  @Test
  void aZoneInADimensionTheServerDoesntHaveIsAProblem() throws IOException {
    Files.createDirectories(worldDir().resolve("seasons"));
    Files.writeString(
        worldDir().resolve("seasons/3.yml"),
        """
        schema-version: 1
        season: 3
        zones:
          old-nether: { dimension: minecraft:the_neter, shape: { cylinder: { center: [0, 0], radius: 1500 } } }
        """);
    ConfigLoader server =
        new ConfigLoader(
            configDir(),
            worldDir(),
            Map.of(),
            dimension -> !dimension.equals("minecraft:the_neter"));

    ConfigLoader.Result result = server.load();

    assertThat(result.snapshot()).isEmpty();
    assertThat(result.problems())
        .singleElement()
        .satisfies(problem -> {
          assertThat(problem.file()).isEqualTo("seasons/3.yml");
          assertThat(problem.path()).contains("zones.old-nether.dimension");
          assertThat(problem.toString()).contains("minecraft:the_neter");
        });
  }

  @Test
  void durationsRoundTrip() {
    for (String text : List.of("7d", "1d", "1h30m", "10m", "10s", "0")) {
      assertThat(Durations.format(Durations.parse(text))).isEqualTo(text);
    }
  }
}
