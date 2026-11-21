// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.hall.Bounds;
import com.petepeterepeat.seasons.core.hall.HallFiles;
import com.petepeterepeat.seasons.core.hall.HallLayout;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.GameMode;
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
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * The hub, the Hall spawn, leaving freely, links, builders and the Hall check (questions 243 to
 * 259). The gallery spans x 0 to 20; the hub, x -8 to -1, touches its west side.
 */
class HubTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Position HOME = new Position(OVERWORLD, 100.5, 64, 100.5, 90f, 10f);
  private static final Position SPAWN = new Position(OVERWORLD, -1.5, 64, 1.5, 0f, 0f);
  private static final Position IN_HUB = Position.of(OVERWORLD, -4.5, 64, 10.5);
  private static final Position IN_GALLERY = Position.of(OVERWORLD, 2.5, 64, 2.5);
  private static final Position OUTSIDE = Position.of(OVERWORLD, 30.5, 64, 5.5);

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
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
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    Files.writeString(
        galleries.resolve("season-2.yml"),
        """
        schema-version: 1
        id: season-2
        season: 2
        bounds: { min: [0, 60, 0], max: [20, 80, 20] }
        entry: { pos: [1, 64, 1] }
        cells:
          c1:
            bounds: { min: [4, 64, 4], max: [8, 69, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
          c2:
            bounds: { min: [10, 64, 4], max: [14, 69, 8] }
            anchors:
              - { type: figure, pos: [12, 64, 7] }
          c3:
            bounds: { min: [16, 64, 4], max: [20, 69, 8] }
            anchors:
              - { type: figure, pos: [18, 64, 7] }
        """);
    Files.writeString(
        dir.resolve("world/seasons/hall.yml"),
        """
        schema-version: 1
        hub: { min: [-8, 60, 0], max: [-1, 80, 20] }
        spawn: { pos: [-2, 64, 1] }
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
    alex = join("Alex");
    fake.player(alex).position = HOME;
    fake.setBlock(101, 64, 100, FakePlatform.BED);
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  private void as(PlayerRef player, String command) {
    TestCommands.run(fake.commands.get(1), new TestSource(Optional.of(player.id())), command);
    fake.clock.advance(Duration.ofSeconds(6));
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    fake.sessions.joined(player, false);
    return player;
  }

  private String lastHeard(PlayerRef player) {
    return fake.sent.stream()
        .filter(s -> s.player().equals(player.id()))
        .map(s -> Messages.plain(s.message()))
        .toList()
        .getLast();
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

  private void config(String yaml) throws IOException {
    Path config = dir.resolve("plugins/Seasons/config.yml");
    Files.createDirectories(config.getParent());
    Files.writeString(config, "schema-version: 1\n" + yaml);
    run("/season reload");
  }

  private void visit() {
    as(alex, "/hof");
    assertThat(at(alex)).isEqualTo(SPAWN);
    fake.tick();
  }

  /** Walks a player from where they stand to a spot, as a move event would. */
  private void walk(PlayerRef player, Position to) {
    Position from = at(player);
    // There, as the game has them, while the move is decided; back if it is refused.
    fake.player(player).position = to;
    if (!decide(new Intent.Move(player.id(), from, to)).equals(Decision.allow())) {
      fake.player(player).position = from;
    }
    fake.tick();
    fake.tick();
  }

  @Test
  void hallYmlReadsBackAsWritten() throws Exception {
    HallLayout hall =
        HallLayout.NONE
            .withHub(Bounds.between(OVERWORLD, -12, 250, -12, 12, 290, 12))
            .withSpawn(new Position(OVERWORLD, 0, 256, 0, 180f, 0f))
            .withExit(Position.of(OVERWORLD, 0, 256, 10))
            .withLink(
                new HallLayout.Link(
                    "s3",
                    Position.of(OVERWORLD, 3, 256, 2),
                    Optional.of(new HallLayout.Target.SeasonSpawn(3))))
            .withLink(
                new HallLayout.Link(
                    "back",
                    Position.of("minecraft:the_nether", 1, 70, 1),
                    Optional.of(new HallLayout.Target.Hall())))
            .withLink(
                new HallLayout.Link(
                    "s1",
                    Position.of(OVERWORLD, 3, 256, 4),
                    Optional.of(
                        new HallLayout.Target.At(new Position(OVERWORLD, 120, 70, -300, 90f, 0f)))))
            .withLink(
                new HallLayout.Link("none", Position.of(OVERWORLD, 3, 256, 6), Optional.empty()));
    Path file = dir.resolve("hall-copy.yml");
    HallFiles.write(file, dir.resolve("backups"), hall);

    HallLayout read = HallFiles.read(YamlConfigurationLoader.builder().path(file).build().load());

    assertThat(read).isEqualTo(hall);
  }

  @Test
  void plainHofAlwaysLandsAtTheHallSpawn() throws IOException {
    visit();
    assertThat(fake.player(alex).gameMode).isEqualTo(GameMode.ADVENTURE);
    assertThat(saved(alex)).map(PlayerRecord.HallReturn::at).contains(HOME);

    // Walking from the hub into the gallery and back is staying in the Hall.
    walk(alex, IN_GALLERY);
    walk(alex, IN_HUB);
    assertThat(saved(alex)).map(PlayerRecord.HallReturn::at).contains(HOME);
    as(alex, "/hof leave");
    assertThat(at(alex)).isEqualTo(HOME);

    // With no Hall spawn, plain /hof is refused, not sent to a gallery's entry.
    Files.writeString(dir.resolve("world/seasons/hall.yml"), "schema-version: 1\n");
    run("/season reload");
    as(alex, "/hof");
    assertThat(lastHeard(alex)).isEqualTo("The Hall of Fame has no spawn yet.");
    assertThat(at(alex)).isEqualTo(HOME);
  }

  @Test
  void theHubIsHallForBuildingAndFurthestBlocks() {
    visit();
    fake.player(alex).position = IN_HUB;
    assertThat(decide(new Intent.PlaceBlock(alex.id(), IN_HUB, "minecraft:stone")))
        .isNotEqualTo(Decision.allow());
    assertThat(seasons.players().orElseThrow().find(alex.id()).orElseThrow().furthest())
        .as("a block placed in the hub doesn't count as the furthest block")
        .isEmpty();
  }

  @Test
  void eachSealingMode() throws IOException {
    // intermission (the default): walking out just leaves.
    visit();
    walk(alex, OUTSIDE);
    assertThat(at(alex)).isEqualTo(OUTSIDE);
    assertThat(lastHeard(alex)).isEqualTo("You left the Hall of Fame.");
    assertThat(saved(alex)).isEmpty();
    fake.tick();
    assertThat(fake.player(alex).gameMode).as("survival back").isEqualTo(GameMode.SURVIVAL);

    // always: back to the saved spot.
    config("hof:\n  sealed: always\n");
    fake.player(alex).position = HOME;
    visit();
    walk(alex, OUTSIDE);
    assertThat(at(alex)).isEqualTo(HOME);

    // During the Intermission, intermission brings them back to the Hall spawn; never doesn't.
    config("");
    run("/season end --now");
    fake.tick();
    fake.player(alex).position = IN_HUB;
    fake.tick();
    walk(alex, OUTSIDE);
    assertThat(at(alex)).isEqualTo(SPAWN);
    config("hof:\n  sealed: never\n");
    fake.player(alex).position = IN_HUB;
    fake.tick();
    walk(alex, OUTSIDE);
    assertThat(at(alex)).isEqualTo(OUTSIDE);
  }

  @Test
  void arrivingWithNoSpotLeavesForTheSeasonSpawn() throws IOException {
    // A respawn at a Hall spawn that is the world spawn: appearing in the hub from far away.
    fake.tick();
    fake.player(alex).position = IN_HUB;
    fake.tick();
    assertThat(saved(alex)).as("nothing to go back to").isEmpty();
    assertThat(fake.player(alex).gameMode).isEqualTo(GameMode.ADVENTURE);

    fake.worldSpawn = Position.of(OVERWORLD, 300.5, 64, 300.5);
    as(alex, "/hof leave");
    assertThat(at(alex)).isEqualTo(fake.worldSpawn);
    assertThat(lastHeard(alex))
        .isEqualTo("There was no spot to send you back to, so you're at the season spawn.");
    assertThat(fake.player(alex).gameMode).isEqualTo(GameMode.SURVIVAL);

    config("hof:\n  leaveWithoutSpot: refuse\n");
    fake.player(alex).position = IN_HUB;
    fake.tick();
    as(alex, "/hof leave");
    assertThat(at(alex)).isEqualTo(IN_HUB);
  }

  @Test
  void linksLeadToTheHallASeasonsSpawnOrASpot() throws IOException {
    Position button = Position.of(OVERWORLD, -5, 64, 5);
    Position back = Position.of(OVERWORLD, 200, 64, 200);
    fake.setBlock(200, 64, 200, FakePlatform.STONE);
    assertThat(hof("/hof link add s3 season 3 -5 64 5"))
        .containsExactly("Link s3 at -5, 64, 5 leads to Season 3's spawn.");
    assertThat(hof("/hof link add home hall 200 64 200"))
        .containsExactly("Link home at 200, 64, 200 leads to the Hall spawn.");
    assertThat(hof("/hof link add spot -5 64 7"))
        .containsExactly(
            "Link spot at -5, 64, 7 leads nowhere yet: stand where it should lead and run /hof"
                + " link to spot.");
    assertThat(hof("/hof link add other hall -5 64 5"))
        .containsExactly("The block at -5, 64, 5 is already link s3.");
    assertThat(hof("/hof link to spot 300 64 300 90"))
        .containsExactly("Link spot now leads to 300, 64, 300.");
    assertThat(Files.readString(dir.resolve("world/seasons/hall.yml"))).contains("s3", "home");

    // The link to the Hall: no bed, and /hof leave goes back to it.
    fake.player(alex).position = Position.of(OVERWORLD, 201.5, 64, 200.5);
    assertThat(decide(new Intent.UseBlock(alex.id(), back, true)))
        .as("the click is taken over")
        .isNotEqualTo(Decision.allow());
    fake.tick();
    assertThat(at(alex)).isEqualTo(SPAWN);
    assertThat(saved(alex))
        .map(PlayerRecord.HallReturn::at)
        .contains(Position.of(OVERWORLD, 201.5, 64, 200.5));
    fake.clock.advance(Duration.ofSeconds(6));

    // Season 3 has no spawn yet.
    decide(new Intent.UseBlock(alex.id(), button, true));
    assertThat(lastHeard(alex)).isEqualTo("Season 3 has no spawn.");
    fake.clock.advance(Duration.ofSeconds(6));

    // A spot: out of the Hall, in survival, with nothing saved.
    decide(new Intent.UseBlock(alex.id(), Position.of(OVERWORLD, -5, 64, 7), true));
    assertThat(at(alex).blockX()).isEqualTo(300);
    assertThat(fake.player(alex).gameMode).isEqualTo(GameMode.SURVIVAL);
    assertThat(saved(alex)).isEmpty();

    // A link block outside the Hall is protected.
    assertThat(decide(new Intent.BreakBlock(alex.id(), back))).isNotEqualTo(Decision.allow());

    // Standing on a link takes it, once.
    fake.clock.advance(Duration.ofSeconds(6));
    fake.player(alex).position = Position.of(OVERWORLD, 200.5, 65, 200.5);
    fake.tick();
    assertThat(at(alex)).isEqualTo(SPAWN);

    // Links out of the Hall wait for the next season.
    run("/season end --now");
    fake.tick();
    fake.clock.advance(Duration.ofSeconds(6));
    fake.player(alex).position = IN_HUB;
    decide(new Intent.UseBlock(alex.id(), Position.of(OVERWORLD, -5, 64, 7), true));
    assertThat(lastHeard(alex))
        .isEqualTo("Links out of the Hall wait for the next season to launch.");
    assertThat(at(alex)).isEqualTo(IN_HUB);
  }

  @Test
  void creativeBuildersBuildInTheHubAndSurvivalOpsDont() throws IOException {
    PlayerRef builder = join("Builder");
    fake.player(builder).position = IN_HUB;
    fake.player(builder).gameMode = GameMode.CREATIVE;
    assertThat(decide(new Intent.PlaceBlock(builder.id(), IN_HUB, "minecraft:hopper")))
        .isEqualTo(Decision.allow());
    assertThat(decide(new Intent.PlaceBlock(builder.id(), IN_HUB, "minecraft:ender_chest")))
        .isEqualTo(Decision.allow());
    assertThat(decide(new Intent.BreakBlock(builder.id(), IN_HUB))).isEqualTo(Decision.allow());

    fake.player(builder).gameMode = GameMode.SURVIVAL;
    assertThat(decide(new Intent.PlaceBlock(builder.id(), IN_HUB, "minecraft:stone")))
        .isNotEqualTo(Decision.allow());

    config("hof:\n  creativeBuilds: false\n");
    fake.player(builder).gameMode = GameMode.CREATIVE;
    assertThat(decide(new Intent.PlaceBlock(builder.id(), IN_HUB, "minecraft:stone")))
        .isNotEqualTo(Decision.allow());
  }

  @Test
  void cellNewWithoutAnIdTakesTheNextFree() {
    assertThat(hof("/hof cell new season-2"))
        .containsExactly("Created cell c4 in gallery season-2.");
    assertThat(hof("/hof cell new season-2"))
        .containsExactly("Created cell c5 in gallery season-2.");
  }

  @Test
  void aCellAssignedBeforeTheWindowIsKept() throws IOException {
    PlayerRef leader = join("Leader");
    PlayerRef sam = join("Sam");
    Path file = dir.resolve("world/seasons/galleries/season-2.yml");
    Files.writeString(
        file, Files.readString(file).replace("cells:", "assignment: ordered\ncells:"));
    run("/season reload");
    hof("/hof cell assign c1 Leader");

    run("/season decorate open");

    assertThat(hof("/hof gallery info season-2"))
        .contains("Cell c1: Leader", "Cell c2: Alex", "Cell c3: Sam");
    assertThat(leader).isNotNull();
    assertThat(sam).isNotNull();
  }

  @Test
  void archivingSendsEveryoneToTheHallSpawnWithArrivalHub() throws IOException {
    config("archive:\n  arrival: hub\n");
    run("/season decorate open");
    run("/season end --now");
    fake.tick();
    assertThat(at(alex)).isEqualTo(SPAWN);
  }

  @Test
  void aSpawnInsideTheHallIsRefusedAndWarnedAbout() throws IOException {
    PlayerRef admin = join("Admin");
    fake.player(admin).position = IN_HUB;
    run("/season end --now");
    TestSource source = new TestSource(Optional.of(admin.id()));
    TestCommands.run(fake.commands.getFirst(), source, "/season spawn set");
    assertThat(source.text()).containsExactly("Season 3's spawn can't be inside the Hall of Fame.");

    Path season = dir.resolve("world/seasons/seasons/3.yml");
    Files.createDirectories(season.getParent());
    Files.writeString(season, "schema-version: 1\nseason: 3\nspawn: { pos: [-4, 64, 10] }\n");
    TestSource reload = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), reload, "/season reload");
    assertThat(reload.text())
        .anyMatch(
            line -> line.contains("Season 3's spawn at -4, 64, 10 is inside the Hall of Fame"));
    TestSource launch = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), launch, "/season launch");
    assertThat(launch.text())
        .contains(
            "Season 3's spawn is inside the Hall of Fame. Move it first with /season spawn set.");
  }

  @Test
  void theCheckReportsWhatDoesntFitTogether() throws IOException {
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.writeString(
        galleries.resolve("season-3.yml"),
        """
        schema-version: 1
        id: season-3
        season: 3
        bounds: { min: [40, 60, 0], max: [60, 80, 20] }
        cells:
          c1:
            bounds: { min: [42, 64, 4], max: [46, 69, 8] }
          c2:
            bounds: { min: [48, 64, 4], max: [52, 69, 8] }
            anchors:
              - { type: figure, pos: [50, 64, 7] }
          c3:
            bounds: { min: [54, 64, 4], max: [58, 69, 9] }
            anchors:
              - { type: figure, pos: [56, 64, 7] }
        """);
    run("/season reload");
    hof("/hof link add gone hall 0 70 30");

    assertThat(hof("/hof hall check"))
        .containsExactly(
            "Hall of Fame check:",
            "Gallery season-3 doesn't touch the hub or a gallery joined to it: walking there is"
                + " leaving the Hall.",
            "Gallery season-3 has no entry.",
            "Cell season-3:c1 has no figure.",
            "Cell season-3:c3 is 5 × 6 × 6, where most of season-3's cells are 5 × 6 × 5.",
            "Link gone's block at 0, 70, 30 is air.",
            "season-2 (Season 2): 3 cells, 3 free.",
            "season-3 (Season 3): 3 cells, 3 free.",
            "5 things to look at.");

    // Bridged by a gallery between: joined to the hub through season-2.
    Files.writeString(
        galleries.resolve("bridge.yml"),
        """
        schema-version: 1
        id: bridge
        season: 2
        bounds: { min: [21, 60, 0], max: [39, 80, 20] }
        entry: { pos: [22, 64, 1] }
        """);
    run("/season reload");
    assertThat(hof("/hof hall check"))
        .doesNotContain(
            "Gallery season-3 doesn't touch the hub or a gallery joined to it: walking there is"
                + " leaving the Hall.");
  }

  @Test
  void theHubCantOverlapAGalleryAndHoldsTheSpawn() {
    assertThat(hof("/hof hall hub -8 60 0 5 80 20"))
        .containsExactly("The hub can't overlap gallery season-2.");
    assertThat(hof("/hof hall hub -8 60 5 -1 80 20"))
        .containsExactly("The hub must hold the Hall spawn at -2, 64, 1; move the spawn first.");
    assertThat(hof("/hof hall spawn 5 64 5"))
        .containsExactly("The Hall spawn must be inside the hub.");
    assertThat(hof("/hof hall spawn -3 64 3 90"))
        .containsExactly("The Hall spawn is now at -3, 64, 3.");
  }
}
