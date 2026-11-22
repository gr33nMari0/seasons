// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveHash;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ImportedCell;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.Imports;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
        TestHooks.of(() -> {
          throw new Halted();
        });
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            MapPublisher.NONE,
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
  void whatAMenuHandsBackAfterTheClearGoesIntoTheRecordNotThePlayer() throws IOException {
    start();
    PlayerRef alex = withGear("Alex");
    NeutralItem diamonds = FakeItems.item("minecraft:diamond", 64);
    fake.player(alex).handedBackOnClose.add(diamonds);
    run("/season init 2");

    run("/season end --now");

    assertThat(isEmpty(alex)).isTrue();
    ArchiveRecord archived = archive(alex, 2).orElseThrow();
    assertThat(archived.inventory()).isEqualTo(FakeItems.gear());
    assertThat(archived.overflow()).containsExactly(Optional.of(diamonds));
    assertThat(record(alex).archivedThrough()).isEqualTo(2);
    assertThat(Files.readString(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .contains("\"action\":\"late-items\"");
  }

  @Test
  void infoGivesTheArchiveStatus() throws IOException {
    start();
    PlayerRef alex = withGear("Alex");
    PlayerRef bob = join("Bob");
    run("/season init 2");
    assertThat(info(alex)).noneMatch(line -> line.contains("rchive"));
    fake.leave(bob);

    run("/season end --now");
    fake.online.add(bob);

    assertThat(info(alex)).contains("Archived into Season 2.");
    assertThat(info(bob)).contains("Waiting to be archived into Season 2.");
  }

  private List<String> info(PlayerRef player) {
    TestSource source = new TestSource(Optional.of(player.id()));
    TestCommands.run(fake.commands.getFirst(), source, "/season info");
    return source.text();
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
    NeutralItem torch = FakeItems.item("minecraft:torch", 3);
    fake.player(casey).cursor.add(torch);
    run("/season init 2");
    hooks.arm(point, casey.id());

    run("/season end --now");
    restart();
    join("Casey");

    assertThat(record(casey).archivedThrough()).isEqualTo(2);
    assertThat(isEmpty(casey)).isTrue();
    assertThat(fake.player(casey).cursor).isEmpty();
    assertThat(archive(casey, 2).orElseThrow().inventory()).isEqualTo(FakeItems.gear());
    assertThat(archive(casey, 2).orElseThrow().overflow()).containsExactly(Optional.of(torch));
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
  void anEmptyInventoryBeforeTheClearWasJournalledIsNotTakenAsCleared() throws IOException {
    start();
    PlayerRef casey = withGear("Casey");
    PlayerRef admin = fake.join("Admin");
    run("/season init 2");
    hooks.arm(CrashPoint.VAULT_SEALED, casey.id());
    run("/season end --now");
    restart();
    // Emptied into a chest somewhere before the restart, as a player could after a failure.
    fake.player(casey).inventory = FakeItems.EMPTY;
    fake.online.add(admin);
    int before = fake.sent.size();

    join("Casey");

    assertThat(record(casey).archivedThrough()).isLessThan(2);
    assertThat(fake.sent.subList(before, fake.sent.size()))
        .map(sent -> TestSource.plain(sent.message()))
        .anyMatch(line ->
            line.startsWith(
                "Casey's inventory is empty, but Seasons never cleared it for their Season 2"
                    + " archive"));
    assertThat(run("/season status"))
        .anyMatch(line -> line.endsWith(", 1 waiting for recovery"))
        .anyMatch(line -> line.contains("never cleared it"));
  }

  @Test
  void aCrashAfterTheClearWasJournalledFinishesWithAnEmptyInventory() throws IOException {
    start();
    PlayerRef casey = withGear("Casey");
    run("/season init 2");
    hooks.arm(CrashPoint.RESET, casey.id());
    run("/season end --now");
    assertThat(isEmpty(casey)).isTrue();
    assertThat(Files.readString(dir.resolve("world/seasons/data/journal.log")))
        .contains("ARCHIVE_CLEARING")
        .doesNotContain("ARCHIVE_DONE");
    restart();

    join("Casey");

    assertThat(record(casey).archivedThrough()).isEqualTo(2);
    assertThat(archive(casey, 2).orElseThrow().inventory()).isEqualTo(FakeItems.gear());
    assertThat(vault(casey, 2).orElseThrow())
        .contains(Optional.of(FakeItems.item("minecraft:emerald", 5)));
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
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
  void leavingTheHallPutsBackSurvivalOnlyForPlayersSeasonsSwitched() throws IOException {
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    Files.writeString(
        galleries.resolve("hall.yml"),
        """
        schema-version: 1
        id: hall
        kind: season
        season: 2
        dimension: minecraft:overworld
        bounds: { min: [0, -64, 0], max: [10, -50, 10] }
        """);
    start();
    run("/season init 2");
    PlayerRef walker = join("Walker");
    PlayerRef jailed = join("Jailed");
    fake.player(jailed).gameMode = GameMode.ADVENTURE;
    Position inside = Position.of("minecraft:overworld", 5, -60, 5);
    Position outside = Position.of("minecraft:overworld", 50, -60, 50);

    fake.player(walker).position = inside;
    fake.player(jailed).position = inside;
    fake.tick();
    assertThat(fake.player(walker).gameMode).isEqualTo(GameMode.ADVENTURE);
    assertThat(fake.player(jailed).gameMode).isEqualTo(GameMode.ADVENTURE);

    // A restart in between doesn't strand the player Seasons switched.
    restart();
    join("Walker");
    join("Jailed");
    fake.player(walker).position = outside;
    fake.player(jailed).position = outside;
    fake.tick();
    assertThat(fake.player(walker).gameMode).isEqualTo(GameMode.SURVIVAL);
    assertThat(fake.player(jailed).gameMode).isEqualTo(GameMode.ADVENTURE);
  }

  /** One Season 1 chest, which a player may empty between the import's scan and its clear. */
  static final class OneChest implements Imports {
    Position at = Position.of("minecraft:overworld", 3, -60, 3);
    List<Optional<NeutralItem>> slots =
        new ArrayList<>(List.of(Optional.of(FakeItems.item("minecraft:diamond", 3))));
    int scans;
    boolean takenAfterFirstScan;

    @Override
    public CompletableFuture<Scan> scan(Position min, Position max) {
      Scan scan =
          new Scan(
              List.of(new Container(at, "minecraft:chest", slots)),
              List.of(),
              List.of(),
              List.of(),
              List.of());
      if (++scans == 1 && takenAfterFirstScan) {
        slots = new ArrayList<>(List.of(Optional.empty()));
      }
      return CompletableFuture.completedFuture(scan);
    }

    @Override
    public boolean emptyContainer(Position block) {
      slots = new ArrayList<>(List.of(Optional.empty()));
      return true;
    }

    @Override
    public boolean fillContainer(Position block, List<Optional<NeutralItem>> slots) {
      this.slots = new ArrayList<>(slots);
      return true;
    }

    @Override
    public boolean remove(UUID entity) {
      return true;
    }

    @Override
    public UUID spawnStand(
        Position at, Map<String, Object> properties, List<Optional<NeutralItem>> worn) {
      return UUID.randomUUID();
    }

    @Override
    public UUID spawnFrame(
        Position at, Map<String, Object> properties, Optional<NeutralItem> item) {
      return UUID.randomUUID();
    }

    @Override
    public void saveWorld(String dimension) {}
  }

  /** A Season 1 gallery with one cell, owned by Veteran, holding {@link OneChest}. */
  private OneChest seasonOneCell(boolean takenAfterScan) throws IOException {
    OneChest chest = new OneChest();
    chest.takenAfterFirstScan = takenAfterScan;
    fake.imports = chest;
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    String veteran =
        UUID.nameUUIDFromBytes("Veteran".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    Files.writeString(
        galleries.resolve("season-1.yml"),
        """
        schema-version: 1
        id: season-1
        kind: season
        season: 1
        dimension: minecraft:overworld
        bounds: { min: [0, -64, 0], max: [10, -50, 10] }
        cells:
          veteran:
            bounds: { min: [1, -64, 1], max: [9, -50, 9] }
            owners: { "1": %s }
        """
            .formatted(veteran));
    start();
    run("/season init 2");
    join("Veteran");
    return chest;
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  @Test
  void anImportTakesTheCellsItemsIntoOneRecord() throws IOException {
    OneChest chest = seasonOneCell(false);

    assertThat(hof("/hof import season-1"))
        .containsExactly("Imported 1 cell from season-1; 0 skipped.");

    assertThat(chest.slots).containsOnly(Optional.empty());
    assertThat(archive(fake.join("Veteran"), 1)).isPresent();
  }

  @Test
  void aChestEmptiedAfterTheScanStopsTheImportAndNoRecordHoldsItsItems() throws IOException {
    seasonOneCell(true);

    assertThat(hof("/hof import season-1").getFirst())
        .startsWith(
            "The season-1 import stopped at cell veteran: the chest at 3, -60, 3 was emptied");

    // Whoever emptied it has the diamonds; the Hall mustn't show them too.
    assertThat(archive(fake.join("Veteran"), 1)).isEmpty();
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void anImportThatWouldWriteABrokenGalleryChangesNothing() throws IOException {
    OneChest chest = seasonOneCell(false);
    // Say the scan placed the chest's anchor outside the cell, as a double chest across its edge
    // once could.
    chest.at = Position.of("minecraft:overworld", 20, -60, 3);

    assertThat(hof("/hof import season-1").getFirst())
        .startsWith(
            "The season-1 import stopped at cell veteran: the gallery would break its rules");

    assertThat(chest.slots).doesNotContain(Optional.empty());
    assertThat(archive(fake.join("Veteran"), 1)).isEmpty();
  }

  @Test
  void anImportedRecordIsNeverUnarchived() throws IOException {
    start();
    PlayerRef veteran = join("Veteran");
    run("/season init 2");
    Position at = Position.of("minecraft:overworld", 5, -60, 4);
    ImportedCell imported =
        new ImportedCell(
            "season-1",
            "veteran",
            Optional.empty(),
            List.of(
                new ImportedCell.Container(
                    at,
                    "minecraft:chest",
                    List.of(Optional.of(FakeItems.item("minecraft:diamond", 3))))),
            List.of(),
            List.of());
    ArchiveRecord record =
        new ArchiveRecord(
            veteran.id(),
            "Veteran",
            1,
            Instant.EPOCH,
            FakeItems.EMPTY,
            List.of(),
            EnderChestPolicy.KEEP,
            new AdvancementSnapshot(Map.of(), 0, 0),
            Experience.NONE,
            Map.of(),
            Optional.empty(),
            at,
            Optional.empty(),
            ArchiveHash.ofImported(imported),
            OptionalInt.empty(),
            Optional.of(imported));
    new ArchiveFiles(dir.resolve("world/seasons/data/archives")).write(record, 1);
    int level = fake.player(veteran).experience.level();

    assertThat(run("/season unarchive Veteran 1").getFirst())
        .startsWith("Veteran's Season 1 archive was imported from a hand-built cell");
    assertThat(archive(veteran, 1)).isPresent();
    assertThat(fake.player(veteran).experience.level()).isEqualTo(level);
  }

  @Test
  void aFailedWriteLeavesEverythingOnThePlayerAndLaunchWaitsForThem() throws IOException {
    start();
    PlayerRef casey = withGear("Casey");
    NeutralItem torch = FakeItems.item("minecraft:torch", 3);
    fake.player(casey).cursor.add(torch);
    run("/season init 2");
    // A file where the season's archive folder should be, so the archive can't be written.
    Path folder = dir.resolve("world/seasons/data/archives/s2");
    Files.createDirectories(folder.getParent());
    Files.writeString(folder, "in the way");

    run("/season end --now");

    assertThat(fake.player(casey).inventory).isEqualTo(FakeItems.gear());
    assertThat(fake.player(casey).cursor).containsExactly(torch);
    assertThat(record(casey).archivedThrough()).isLessThan(2);
    setSpawn(500, 300);
    assertThat(run("/season launch").getFirst())
        .startsWith("Someone online isn't archived into Season 2 yet");
    assertThat(fake.player(casey).position.x()).isEqualTo(10);

    Files.delete(folder);
    assertThat(run("/season archive Casey")).containsExactly("Archived Casey into Season 2.");
    assertThat(fake.player(casey).cursor).isEmpty();
    assertThat(archive(casey, 2).orElseThrow().overflow()).containsExactly(Optional.of(torch));
    assertThat(run("/season launch")).isEmpty();
    assertThat(fake.player(casey).position.x()).isEqualTo(500.5);
  }

  @Test
  void aJoiningPlayerIsHeldUntilCatchUpHasRun() {
    start();
    run("/season init 2");
    PlayerRef ari = fake.join("Ari");

    fake.sessions.arriving(ari.id());
    assertThat(fake.handler.decide(new Intent.DropItem(ari.id()))).isNotEqualTo(Decision.allow());

    fake.sessions.joined(ari, false);
    assertThat(fake.handler.decide(new Intent.DropItem(ari.id()))).isEqualTo(Decision.allow());
  }

  @Test
  void unarchivingDuringALaterSeasonIsNeverArchivedAgainAtLogin() throws IOException {
    start();
    PlayerRef alex = withGear("Alex");
    run("/season init 2");
    run("/season end --now");
    setSpawn(500, 300);
    run("/season launch");
    fake.player(alex).inventory = FakeItems.stone(1);

    assertThat(run("/season unarchive Alex 2 --force").getFirst())
        .startsWith("Restored Alex's Season 2 archive to them.");
    fake.leave(alex);
    join("Alex");

    assertThat(fake.player(alex).inventory.storage()).isEqualTo(FakeItems.gear().storage());
    assertThat(archive(alex, 2)).isEmpty();
  }

  @Test
  void unarchivingAnOlderSeasonLeavesALaterArchiveAlone() throws IOException {
    start();
    PlayerRef alex = withGear("Alex");
    run("/season init 2");
    run("/season end --now");
    setSpawn(500, 300);
    run("/season launch");
    fake.player(alex).inventory = FakeItems.stone(7);
    run("/season end --now");
    ArchiveRecord third = archive(alex, 3).orElseThrow();
    assertThat(isEmpty(alex)).isTrue();

    assertThat(run("/season unarchive Alex 2").getFirst())
        .startsWith("Restored Alex's Season 2 archive to them.");
    assertThat(record(alex).archivedThrough()).isEqualTo(3);
    fake.leave(alex);
    join("Alex");

    assertThat(fake.player(alex).inventory.storage()).isEqualTo(FakeItems.gear().storage());
    assertThat(archive(alex, 3).orElseThrow().contentHash()).isEqualTo(third.contentHash());
  }

  @Test
  void anArchiveFileIsMovedAsideRatherThanOverwritten() throws IOException {
    start();
    PlayerRef alex = withGear("Alex");
    run("/season init 2");
    run("/season end --now");
    ArchiveRecord first = archive(alex, 2).orElseThrow();
    ArchiveFiles files = new ArchiveFiles(dir.resolve("world/seasons/data/archives"));

    files.write(first, FakeItems.DATA_VERSION);

    try (Stream<Path> listed = Files.list(dir.resolve("world/seasons/data/archives/s2"))) {
      assertThat(listed.map(f -> f.getFileName().toString()))
          .anyMatch(name -> name.contains(".superseded-"));
    }
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
