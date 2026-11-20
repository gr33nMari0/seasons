// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.RolloverArchiver;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeasonsTest {
  private static final PlayerId ALEX =
      new PlayerId(UUID.fromString("00000000-0000-0000-0000-00000000a1e7"));

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private final List<String> posts = new ArrayList<>();
  private final List<PlayerId> archived = new ArrayList<>();
  private Seasons seasons;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {
          posts.add(content);
        }

        @Override
        public void close() {}
      };

  private final RolloverArchiver recordingArchiver =
      new RolloverArchiver() {
        @Override
        public CompletableFuture<Void> lockGalleries(int season) {
          return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> archive(PlayerId player, int season) {
          archived.add(player);
          return CompletableFuture.completedFuture(null);
        }
      };

  private Seasons start() {
    return start(new Capabilities(EnumSet.allOf(Capability.class)), recordingArchiver);
  }

  /**
   * Starts Seasons on the fake platform.
   *
   * @param archiver ends seasons instead of the real archiver, or null for the real one
   */
  private Seasons start(Capabilities capabilities, @Nullable RolloverArchiver archiver) {
    seasons =
        new Seasons(
            fake.platform(capabilities, dir),
            "1.2.3",
            fake.clock,
            webhooks,
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    if (archiver != null) {
      seasons.useArchiver(archiver);
    }
    seasons.start();
    return seasons;
  }

  /** Stops the running instance and starts a new one on the same files, as a restart would. */
  private void restart() {
    seasons.stop();
    FakePlatform before = fake;
    fake = new FakePlatform();
    fake.clock.advance(Duration.between(fake.clock.instant(), before.clock.instant()));
    fake.online.addAll(before.online);
    start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private TestSource run(String command) {
    TestSource source = TestSource.console();
    run(source, command);
    return source;
  }

  /** Runs a command line against the registered tree, as an adapter would. */
  private void run(TestSource source, String command) {
    TestCommands.run(fake.commands.getFirst(), source, command);
  }

  private List<String> broadcasts() {
    return fake.broadcasts.stream().map(TestSource::plain).toList();
  }

  private Path worldFile(String path) {
    return dir.resolve("world/seasons").resolve(path);
  }

  @Test
  void registersTheSeasonCommandWithPermissions() {
    start();

    assertThat(fake.commands)
        .extracting(CommandNode::name)
        .containsExactly("season", "hof", "vault", "pet", "museum");
    assertThat(fake.commands.getFirst())
        .satisfies(root -> {
          assertThat(root.name()).isEqualTo("season");
          assertThat(root.children())
              .extracting(CommandNode::name, CommandNode::permission)
              .contains(
                  org.assertj.core.groups.Tuple.tuple(
                      "status", Optional.of(Seasons.PERMISSION_ADMIN_SEASON)),
                  org.assertj.core.groups.Tuple.tuple("info", Optional.of(Seasons.PERMISSION_INFO)),
                  org.assertj.core.groups.Tuple.tuple(
                      "reload", Optional.of(Seasons.PERMISSION_ADMIN_CONFIG)));
        });
  }

  @Test
  void statusReportsVersionNoSeasonJournalAndCapabilityGaps() {
    start(new Capabilities(EnumSet.of(Capability.MANNEQUINS)), null);

    TestSource source = run("/season status");

    Messages messages = Messages.defaults();
    assertThat(source.replies.getFirst().placeholders()).containsEntry("version", "1.2.3");
    assertThat(source.replies)
        .extracting(m -> m.template())
        .contains(
            messages.get("status.no-season").template(),
            messages.get("status.journal").template(),
            messages.get("status.capability-gap").template());
  }

  @Test
  void allowsBuildingOutsideTheHall() {
    start();
    PlayerId player = new PlayerId(UUID.randomUUID());

    assertThat(
            fake.handler.decide(
                new Intent.BreakBlock(player, Position.of("minecraft:overworld", 0, 64, 0))))
        .isEqualTo(Decision.allow());
  }

  @Test
  void initRecordsTheSeasonOnceAndWritesItsFile() {
    start();
    assertThat(seasons.currentSeason()).isEmpty();

    assertThat(run("/season init 2").text())
        .containsExactly("Recorded Season 2 as the season in progress.");

    assertThat(seasons.currentSeason()).hasValue(2);
    assertThat(worldFile("seasons/2.yml")).exists();
    assertThat(worldFile("data/season.json")).exists();
    assertThat(run("/season init 3").text().getFirst()).startsWith("Season 2 is already recorded");
    assertThat(run("/season status").text()).contains("Season 2", "Phase: Active, Open stage");
  }

  @Test
  void scheduleEndWritesTheSeasonFileAndNeedsATimeZone() throws IOException {
    start();
    run("/season init 2");

    assertThat(run("/season schedule end 2026-12-05T20:00").text().getFirst())
        .contains("is not an ISO time with a time zone");
    assertThat(run("/season schedule end 2026-11-01T20:00Z").text())
        .containsExactly("That time has already passed.");
    List<String> reply = run("/season schedule end 2026-12-05T20:00-05:00").text();

    assertThat(reply.getFirst())
        .startsWith("Season 2 now ends at 2026-12-05 20:00 -05:00 (2026-12-06 01:00 UTC)");
    assertThat(reply).hasSize(1);
    assertThat(Files.readString(worldFile("seasons/2.yml")))
        .contains("plannedEnd: '2026-12-05T20:00:00-05:00'");
    assertThat(run("/season schedule end 2026-11-20T20:00Z").text())
        .contains("That is less than a week away.");
  }

  @Test
  void remindersGoToChatAndDiscordOnceEach() throws IOException {
    Files.createDirectories(dir.resolve("plugins/Seasons"));
    Files.writeString(
        dir.resolve("plugins/Seasons/secrets.yml"),
        "schema-version: 1\ndiscordWebhook: \"https://discord.example/hook\"\n");
    start();
    run("/season init 2");
    Instant end = fake.clock.instant().plus(Duration.ofDays(8));
    run("/season schedule end " + end);

    fake.clock.advance(Duration.ofDays(1).minusSeconds(5));
    fake.runFor(Duration.ofSeconds(10));
    fake.clock.advance(Duration.ofDays(6).minusSeconds(10));
    fake.runFor(Duration.ofSeconds(10));
    fake.clock.advance(Duration.ofHours(23).minusSeconds(10));
    fake.runFor(Duration.ofSeconds(10));
    fake.clock.advance(Duration.ofMinutes(50).minusSeconds(10));
    fake.runFor(Duration.ofSeconds(10));
    fake.clock.advance(Duration.ofMinutes(20));
    fake.runFor(Duration.ofSeconds(10));

    assertThat(broadcasts())
        .extracting(s -> s.replaceAll(",.*", ""))
        .containsExactly(
            "Season 2 ends in 7 days",
            "Season 2 ends in 1 day",
            "Season 2 ends in 1 hour",
            "Season 2 ends in 10 minutes");
    assertThat(posts).hasSize(4).allMatch(p -> p.startsWith("Season 2 ends in "));
    assertThat(fake.visibleBars()).isEmpty();
    assertThat(run("/season info").text()).contains("Phase: Active, Open stage");
  }

  @Test
  void theCountdownShowsABarCanBeCancelledAndEndsInTheIntermission() {
    start();
    fake.online.add(new PlayerRef(ALEX, "Alex"));
    run("/season init 2");

    run("/season end");
    assertThat(broadcasts())
        .containsExactly("Season 2 ends in 10 minutes. Finish what you're doing!");
    assertThat(fake.visibleBars())
        .singleElement()
        .satisfies(
            bar -> assertThat(TestSource.plain(bar.title)).isEqualTo("Season 2 ends in 10:00"));
    fake.runFor(Duration.ofSeconds(61));
    assertThat(TestSource.plain(fake.visibleBars().getFirst().title))
        .isEqualTo("Season 2 ends in 8:59");
    assertThat(run("/season end").text().getFirst()).startsWith("Season 2 is already ending");

    run("/season cancel");
    assertThat(fake.visibleBars()).isEmpty();
    assertThat(broadcasts()).last().isEqualTo("The end of Season 2 was cancelled. Play on!");
    assertThat(archived).isEmpty();
    assertThat(run("/season info").text()).contains("Phase: Active, Open stage");

    run("/season end");
    fake.runFor(Duration.ofMinutes(10));
    assertThat(archived).containsExactly(ALEX);
    assertThat(broadcasts()).last().isEqualTo("Season 2 has ended. Welcome to the Intermission.");
    assertThat(run("/season info").text()).contains("Phase: Intermission");
    assertThat(run("/season cancel").text()).containsExactly("There is no countdown to cancel.");
    assertThat(run("/season end").text().getFirst()).startsWith("Season 2 has already ended");
  }

  @Test
  void endNowSkipsTheCountdown() {
    start();
    fake.online.add(new PlayerRef(ALEX, "Alex"));
    run("/season init 2");

    assertThat(run("/season end --now").text())
        .containsExactly("Ending Season 2 now: archiving 1 online players.");

    assertThat(fake.bars).isEmpty();
    assertThat(archived).containsExactly(ALEX);
    assertThat(run("/season info").text()).contains("Phase: Intermission");
  }

  @Test
  void theDryRunChangesNothing() {
    start();
    fake.online.add(new PlayerRef(ALEX, "Alex"));
    run("/season init 2");

    List<String> report = run("/season end --dry-run").text();

    assertThat(report.getFirst()).startsWith("Dry run of /season end for Season 2");
    assertThat(report).contains("Online players to archive (1): Alex");
    assertThat(run("/season info").text()).contains("Phase: Active, Open stage");
    assertThat(run("/season end --force").text())
        .containsExactly("Unknown option --force. /season end takes --dry-run and --now.");
  }

  @Test
  void aRestartCancelsTheCountdownAndKeepsSentReminders() {
    start();
    run("/season init 2");
    run("/season schedule end " + fake.clock.instant().plus(Duration.ofDays(1)).plusSeconds(3));
    fake.runFor(Duration.ofSeconds(5));
    run("/season end");
    assertThat(broadcasts()).hasSize(2);

    restart();
    fake.runFor(Duration.ofSeconds(5));

    assertThat(broadcasts()).isEmpty();
    assertThat(fake.visibleBars()).isEmpty();
    assertThat(run("/season info").text())
        .contains("Phase: Active, Open stage")
        .anyMatch(l -> l.startsWith("Planned end: "));
    assertThat(run("/season status").text())
        .anyMatch(l -> l.startsWith("The end countdown was interrupted by a restart at "));
  }

  @Test
  void aRestartDuringArchivingFinishesTheEndSequence() {
    start(
        new Capabilities(EnumSet.allOf(Capability.class)),
        new RolloverArchiver() {
          @Override
          public CompletableFuture<Void> lockGalleries(int season) {
            return new CompletableFuture<>();
          }

          @Override
          public CompletableFuture<Void> archive(PlayerId player, int season) {
            return new CompletableFuture<>();
          }
        });
    run("/season init 2");
    run("/season end --now");
    assertThat(run("/season info").text()).contains("Phase: Ending, archiving players");

    restart();

    assertThat(run("/season info").text()).contains("Phase: Intermission");
  }

  @Test
  void reloadSwapsEverythingOrNothing() throws IOException {
    start();
    run("/season init 2");
    Path config = dir.resolve("plugins/Seasons/config.yml");
    Files.writeString(config, "schema-version: 1\nschedule:\n  endCountdown: 2m\n");
    assertThat(run("/season reload").text())
        .containsExactly(
            "Reloaded config.yml, messages.yml, secrets.yml, the season files, the galleries and the waypoints.");

    Files.writeString(
        config, "schema-version: 1\nschedule:\n  endCountdown: 3m\n  reminders: [7d, 1d\n");
    List<String> failed = run("/season reload").text();
    assertThat(failed.getFirst()).isEqualTo("Reload failed. The previous config stays live.");
    assertThat(failed.get(1)).matches("config\\.yml, line \\d+, column \\d+: \\S.*");
    assertThat(run("/season validate").text().get(1))
        .matches("config\\.yml, line \\d+, column \\d+: \\S.*");

    run("/season end");
    assertThat(broadcasts())
        .containsExactly("Season 2 ends in 2 minutes. Finish what you're doing!");
  }

  @Test
  void aFailedReloadAlsoReachesTheOtherAdminsAndTheLog() throws IOException {
    PlayerId sam = new PlayerId(UUID.fromString("00000000-0000-0000-0000-000000005a77"));
    fake.online.add(new PlayerRef(ALEX, "Alex"));
    fake.online.add(new PlayerRef(sam, "Sam"));
    start();
    Files.writeString(
        dir.resolve("plugins/Seasons/config.yml"), "schema-version: 1\nschedule: [7d, 1d\n");

    TestSource alex = new TestSource(Optional.of(ALEX));
    run(alex, "/season reload");

    assertThat(alex.text().getFirst()).isEqualTo("Reload failed. The previous config stays live.");
    assertThat(alex.text().get(1)).matches("config\\.yml, line \\d+, column \\d+: \\S.*");
    List<String> toSam =
        fake.sent.stream()
            .filter(s -> s.player().equals(sam))
            .map(s -> TestSource.plain(s.message()))
            .toList();
    assertThat(toSam.getFirst())
        .isEqualTo("Alex ran /season reload and it failed. The previous config stays live.");
    assertThat(toSam.get(1)).matches("config\\.yml, line \\d+, column \\d+: \\S.*");
    assertThat(fake.sent).noneMatch(s -> s.player().equals(ALEX));
  }

  @Test
  void nobodyIsArchivedWhenTheEndingPhaseCantBeSaved() throws IOException {
    fake.online.add(new PlayerRef(ALEX, "Alex"));
    start();
    run("/season init 2");
    // A directory where season.json goes makes every save fail.
    Path state = worldFile("data/season.json");
    Files.delete(state);
    Files.createDirectories(state.resolve("blocked"));

    run("/season end --now");

    assertThat(archived).isEmpty();
    assertThat(fake.sent)
        .map(s -> TestSource.plain(s.message()))
        .anyMatch(line -> line.startsWith("Seasons couldn't save season.json: "))
        .anyMatch(line -> line.startsWith("Season 2 wasn't ended: season.json couldn't be saved"));
  }

  @Test
  void aHandEditedPlannedEndTakesEffectOnReload() throws IOException {
    start();
    run("/season init 2");
    Files.writeString(
        worldFile("seasons/2.yml"),
        "schema-version: 1\nseason: 2\nschedule:\n  plannedEnd: \"2026-12-24T18:00Z\"\n");

    run("/season reload");

    assertThat(run("/season info").text())
        .anyMatch(
            line -> line.startsWith("Planned end: ") && line.endsWith("(2026-12-24 18:00 UTC)"));
  }

  @Test
  void autoEndStartsTheCountdownAtThePlannedEndOnce() throws IOException {
    Files.createDirectories(dir.resolve("plugins/Seasons"));
    Files.writeString(
        dir.resolve("plugins/Seasons/config.yml"),
        "schema-version: 1\nschedule:\n  autoEnd: true\n");
    start();
    run("/season init 2");
    run("/season schedule end " + fake.clock.instant().plusSeconds(30));

    fake.runFor(Duration.ofSeconds(31));
    assertThat(run("/season info").text()).contains("Phase: Ending, countdown running");
    run("/season cancel");
    fake.runFor(Duration.ofSeconds(5));

    assertThat(run("/season info").text()).contains("Phase: Active, Open stage");
  }

  @Test
  void aBrokenConfigAtStartupFallsBackToDefaultsAndSaysSo() throws IOException {
    Files.createDirectories(dir.resolve("plugins/Seasons"));
    Files.writeString(
        dir.resolve("plugins/Seasons/config.yml"), "schema-version: 1\nschedule: [\n");

    start();

    assertThat(run("/season status").text())
        .anyMatch(line ->
            line.startsWith(
                "Config problem at startup, so the bundled defaults are in use: config.yml, line"));
  }
}
