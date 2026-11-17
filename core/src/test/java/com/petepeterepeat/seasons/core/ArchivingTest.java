// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveHash;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.hall.Hall;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Archiving end to end on the fake platform: the real archiver, journal, files and commands. */
class ArchivingTest {
  private static final Map<String, Map<String, Instant>> ADVANCEMENTS =
      Map.of(
          "minecraft:story/root", Map.of("crafting_table", Instant.parse("2026-10-01T10:00:00Z")));

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private ScenarioHooks hooks;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  private void start() {
    hooks =
        new ScenarioHooks(() -> {
          throw new Halted();
        });
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            Hall.none(),
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.of(hooks));
    seasons.start();
  }

  /**
   * Starts again on the same files with nobody online, as after a crash; players keep what their
   * saved player file held, which here is what they last had.
   */
  private void restart() {
    seasons.stop();
    FakePlatform before = fake;
    fake = new FakePlatform();
    fake.clock.advance(Duration.between(fake.clock.instant(), before.clock.instant()));
    fake.players.putAll(before.players);
    start();
  }

  /** What the crash hooks throw instead of halting the JVM. */
  static final class Halted extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    if (fake.sessions != null) {
      fake.sessions.joined(player, fake.player(player).saves > 0);
    }
    return player;
  }

  private PlayerRef withGear(String name) {
    PlayerRef player = fake.join(name);
    fake.player(player).inventory = FakeItems.gear();
    fake.player(player).advancements.putAll(ADVANCEMENTS);
    return player;
  }

  private Optional<ArchiveRecord> archive(PlayerRef player, int season) throws IOException {
    return new ArchiveFiles(dir.resolve("world/seasons/data/archives")).read(player.id(), season);
  }

  private Optional<List<Optional<NeutralItem>>> vault(PlayerRef player, int season)
      throws IOException {
    return new VaultFiles(dir.resolve("world/seasons/data/vaults")).page(player.id(), season);
  }

  private PlayerRecord record(PlayerRef player) {
    return seasons.players().orElseThrow().find(player.id()).orElseThrow();
  }

  private boolean isEmpty(PlayerRef player) {
    return ArchiveHash.isEmpty(fake.player(player).inventory, true);
  }

  private void setSpawn(int x, int z) {
    PlayerRef admin = fake.join("Admin");
    fake.player(admin).position = Position.of("minecraft:overworld", x + 0.3, -60, z + 0.7);
    TestSource source = new TestSource(Optional.of(admin.id()));
    TestCommands.run(fake.commands.getFirst(), source, "/season spawn set");
    assertThat(source.text()).singleElement().asString().startsWith("Season ");
    fake.leave(admin);
  }

  @Test
  void theEndArchivesEveryoneOnlineIntoTheSeason() throws IOException {
    start();
    PlayerRef alex = withGear("Alex");
    run("/season init 2");

    run("/season end --now");

    ArchiveRecord archived = archive(alex, 2).orElseThrow();
    assertThat(archived.inventory()).isEqualTo(FakeItems.gear());
    assertThat(archived.advancements().awarded()).isEqualTo(ADVANCEMENTS);
    assertThat(archived.experience().level()).isEqualTo(5);
    assertThat(archived.skin()).isPresent();
    assertThat(vault(alex, 2).orElseThrow())
        .contains(Optional.of(FakeItems.item("minecraft:emerald", 5)));
    assertThat(isEmpty(alex)).isTrue();
    assertThat(fake.player(alex).advancements).isEmpty();
    assertThat(fake.player(alex).experience.level()).isZero();
    assertThat(fake.player(alex).saves).isGreaterThanOrEqualTo(2);
    assertThat(record(alex).archivedThrough()).isEqualTo(2);
    assertThat(run("/season info")).contains("Phase: Intermission");
    assertThat(run("/season status"))
        .contains("Players: 1 archived into Season 2, 0 still to archive at login");
    assertThat(Files.readString(dir.resolve("world/seasons/data/journal.log")))
        .contains("ARCHIVE_STARTED", "VAULT_SEALED", "ARCHIVE_DONE");
    assertThat(Files.readString(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .contains("\"action\":\"archive\"", "\"target\":\"Alex\"");
  }

  @Test
  void archivingAgainTakesNothing() throws IOException {
    start();
    PlayerRef alex = withGear("Alex");
    run("/season init 2");
    assertThat(run("/season archive Alex"))
        .containsExactly("Alex has no finished season waiting to be archived.");
    run("/season end --now");
    fake.player(alex).inventory = FakeItems.gear();

    assertThat(run("/season archive Alex"))
        .containsExactly("Alex has no finished season waiting to be archived.");
    assertThat(run("/season archive Nobody").getFirst()).startsWith("Nobody isn't online.");
    assertThat(run("/season reprocess Alex")).containsExactly("Alex has nothing pending.");

    assertThat(isEmpty(alex)).isFalse();
    assertThat(archive(alex, 2).orElseThrow().inventory()).isEqualTo(FakeItems.gear());
  }

  @Test
  void anOfflinePlayerIsArchivedAtLoginAndAfterLaunchGoesToTheNewSpawn() throws IOException {
    start();
    PlayerRef early = join("Early");
    PlayerRef late = join("Late");
    fake.player(early).inventory = FakeItems.gear();
    fake.player(late).inventory = FakeItems.gear();
    run("/season init 2");
    fake.leave(early);
    fake.leave(late);
    run("/season end --now");
    assertThat(run("/season status"))
        .contains("Players: 0 archived into Season 2, 2 still to archive at login");

    join("Early");
    assertThat(record(early).archivedThrough()).isEqualTo(2);
    assertThat(isEmpty(early)).isTrue();
    assertThat(fake.player(early).position.x()).isEqualTo(10);

    assertThat(run("/season launch"))
        .containsExactly("Set Season 3's spawn first: stand there and run /season spawn set.");
    setSpawn(500, 300);
    assertThat(run("/season launch --dry-run"))
        .containsExactly(
            "Dry run of /season launch for Season 3. Nothing changes.",
            "1 online players would go to 500, -60, 300 in minecraft:overworld, in survival.");
    run("/season launch");
    assertThat(fake.broadcasts).map(TestSource::plain).contains("Season 3 has begun!");
    assertThat(fake.player(early).position)
        .isEqualTo(Position.of("minecraft:overworld", 500.5, -60, 300.5));
    assertThat(fake.player(early).survival).isTrue();
    assertThat(fake.worldSpawn.blockX()).isEqualTo(500);
    assertThat(fake.dragonKilled).isFalse();
    assertThat(fake.objectives).containsExactly("seasons_deaths_s3");
    assertThat(fake.handler.decide(new Intent.TakeDamage(early.id())))
        .isNotEqualTo(Decision.allow());
    fake.clock.advance(Duration.ofSeconds(6));
    assertThat(fake.handler.decide(new Intent.TakeDamage(early.id()))).isEqualTo(Decision.allow());

    join("Late");
    assertThat(archive(late, 2)).isPresent();
    assertThat(isEmpty(late)).isTrue();
    assertThat(fake.player(late).position.x()).isEqualTo(500.5);
    assertThat(fake.player(late).survival).isTrue();
    assertThat(record(late).seasonEntered()).isEqualTo(3);
    assertThat(run("/season status"))
        .contains("Players: 2 archived into Season 2, 0 still to archive at login");
  }

  @Test
  void aPlayerFromBeforeSeasonsIsArchivedIntoTheInitSeason() throws IOException {
    PlayerRef old = fake.join("OldTimer");
    fake.player(old).inventory = FakeItems.gear();
    fake.player(old).saves = 1;
    fake.leave(old);
    start();
    run("/season init 2");
    run("/season end --now");
    setSpawn(300, 300);
    run("/season launch");

    join("OldTimer");

    assertThat(archive(old, 2).orElseThrow().inventory()).isEqualTo(FakeItems.gear());
    assertThat(fake.player(old).position.x()).isEqualTo(300.5);
  }

  @ParameterizedTest
  @EnumSource(CrashPoint.class)
  void aCrashAtAnyStepEndsWithEveryItemExactlyOnce(CrashPoint point) throws IOException {
    start();
    PlayerRef casey = withGear("Casey");
    run("/season init 2");
    hooks.arm(point, casey.id());

    run("/season end --now");
    restart();
    join("Casey");

    assertThat(record(casey).archivedThrough()).isEqualTo(2);
    assertThat(isEmpty(casey)).isTrue();
    assertThat(archive(casey, 2).orElseThrow().inventory()).isEqualTo(FakeItems.gear());
    assertThat(vault(casey, 2).orElseThrow().stream().filter(Optional::isPresent)).hasSize(1);
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void recoveryStopsIfTheInventoryNeitherMatchesNorIsEmpty() throws IOException {
    start();
    PlayerRef casey = withGear("Casey");
    run("/season init 2");
    hooks.arm(CrashPoint.ARCHIVE_STARTED, casey.id());
    run("/season end --now");
    restart();
    fake.player(casey).inventory =
        new com.petepeterepeat.seasons.core.port.InventorySnapshot(
            List.of(Optional.of(FakeItems.item("minecraft:stone", 1))),
            List.of(),
            Optional.empty(),
            List.of());

    join("Casey");

    assertThat(isEmpty(casey)).isFalse();
    assertThat(run("/season status"))
        .anyMatch(line -> line.endsWith(", 1 waiting for recovery"))
        .anyMatch(line -> line.startsWith("Recovery of Casey's Season 2 archive stopped"));
    assertThat(run("/season reprocess Casey").getFirst())
        .startsWith("Casey still has 1 operation(s) pending");
  }

  @Test
  void unarchiveRestoresAndKeepsWhateverWasInTheWay() throws IOException {
    start();
    PlayerRef undo = withGear("Undo");
    run("/season init 2");
    run("/season end --now");
    assertThat(run("/season unarchive Undo 1"))
        .containsExactly("Undo has no archive for Season 1.");
    fake.player(undo).inventory =
        new com.petepeterepeat.seasons.core.port.InventorySnapshot(
            FakeItems.empty(36),
            FakeItems.empty(4),
            Optional.of(FakeItems.item("minecraft:apple", 2)),
            FakeItems.empty(27));
    assertThat(run("/season unarchive Undo 2").getFirst())
        .startsWith("Undo's inventory isn't empty.");

    List<String> reply = run("/season unarchive Undo 2 --force");

    assertThat(reply.getFirst()).startsWith("Restored Undo's Season 2 archive to them. 2 items");
    assertThat(fake.player(undo).inventory.storage()).isEqualTo(FakeItems.gear().storage());
    assertThat(fake.player(undo).advancements).isEqualTo(ADVANCEMENTS);
    assertThat(fake.player(undo).experience.level()).isEqualTo(5);
    assertThat(archive(undo, 2)).isEmpty();
    assertThat(vault(undo, 2)).isEmpty();
    assertThat(record(undo).archivedThrough()).isEqualTo(1);
    try (Stream<Path> files = Files.list(dir.resolve("world/seasons/data/archives/s2"))) {
      assertThat(files.map(f -> f.getFileName().toString()))
          .anyMatch(name -> name.contains(".displaced-"))
          .anyMatch(name -> name.contains(".unarchived-"));
    }

    fake.player(undo).inventory = FakeItems.gear();
    assertThat(run("/season archive Undo")).containsExactly("Archived Undo into Season 2.");
    assertThat(isEmpty(undo)).isTrue();
  }

  @Test
  void theFurthestBlockCountsOnlyOverworldBlocksWhileTheSeasonIsPlayed() {
    start();
    PlayerRef builder = join("Builder");
    run("/season init 2");
    assertThat(run("/season status")).contains("No block placed yet this season.");

    fake.handler.decide(
        new Intent.PlaceBlock(
            builder.id(), Position.of("minecraft:overworld", 5, 64, 0), "minecraft:stone"));
    fake.handler.decide(
        new Intent.PlaceBlock(
            builder.id(), Position.of("minecraft:the_nether", 900, 64, 0), "minecraft:stone"));
    fake.handler.decide(
        new Intent.PlaceBlock(
            builder.id(), Position.of("minecraft:overworld", 3, 64, 0), "minecraft:stone"));

    assertThat(run("/season status"))
        .contains("Furthest block from 0,0 this season: 5 blocks, by Builder at 5, 0");
  }
}
