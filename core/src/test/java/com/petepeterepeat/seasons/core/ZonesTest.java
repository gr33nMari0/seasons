// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ZoneGuard;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.SeasonStore;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zones in a launched season (A30, A33 to A35, A45): keeping players out of blocked zones by every
 * route, the sweep, the edge, no-build and announce modes, unlock conditions and Homecoming.
 */
class ZonesTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Position SPAWN = Position.of(OVERWORLD, 3000.5, 64, 0.5);
  private static final Position OUTSIDE = Position.of(OVERWORLD, 533.5, 64, 500.5);
  private static final Position INSIDE = Position.of(OVERWORLD, 510.5, 64, 500.5);
  private static final Position IN_FARM = Position.of(OVERWORLD, 805.5, 64, 805.5);
  private static final Position IN_MARKET = Position.of(OVERWORLD, 305.5, 64, 305.5);

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef pat;
  private final List<String> discord = new ArrayList<>();

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {
          discord.add(content);
        }

        @Override
        public void close() {}
      };

  @BeforeEach
  void setUp() throws IOException {
    writeSeasonFile("");
    Path world = dir.resolve("world/seasons");
    Files.createDirectories(world.resolve("data"));
    SeasonState launched =
        SeasonState.start(3, Stage.FRONTIER, Optional.of(fake.clock.instant())).withLaunchApplied();
    new SeasonStore(world.resolve("data/season.json"), new SerialWriter(Runnable::run))
        .save(launched)
        .join();
    Files.createDirectories(dir.resolve("plugins/Seasons"));
    Files.writeString(
        dir.resolve("plugins/Seasons/secrets.yml"),
        "schema-version: 1\ndiscordWebhook: \"https://discord.example/hook\"\n");
    start();
    pat = fake.join("Pat");
    fake.player(pat).position = OUTSIDE;
  }

  /** Season 3's file: the base, a no-build farm and an announce market, and anything extra. */
  private void writeSeasonFile(String extra) throws IOException {
    Path file = dir.resolve("world/seasons/seasons/3.yml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema-version: 1
        season: 3
        spawn: { dimension: minecraft:overworld, pos: [3000, 64, 0] }
        zones:
          base: { dimension: minecraft:overworld, shape: { cylinder: { center: [500, 500], radius: 30 } } }
          farm: { dimension: minecraft:overworld, shape: { box: { min: [800, 0, 800], max: [810, 100, 810] } }, mode: no-build, afterUnlock: no-build }
          market: { dimension: minecraft:overworld, shape: { box: { min: [300, 0, 300], max: [320, 100, 320] } }, mode: announce }
        """
            + extra,
        UTF_8);
  }

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
            Optional.empty());
    seasons.start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private TestSource run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source;
  }

  private Decision decide(Intent intent) {
    return fake.handler.decide(intent);
  }

  private ZoneGuard guard() {
    return java.util.Objects.requireNonNull(fake.zoneGuard);
  }

  private List<JsonObject> audited(String action) throws IOException {
    Path file = dir.resolve("world/seasons/audit/2026-11.jsonl");
    if (!Files.exists(file)) {
      return List.of();
    }
    return Files.readAllLines(file).stream()
        .map(line -> JsonParser.parseString(line).getAsJsonObject())
        .filter(entry -> entry.get("action").getAsString().equals(action))
        .toList();
  }

  private static String field(JsonObject entry, String name) {
    return entry.get(name).getAsString();
  }

  /** Ten runs of the sweep: every player checked once. */
  private void sweepAll() {
    for (int i = 0; i < 10; i++) {
      fake.tick();
    }
  }

  private List<String> bar(PlayerRef player) {
    return fake.player(player).actionBars.stream().map(Messages::plain).toList();
  }

  private List<String> broadcasts() {
    return fake.broadcasts.stream().map(Messages::plain).toList();
  }

  @Test
  void everyRouteIntoABlockedZoneIsRefusedWithAMessage() {
    fake.player(pat).movement =
        new com.petepeterepeat.seasons.core.port.Movement(true, true, false, false);
    assertThat(decide(new Intent.Move(pat.id(), OUTSIDE, INSIDE)))
        .isInstanceOf(Decision.Deny.class);
    assertThat(fake.player(pat).stopped).isEqualTo(1);
    assertThat(bar(pat)).containsExactly("The base is locked until Homecoming.");
    for (Intent.TeleportKind kind :
        List.of(
            Intent.TeleportKind.PEARL,
            Intent.TeleportKind.CHORUS,
            Intent.TeleportKind.PORTAL,
            Intent.TeleportKind.OTHER)) {
      assertThat(decide(new Intent.Teleport(pat.id(), OUTSIDE, INSIDE, kind)))
          .as(kind.name())
          .isInstanceOf(Decision.Deny.class);
    }
    // Seasons' own moves, and moves that stay outside, go ahead.
    assertThat(decide(new Intent.Teleport(pat.id(), OUTSIDE, INSIDE, Intent.TeleportKind.SEASONS)))
        .isEqualTo(Decision.allow());
    assertThat(decide(new Intent.Move(pat.id(), OUTSIDE, SPAWN))).isEqualTo(Decision.allow());
    // A new portal for the other end of a trip, inside.
    assertThat(decide(new Intent.CreatePortal(pat.id(), INSIDE)))
        .isInstanceOf(Decision.Deny.class)
        .extracting(d -> Messages.plain(((Decision.Deny) d).message().orElseThrow()))
        .isEqualTo("That portal leads into the base, which is locked until Homecoming.");
  }

  @Test
  void bypassHoldersGoWhereTheyLike() {
    fake.bypassing.add(pat.id());
    assertThat(decide(new Intent.Move(pat.id(), OUTSIDE, INSIDE))).isEqualTo(Decision.allow());
    assertThat(decide(new Intent.BreakBlock(pat.id(), INSIDE))).isEqualTo(Decision.allow());
    fake.player(pat).position = INSIDE;
    sweepAll();
    assertThat(fake.player(pat).position).isEqualTo(INSIDE);
  }

  @Test
  void theSweepChecksATenthOfThePlayersEachRun() {
    List<PlayerRef> inside = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      PlayerRef player = fake.join("P" + i);
      fake.player(player).position = INSIDE;
      inside.add(player);
    }
    fake.player(pat).position = OUTSIDE;
    fake.tick();
    long moved = inside.stream().filter(p -> fake.player(p).position.equals(SPAWN)).count();
    assertThat(moved).isLessThanOrEqualTo(2);
    sweepAll();
    assertThat(inside).allMatch(p -> fake.player(p).position.equals(SPAWN));
  }

  @Test
  void theSweepPutsPlayersBackWhereTheyLastStoodOutside() {
    sweepAll();
    // Ridden in, which no move event reports.
    fake.player(pat).position = INSIDE;
    sweepAll();
    assertThat(fake.player(pat).position).isEqualTo(OUTSIDE);
    assertThat(bar(pat)).containsExactly("The base is locked until Homecoming.");
  }

  @Test
  void aPlayerFoundInsideWithNowhereOutsideToGoIsSentToTheSpawn() {
    PlayerRef quinn = fake.join("Quinn");
    fake.player(quinn).position = INSIDE;
    sweepAll();
    assertThat(fake.player(quinn).position).isEqualTo(SPAWN);
    assertThat(
            fake.sent.stream()
                .filter(s -> s.player().equals(quinn.id()))
                .map(s -> Messages.plain(s.message())))
        .containsExactly("You were in the base, which is locked, so you're back at the spawn.");
  }

  @Test
  void nothingInsideABlockedZoneIsTouchedFromOutsideItsEdge() {
    List<Intent> inside =
        List.of(
            new Intent.BreakBlock(pat.id(), INSIDE),
            new Intent.PlaceBlock(pat.id(), INSIDE, "minecraft:stone"),
            new Intent.OpenContainer(pat.id(), INSIDE),
            new Intent.Bucket(pat.id(), INSIDE, "minecraft:water_bucket"),
            new Intent.UseBlock(pat.id(), INSIDE, true),
            new Intent.ChangeBlock(pat.id(), INSIDE, "minecraft:flint_and_steel"),
            new Intent.UseEntity(pat.id(), INSIDE, "minecraft:villager"),
            new Intent.HurtEntity(pat.id(), INSIDE, "minecraft:cow"),
            new Intent.PlaceEntity(pat.id(), INSIDE, "minecraft:item_frame"));
    for (Intent intent : inside) {
      assertThat(decide(intent))
          .as(intent.toString())
          .isInstanceOf(Decision.Deny.class)
          .extracting(d -> Messages.plain(((Decision.Deny) d).message().orElseThrow()))
          .isEqualTo("That's in the base, which is locked until Homecoming.");
    }
    assertThat(decide(new Intent.BreakBlock(pat.id(), OUTSIDE))).isEqualTo(Decision.allow());
  }

  @Test
  void nothingCrossesABlockedOrNoBuildZonesEdgeByItself() {
    Position edgeOut = Position.of(OVERWORLD, 531, 64, 500);
    Position edgeIn = Position.of(OVERWORLD, 529, 64, 500);
    assertThat(guard().protects(edgeIn)).isTrue();
    assertThat(guard().protects(edgeOut)).isFalse();
    assertThat(guard().allows(List.of(edgeOut, edgeIn))).isFalse();
    assertThat(guard().allows(List.of(edgeIn, Position.of(OVERWORLD, 520, 64, 500)))).isTrue();
    assertThat(guard().allows(List.of(edgeOut, Position.of(OVERWORLD, 540, 64, 500)))).isTrue();
    assertThat(guard().protects(IN_FARM)).isTrue();
    assertThat(guard().allows(List.of(IN_FARM, Position.of(OVERWORLD, 799, 64, 805)))).isFalse();
    // Announce zones guard nothing.
    assertThat(guard().protects(IN_MARKET)).isFalse();
    assertThat(guard().blocks(INSIDE)).isTrue();
    assertThat(guard().blocks(IN_FARM)).isFalse();
  }

  @Test
  void noBuildRefusesBuildingButNotUsing() {
    for (Intent building :
        List.of(
            new Intent.BreakBlock(pat.id(), IN_FARM),
            new Intent.PlaceBlock(pat.id(), IN_FARM, "minecraft:stone"),
            new Intent.Bucket(pat.id(), IN_FARM, "minecraft:water_bucket"),
            new Intent.ChangeBlock(pat.id(), IN_FARM, "minecraft:tnt"),
            new Intent.PlaceEntity(pat.id(), IN_FARM, "minecraft:cushion"),
            new Intent.HurtEntity(pat.id(), IN_FARM, "minecraft:armor_stand"))) {
      assertThat(decide(building))
          .as(building.toString())
          .isInstanceOf(Decision.Deny.class)
          .extracting(d -> Messages.plain(((Decision.Deny) d).message().orElseThrow()))
          .isEqualTo("You can't build in the farm.");
    }
    for (Intent using :
        List.of(
            new Intent.Move(pat.id(), OUTSIDE, IN_FARM),
            new Intent.OpenContainer(pat.id(), IN_FARM),
            new Intent.UseBlock(pat.id(), IN_FARM, true),
            new Intent.UseEntity(pat.id(), IN_FARM, "minecraft:villager"),
            new Intent.HurtEntity(pat.id(), IN_FARM, "minecraft:zombie"))) {
      assertThat(decide(using)).as(using.toString()).isEqualTo(Decision.allow());
    }
  }

  @Test
  void enteringAnAnnounceZoneIsBroadcastOncePerDay() {
    decide(new Intent.Move(pat.id(), OUTSIDE, IN_MARKET));
    fake.player(pat).position = IN_MARKET;
    sweepAll();
    decide(new Intent.Move(pat.id(), IN_MARKET, OUTSIDE));
    fake.player(pat).position = OUTSIDE;
    sweepAll();
    decide(new Intent.Move(pat.id(), OUTSIDE, IN_MARKET));
    assertThat(broadcasts())
        .containsOnlyOnce("Pat entered the market (opens at Homecoming)")
        .hasSize(1);
    assertThat(discord).isEmpty();
    fake.clock.advance(Duration.ofDays(1));
    fake.player(pat).position = OUTSIDE;
    sweepAll();
    decide(new Intent.Move(pat.id(), OUTSIDE, IN_MARKET));
    assertThat(broadcasts()).hasSize(2);
  }

  @Test
  void homecomingSwitchesEveryZoneInOneTick() {
    assertThat(guard().blocks(INSIDE)).isTrue();
    assertThat(run("/season unlock homecoming").text())
        .containsExactly("Homecoming! Every zone is now in its after-unlock mode.");
    // No tick has passed: the base is off and the farm stays no-build at once.
    assertThat(guard().blocks(INSIDE)).isFalse();
    assertThat(guard().protects(INSIDE)).isFalse();
    assertThat(guard().protects(IN_FARM)).isTrue();
    assertThat(guard().protects(IN_MARKET)).isFalse();
    assertThat(decide(new Intent.Move(pat.id(), OUTSIDE, INSIDE))).isEqualTo(Decision.allow());
    assertThat(fake.player(pat).titles).extracting(Messages::plain).contains("Homecoming");
    assertThat(fake.fireworks).containsExactly(SPAWN);
    assertThat(broadcasts()).anyMatch(line -> line.startsWith("Homecoming! The old world is open"));
    assertThat(discord).anyMatch(line -> line.startsWith("Homecoming! The old world is open"));
    assertThat(run("/season unlock homecoming").text())
        .containsExactly("Homecoming only happens once, in the Frontier stage.");
  }

  @Test
  void aRestartAfterHomecomingKeepsTheZonesOpenAndDoesNotCelebrateAgain() {
    run("/season unlock homecoming");
    run("/season lock base");
    seasons.stop();
    FakePlatform before = fake;
    fake = new FakePlatform();
    fake.players.putAll(before.players);
    start();
    fake.online.add(pat);
    sweepAll();
    assertThat(fake.broadcasts).isEmpty();
    assertThat(fake.fireworks).isEmpty();
    assertThat(guard().blocks(INSIDE)).isTrue();
    assertThat(guard().protects(IN_FARM)).isTrue();
  }

  @Test
  void oneZoneUnlocksAndLocksAgainOnItsOwn() {
    assertThat(run("/season unlock base").text())
        .containsExactly("Zone base is unlocked: it is off now.");
    assertThat(broadcasts()).containsExactly("The base is open!");
    assertThat(guard().blocks(INSIDE)).isFalse();
    assertThat(run("/season unlock base").text()).containsExactly("Zone base is already unlocked.");
    fake.player(pat).position = INSIDE;
    sweepAll();
    assertThat(run("/season lock base").text())
        .containsExactly("Zone base is locked again: it is block now.");
    sweepAll();
    // Where the sweep last saw them is inside, so they go to the spawn.
    assertThat(fake.player(pat).position).isEqualTo(SPAWN);
    assertThat(run("/season unlock nowhere").text())
        .containsExactly("There's no zone called nowhere.");
  }

  @Test
  void conditionsUnlockZonesAndBringHomecoming() throws IOException {
    writeSeasonFile(
        """
          tower: { dimension: minecraft:overworld, shape: { box: { min: [0, 0, 0], max: [5, 100, 5] } }, lockedUntil: { after: 2d } }
        homecoming:
          unlock: { any: [ { dragonKilled: true }, { playersReachedEnd: 2 } ] }
        """);
    run("/season reload");
    Position inTower = Position.of(OVERWORLD, 2.5, 64, 2.5);
    fake.runFor(Duration.ofSeconds(5));
    assertThat(guard().blocks(inTower)).isTrue();
    fake.clock.advance(Duration.ofDays(2));
    fake.runFor(Duration.ofSeconds(1));
    assertThat(guard().blocks(inTower)).isFalse();
    assertThat(broadcasts()).contains("The tower is open!");
    assertThat(guard().blocks(INSIDE)).isTrue();

    // One player in the End isn't enough; the dragon's death is.
    java.util.Objects.requireNonNull(fake.milestones)
        .enteredDimension(pat.id(), "minecraft:the_end");
    fake.runFor(Duration.ofSeconds(1));
    assertThat(guard().blocks(INSIDE)).isTrue();
    fake.milestones.dragonKilled();
    fake.runFor(Duration.ofSeconds(1));
    assertThat(guard().blocks(INSIDE)).isFalse();
    assertThat(broadcasts()).anyMatch(line -> line.startsWith("Homecoming!"));
  }

  @Test
  void zoneCommandsEditTheLiveSeasonsFileAndRefuseAZoneAroundTheSpawn() throws IOException {
    TestSource admin = new TestSource(Optional.of(pat.id()));
    fake.player(pat).position = Position.of(OVERWORLD, 200.5, 64, 200.5);
    TestCommands.run(
        fake.commands.getFirst(), admin, "/season zone add tower cylinder 12 --max-y 80");
    assertThat(admin.text())
        .containsExactly(
            "Added zone tower: a cylinder of radius 12 around 200, 200 up to y 80 in"
                + " minecraft:overworld, block until Homecoming and off after.");
    assertThat(Files.readString(dir.resolve("world/seasons/seasons/3.yml"))).contains("tower:");
    assertThat(guard().blocks(Position.of(OVERWORLD, 205, 64, 200))).isTrue();
    assertThat(guard().blocks(Position.of(OVERWORLD, 205, 81, 200))).isFalse();

    fake.player(pat).position = SPAWN;
    admin.replies.clear();
    TestCommands.run(fake.commands.getFirst(), admin, "/season zone add pen cylinder 5");
    assertThat(admin.text())
        .containsExactly(
            "That zone would hold Season 3's spawn at 3000, 64, 0; the spawn must stay outside"
                + " every zone.");
    assertThat(run("/season zone add mine box").text())
        .singleElement()
        .asString()
        .startsWith("Give both corners");
    assertThat(run("/season zone add tower box 0 0 0 1 1 1").text())
        .containsExactly("There's already a zone called tower.");
    assertThat(run("/season zone remove tower").text()).containsExactly("Removed zone tower.");
    assertThat(guard().blocks(Position.of(OVERWORLD, 205, 64, 200))).isFalse();
  }

  @Test
  void aZoneEditThatDoesntTakeIsPutBackAndNotAudited() throws IOException {
    Path file = dir.resolve("world/seasons/seasons/3.yml");
    String before = Files.readString(file, UTF_8);
    // A hand edit gone wrong in another file, not reloaded yet: the load after the edit fails.
    Path config = dir.resolve("plugins/Seasons/config.yml");
    String good = Files.readString(config, UTF_8);
    Files.writeString(config, "schedule: [nonsense\n", UTF_8);

    TestSource admin = new TestSource(Optional.of(pat.id()));
    fake.player(pat).position = Position.of(OVERWORLD, 200.5, 64, 200.5);
    TestCommands.run(fake.commands.getFirst(), admin, "/season zone add tower cylinder 12");
    assertThat(admin.text())
        .singleElement()
        .asString()
        .startsWith("The zone wasn't saved: config.yml, line ");
    assertThat(run("/season zone remove base").text())
        .singleElement()
        .asString()
        .startsWith("The zone wasn't saved: config.yml, line ");
    assertThat(Files.readString(file, UTF_8)).as("Season 3's file is as it was").isEqualTo(before);
    assertThat(audited("config")).isEmpty();

    // Fixing the other file brings in neither edit.
    Files.writeString(config, good, UTF_8);
    assertThat(run("/season reload").text()).first().asString().startsWith("Reloaded ");
    assertThat(guard().blocks(Position.of(OVERWORLD, 205, 64, 200))).isFalse();
    assertThat(guard().blocks(INSIDE)).isTrue();
  }

  @Test
  void homecomingOpensZonesLockedAgainOnTheirOwnAndLetsThemBeLockedAfter() {
    run("/season unlock base");
    run("/season lock base");
    assertThat(guard().blocks(INSIDE)).isTrue();
    run("/season unlock homecoming");
    assertThat(guard().blocks(INSIDE)).as("Homecoming opens every zone").isFalse();
    assertThat(run("/season lock base").text())
        .containsExactly("Zone base is locked again: it is block now.");
    assertThat(guard().blocks(INSIDE)).isTrue();
  }

  @Test
  void anItemLyingInABlockedZoneIsNotPickedUpFromOutsideItsEdge() throws IOException {
    assertThat(decide(new Intent.PickupItem(pat.id(), INSIDE)))
        .isEqualTo(new Decision.Deny(Optional.empty()));
    assertThat(decide(new Intent.PickupItem(pat.id(), OUTSIDE))).isEqualTo(Decision.allow());
    assertThat(decide(new Intent.PickupItem(pat.id(), IN_FARM))).isEqualTo(Decision.allow());
    assertThat(audited("zone-blocked"))
        .extracting(e -> field(e, "details"))
        .contains("pick up at 510, 64, 500");
    run("/season unlock homecoming");
    assertThat(decide(new Intent.PickupItem(pat.id(), INSIDE))).isEqualTo(Decision.allow());
  }

  @Test
  void hittingOrReelingInAnythingInsideFromTheEdgeIsRefused() {
    for (Intent intent :
        List.of(
            new Intent.HurtEntity(pat.id(), INSIDE, "minecraft:item_frame"),
            new Intent.HurtEntity(pat.id(), INSIDE, "minecraft:armor_stand"),
            new Intent.HurtEntity(pat.id(), INSIDE, "minecraft:chest_minecart"),
            new Intent.UseEntity(pat.id(), INSIDE, "minecraft:item"))) {
      assertThat(decide(intent)).as(intent.toString()).isInstanceOf(Decision.Deny.class);
    }
    assertThat(decide(new Intent.HurtEntity(pat.id(), IN_FARM, "minecraft:item_frame")))
        .isInstanceOf(Decision.Deny.class);
  }

  @Test
  void aPlayerArrivingInABlockedZoneThroughAPortalGoesBackAtOnce() {
    sweepAll();
    Position nether = Position.of("minecraft:the_nether", 64.5, 70, 62.5);
    fake.player(pat).position = nether;
    sweepAll();
    // The game chose an old portal inside the base as the trip's exit.
    fake.player(pat).position = INSIDE;
    java.util.Objects.requireNonNull(fake.milestones).enteredDimension(pat.id(), OVERWORLD);
    assertThat(fake.player(pat).position).isEqualTo(nether);
    assertThat(bar(pat)).contains("The base is locked until Homecoming.");
  }

  @Test
  void refusedEntriesUnlocksHomecomingAndZoneEditsAreAudited() throws IOException {
    decide(new Intent.Move(pat.id(), OUTSIDE, INSIDE));
    decide(new Intent.Teleport(pat.id(), OUTSIDE, INSIDE, Intent.TeleportKind.PEARL));
    assertThat(audited("zone-blocked"))
        .extracting(e -> field(e, "target") + ": " + field(e, "details"))
        .contains("base: walk in at 510, 64, 500", "base: teleport in by pearl at 510, 64, 500");

    run("/season zone mode farm block");
    run("/season unlock base");
    run("/season lock base");
    run("/season unlock homecoming");
    assertThat(audited("config"))
        .extracting(e -> field(e, "actor") + " " + field(e, "target") + " " + field(e, "details"))
        .containsExactly("The console seasons/3.yml zones.farm.mode no-build -> block");
    assertThat(audited("zone-unlock")).extracting(e -> field(e, "target")).containsExactly("base");
    assertThat(audited("zone-lock")).extracting(e -> field(e, "target")).containsExactly("base");
    assertThat(audited("homecoming"))
        .extracting(e -> field(e, "actor") + " " + field(e, "target"))
        .containsExactly("The console season 3");
  }
}
