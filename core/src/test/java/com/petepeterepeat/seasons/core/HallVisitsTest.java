// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.Movement;
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

/** Visiting the Hall of Fame (A26 to A29), sealing it, and cushions (A44, sitting). */
class HallVisitsTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Position HOME = new Position(OVERWORLD, 100.5, 64, 100.5, 90f, 10f);
  private static final Position ENTRY = new Position(OVERWORLD, 1.5, 64, 1.5, 0f, 0f);
  private static final Position INSIDE = Position.of(OVERWORLD, 5.5, 64, 5.5);
  private static final Position OUTSIDE = Position.of(OVERWORLD, 30.5, 64, 5.5);

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef alex;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void setUp() throws IOException {
    writeGallery();
    start();
    run("/season init 2");
    alex = join("Alex");
    fake.player(alex).position = HOME;
    fake.setBlock(101, 64, 100, FakePlatform.BED);
    fake.worldSpawn = Position.of(OVERWORLD, 50.5, 64, 50.5);
  }

  private void start() {
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
  }

  private void restart() {
    seasons.stop();
    FakePlatform before = fake;
    fake = new FakePlatform();
    fake.players.putAll(before.players);
    fake.blocks.putAll(before.blocks);
    fake.clock.advance(Duration.between(fake.clock.instant(), before.clock.instant()));
    start();
    fake.online.add(alex);
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  /** Runs a /hof command as a player, and waits out the rate limit afterwards. */
  private void hof(PlayerRef player, String command) {
    TestCommands.run(fake.commands.get(1), new TestSource(Optional.of(player.id())), command);
    fake.clock.advance(Duration.ofSeconds(6));
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    if (fake.sessions != null) {
      fake.sessions.joined(player, false);
    }
    return player;
  }

  private List<String> heard(PlayerRef player) {
    return fake.sent.stream()
        .filter(s -> s.player().equals(player.id()))
        .map(s -> Messages.plain(s.message()))
        .toList();
  }

  private String lastHeard(PlayerRef player) {
    return heard(player).getLast();
  }

  private Position at(PlayerRef player) {
    return fake.player(player).position;
  }

  private Optional<PlayerRecord.HallReturn> saved(PlayerRef player) {
    return seasons.players().orElseThrow().find(player.id()).orElseThrow().hallReturn();
  }

  private Decision decide(Intent intent) {
    return fake.handler.decide(intent);
  }

  private void visit() {
    hof(alex, "/hof");
    assertThat(at(alex)).isEqualTo(ENTRY);
    fake.tick();
  }

  @Test
  void aBedNearbyTakesThemToTheHallAndLeaveBringsThemBack() {
    fake.blocks.clear();
    hof(alex, "/hof");
    assertThat(lastHeard(alex)).isEqualTo("You need to be within 3 blocks of a bed.");

    fake.setBlock(102, 64, 100, FakePlatform.BED);
    TestCommands.run(fake.commands.get(1), new TestSource(Optional.of(alex.id())), "/hof");
    assertThat(decide(new Intent.TakeDamage(alex.id()))).isNotEqualTo(Decision.allow());
    fake.clock.advance(Duration.ofSeconds(6));
    fake.tick();
    assertThat(at(alex)).isEqualTo(ENTRY);
    assertThat(lastHeard(alex))
        .isEqualTo("Welcome to the Hall of Fame. /hof leave takes you back.");
    assertThat(saved(alex)).contains(new PlayerRecord.HallReturn(HOME, GameMode.SURVIVAL));
    assertThat(fake.player(alex).gameMode).isEqualTo(GameMode.ADVENTURE);

    hof(alex, "/hof leave");
    assertThat(at(alex)).isEqualTo(HOME);
    assertThat(lastHeard(alex)).isEqualTo("Back where you left.");
    assertThat(fake.player(alex).gameMode).isEqualTo(GameMode.SURVIVAL);
    assertThat(saved(alex)).isEmpty();
    hof(alex, "/hof leave");
    assertThat(lastHeard(alex)).isEqualTo("You aren't visiting the Hall of Fame.");
  }

  @Test
  void eachCheckRefusesWithItsReasonInOrder() {
    TestSource source = new TestSource(Optional.of(alex.id()));
    fake.blocks.clear();
    TestCommands.run(fake.commands.get(1), source, "/hof");
    TestCommands.run(fake.commands.get(1), source, "/hof");
    assertThat(heard(alex))
        .endsWith(
            "You need to be within 3 blocks of a bed.",
            "Wait a few seconds before trying /hof again.");
    fake.clock.advance(Duration.ofSeconds(6));
    fake.setBlock(101, 64, 100, FakePlatform.BED);

    fake.monsters.add(Position.of(OVERWORLD, 104, 64, 100));
    decide(new Intent.TakeDamage(alex.id()));
    fake.setBlock(100, 64, 100, FakePlatform.LAVA);
    hof(alex, "/hof");
    assertThat(lastHeard(alex)).isEqualTo("You're standing in lava.");
    fake.blocks.remove(List.of(100, 64, 100));

    fake.player(alex).movement = new Movement(false, true, false, false);
    hof(alex, "/hof");
    assertThat(lastHeard(alex)).isEqualTo("You can't use /hof while gliding.");
    fake.player(alex).movement = Movement.STILL;

    hof(alex, "/hof");
    assertThat(lastHeard(alex)).isEqualTo("Monsters are nearby.");
    fake.monsters.clear();

    decide(new Intent.TakeDamage(alex.id()));
    hof(alex, "/hof");
    assertThat(lastHeard(alex)).isEqualTo("You were hurt in the last 10 seconds.");

    fake.player(alex).position = Position.of(OVERWORLD, 100.5, 70, 100.5);
    fake.setBlock(101, 70, 100, FakePlatform.BED);
    hof(alex, "/hof");
    assertThat(lastHeard(alex)).isEqualTo("You can't use /hof while falling.");

    fake.player(alex).position = HOME;
    hof(alex, "/hof");
    assertThat(at(alex)).isEqualTo(ENTRY);
  }

  @Test
  void sneakClickingABedWithAnEmptyHandVisits() {
    Position bed = Position.of(OVERWORLD, 101, 64, 100);
    assertThat(decide(new Intent.UseBlock(alex.id(), bed, true, false, true, true)))
        .isEqualTo(Decision.allow());
    assertThat(at(alex)).isEqualTo(HOME);

    assertThat(decide(new Intent.UseBlock(alex.id(), bed, true, true, true, true)))
        .isNotEqualTo(Decision.allow());
    assertThat(at(alex)).isEqualTo(ENTRY);
  }

  @Test
  void theSavedSpotSurvivesARestart() {
    visit();
    restart();
    assertThat(saved(alex)).contains(new PlayerRecord.HallReturn(HOME, GameMode.SURVIVAL));
    hof(alex, "/hof leave");
    assertThat(at(alex)).isEqualTo(HOME);
  }

  @Test
  void anUnsafeSpotFallsBackToTheBedThenNearbyThenTheSpawn() {
    Position bed = Position.of(OVERWORLD, 200.5, 64, 200.5);
    fake.player(alex).respawn = bed;

    visit();
    fake.setBlock(100, 64, 100, FakePlatform.LAVA);
    hof(alex, "/hof leave");
    assertThat(at(alex)).isEqualTo(bed);
    assertThat(lastHeard(alex)).isEqualTo("Your spot wasn't safe (lava), so you're at your bed.");

    fake.player(alex).position = HOME;
    fake.blocks.remove(List.of(100, 64, 100));
    visit();
    fake.setBlock(100, 64, 100, FakePlatform.LAVA);
    fake.monsters.add(Position.of(OVERWORLD, 200, 64, 203));
    hof(alex, "/hof leave");
    assertThat(lastHeard(alex))
        .isEqualTo("Your spot wasn't safe (lava), so you're at the nearest safe spot.");
    assertThat(at(alex).y()).isEqualTo(64);
    assertThat(Math.abs(at(alex).x() - HOME.x()) + Math.abs(at(alex).z() - HOME.z()))
        .isEqualTo(1.0);

    fake.player(alex).position = Position.of(OVERWORLD, 100.5, 64, 98.5);
    fake.setBlock(100, 64, 99, FakePlatform.BED);
    visit();
    for (int x = 92; x <= 108; x++) {
      for (int z = 90; z <= 107; z++) {
        fake.setBlock(x, 63, z, FakePlatform.LAVA);
        fake.setBlock(x, 64, z, FakePlatform.LAVA);
      }
    }
    hof(alex, "/hof leave");
    assertThat(lastHeard(alex))
        .isEqualTo("Your spot wasn't safe (lava), so you're at the season spawn.");
    assertThat(at(alex)).isEqualTo(fake.worldSpawn);
  }

  @Test
  void aCreeperAtTheSavedSpotSendsThemToTheirBed() {
    Position bed = Position.of(OVERWORLD, 200.5, 64, 200.5);
    fake.player(alex).respawn = bed;
    visit();
    fake.monsters.add(HOME);
    hof(alex, "/hof leave");
    assertThat(at(alex)).isEqualTo(bed);
    assertThat(lastHeard(alex))
        .isEqualTo("Your spot wasn't safe (monsters nearby), so you're at your bed.");
  }

  @Test
  void leavingTheBoundsByAnyRouteReturnsThem() {
    visit();
    fake.player(alex).position = INSIDE;
    decide(new Intent.Move(alex.id(), INSIDE, OUTSIDE));
    assertThat(at(alex)).isEqualTo(HOME);
    assertThat(lastHeard(alex))
        .isEqualTo("You left the Hall of Fame, so you're back where you came from.");

    fake.player(alex).position = HOME;
    visit();
    fake.player(alex).position = INSIDE;
    assertThat(decide(new Intent.Teleport(alex.id(), INSIDE, OUTSIDE, Intent.TeleportKind.PEARL)))
        .isNotEqualTo(Decision.allow());
    assertThat(at(alex)).isEqualTo(HOME);

    fake.player(alex).position = HOME;
    visit();
    // Pushed out with no event: the sweep notices.
    fake.player(alex).position = OUTSIDE;
    fake.tick();
    assertThat(at(alex)).isEqualTo(HOME);

    fake.player(alex).position = HOME;
    visit();
    // An exit anchor.
    fake.player(alex).position = Position.of(OVERWORLD, 10.5, 64, 1.5);
    fake.tick();
    assertThat(at(alex)).isEqualTo(HOME);
    assertThat(lastHeard(alex)).isEqualTo("Back where you left.");
  }

  @Test
  void seasonsOwnTeleportsOutAreNotLeaving() {
    visit();
    decide(new Intent.Teleport(alex.id(), ENTRY, OUTSIDE, Intent.TeleportKind.SEASONS));
    fake.player(alex).position = OUTSIDE;
    fake.tick();
    assertThat(at(alex)).isEqualTo(OUTSIDE);
  }

  @Test
  void walkingInSavesWhereTheyCameFrom() {
    fake.player(alex).position = OUTSIDE;
    decide(new Intent.Move(alex.id(), OUTSIDE, INSIDE));
    fake.player(alex).position = INSIDE;
    assertThat(saved(alex)).map(PlayerRecord.HallReturn::at).contains(OUTSIDE);

    fake.player(alex).movement = new Movement(true, false, false, false);
    PlayerRef sam = join("Sam");
    fake.player(sam).position = OUTSIDE;
    fake.player(sam).movement = new Movement(true, false, false, false);
    assertThat(decide(new Intent.Move(sam.id(), OUTSIDE, INSIDE))).isNotEqualTo(Decision.allow());

    // Carried in on a mount, which no move event reports: the sweep puts them back out.
    fake.tick();
    fake.player(sam).position = INSIDE;
    fake.tick();
    assertThat(at(sam)).isEqualTo(OUTSIDE);
    assertThat(lastHeard(sam)).isEqualTo("You can't ride in the Hall of Fame.");
  }

  @Test
  void betweenSeasonsLeavingReturnsToTheHallEntry() {
    run("/season end --now");
    fake.player(alex).position = INSIDE;
    fake.tick();
    fake.player(alex).position = OUTSIDE;
    fake.tick();
    assertThat(at(alex)).isEqualTo(ENTRY);
    hof(alex, "/hof leave");
    assertThat(lastHeard(alex))
        .isEqualTo("Everyone stays in the Hall of Fame until the next season launches.");
  }

  @Test
  void insideNothingIsDroppedPickedUpPearledOrRiddenButCushionsAreSeats() {
    visit();
    assertThat(decide(new Intent.DropItem(alex.id()))).isNotEqualTo(Decision.allow());
    assertThat(decide(new Intent.PickupItem(alex.id()))).isNotEqualTo(Decision.allow());
    assertThat(decide(new Intent.UseItem(alex.id(), "minecraft:ender_pearl")))
        .isNotEqualTo(Decision.allow());
    assertThat(decide(new Intent.Consume(alex.id(), "minecraft:chorus_fruit")))
        .isNotEqualTo(Decision.allow());
    assertThat(decide(new Intent.Consume(alex.id(), "minecraft:bread")))
        .isEqualTo(Decision.allow());
    assertThat(decide(new Intent.Mount(alex.id(), false))).isNotEqualTo(Decision.allow());
    assertThat(decide(new Intent.Mount(alex.id(), true))).isEqualTo(Decision.allow());
    assertThat(decide(new Intent.Teleport(alex.id(), OUTSIDE, INSIDE, Intent.TeleportKind.PEARL)))
        .isNotEqualTo(Decision.allow());

    hof(alex, "/hof leave");
    assertThat(decide(new Intent.DropItem(alex.id()))).isEqualTo(Decision.allow());
    assertThat(decide(new Intent.Mount(alex.id(), false))).isEqualTo(Decision.allow());
  }

  @Test
  void hofPlayerAndSeasonLandAtTheirEntries() {
    PlayerRef sam = join("Sam");
    run("/season decorate open");
    hof(alex, "/hof Sam");
    assertThat(at(alex)).isNotEqualTo(HOME);
    hof(alex, "/hof 3");
    assertThat(lastHeard(alex)).isEqualTo("Season 3 has no gallery to visit.");
    hof(alex, "/hof 2");
    assertThat(at(alex)).isEqualTo(ENTRY);
    hof(alex, "/hof Nobody");
    assertThat(lastHeard(alex)).isEqualTo("Nobody called Nobody has played here.");
    assertThat(saved(alex)).map(PlayerRecord.HallReturn::at).contains(HOME);
    assertThat(sam).isNotNull();
  }

  private void writeGallery() throws IOException {
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    Files.writeString(
        galleries.resolve("season-2.yml"),
        """
        schema-version: 1
        id: season-2
        kind: season
        season: 2
        dimension: minecraft:overworld
        bounds: { min: [0, 60, 0], max: [20, 80, 20] }
        entry: { pos: [1, 64, 1] }
        exits:
          - [10, 64, 1]
        cells:
          c1:
            bounds: { min: [4, 60, 4], max: [8, 70, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
              - { type: entry, pos: [6, 64, 5] }
          c2:
            bounds: { min: [10, 60, 4], max: [14, 70, 8] }
            anchors:
              - { type: figure, pos: [12, 64, 7] }
        """);
  }
}
