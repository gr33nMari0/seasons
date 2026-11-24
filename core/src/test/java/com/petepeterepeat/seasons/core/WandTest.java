// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The wand (questions 226 to 229 and 236). */
class WandTest {
  private static final String OVERWORLD = "minecraft:overworld";

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
        bounds: { min: [0, 60, 0], max: [40, 80, 20] }
        entry: { pos: [1, 64, 1] }
        cells: {}
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
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), "/season init 2");
    admin = fake.join("Admin");
    fake.player(admin).position = new Position(OVERWORLD, 30.5, 64, 15.5, 0f, 0f);
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private TestSource hof(String command) {
    TestSource source = new TestSource(Optional.of(admin.id()));
    TestCommands.run(fake.commands.get(1), source, command);
    return source;
  }

  private Decision click(int x, int y, int z, String face, boolean left, boolean sneaking) {
    return fake.handler.decide(
        new Intent.UseWand(admin.id(), Position.of(OVERWORLD, x, y, z), face, left, sneaking));
  }

  private List<String> said() {
    return fake.sent.stream()
        .filter(s -> s.player().equals(admin.id()))
        .map(s -> TestSource.plain(s.message()))
        .toList();
  }

  /** A cell's minimum corner as the gallery file has it, as "x, y, z". */
  private String minOf(String cell) throws IOException {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "\\n    "
                    + cell
                    + ":\\n        bounds:\\n            min:\\n"
                    + "            - (-?\\d+)\\n            - (-?\\d+)\\n            - (-?\\d+)")
            .matcher(file());
    assertThat(m.find()).as(cell + " has bounds in the file").isTrue();
    return m.group(1) + ", " + m.group(2) + ", " + m.group(3);
  }

  private String file() throws IOException {
    return Files.readString(dir.resolve("world/seasons/galleries/season-2.yml"));
  }

  @Test
  void theWandIsGivenToHallAdmins() {
    assertThat(hof("/hof wand").text())
        .singleElement()
        .asString()
        .startsWith("Gave you the Seasons wand.");
    assertThat(fake.wands).containsExactly(admin.id());
  }

  @Test
  void placeModeTakesTheBlockAgainstTheFace() {
    hof("/hof wand mode place");
    click(5, 63, 5, "up", true, true);
    click(9, 63, 9, "east", false, true);
    assertThat(said())
        .contains(
            "The wand's corners now take the block against the face you click.",
            "Selected 5, 63, 5 to 10, 64, 9 in minecraft:overworld: 6 × 2 × 5 blocks.");
    // Swap-hands toggles it back, and the swap is taken over.
    assertThat(fake.handler.decide(new Intent.WandSwap(admin.id())))
        .isInstanceOf(Decision.Deny.class);
    click(5, 63, 5, "up", true, true);
    click(9, 63, 9, "east", false, true);
    assertThat(said())
        .contains("Selected 5, 63, 5 to 9, 63, 9 in minecraft:overworld: 5 × 1 × 5 blocks.");
  }

  @Test
  void selCommandsFollowTheSameRules() {
    fake.player(admin).position = new Position(OVERWORLD, 10.5, 64, 10.5, 0f, 0f);
    hof("/hof sel pos1 ~ ~ ~");
    assertThat(said()).contains("Corner 1: 10, 64, 10");
    hof("/hof sel pos2 ~2 ~1 ~2");
    assertThat(hof("/hof sel info").text())
        .containsExactly(
            "Selection: 10, 64, 10 to 12, 65, 12 in minecraft:overworld: 3 × 2 × 3 blocks.");
    hof("/hof sel clear");
    assertThat(hof("/hof sel info").text().getFirst()).startsWith("No selection.");
  }

  @Test
  void cornersMakeASelectionAndCornerOneStartsANewOne() {
    assertThat(click(5, 63, 5, "up", true, true)).isInstanceOf(Decision.Deny.class);
    assertThat(fake.player(admin).actionBars)
        .extracting(TestSource::plain)
        .containsExactly("Corner 1: 5, 63, 5");
    click(9, 70, 12, "up", false, true);
    assertThat(said())
        .contains("Selected 5, 63, 5 to 9, 70, 12 in minecraft:overworld: 5 × 8 × 8 blocks.");
    click(1, 63, 1, "up", true, true);
    assertThat(hof("/hof cell new season-2 c1").text())
        .containsExactly("Created cell c1 in gallery season-2.");
    assertThat(hof("/hof cell bounds season-2:c1").text())
        .containsExactly("Give both corners, or select them with /hof wand first.");
  }

  @Test
  void aCornerInAnotherDimensionStartsANewSelection() {
    click(5, 63, 5, "up", true, true);
    fake.handler.decide(
        new Intent.UseWand(
            admin.id(), Position.of("minecraft:the_nether", 9, 70, 9), "up", false, true));
    assertThat(said()).noneMatch(line -> line.startsWith("Selected"));
    assertThat(fake.player(admin).actionBars)
        .extracting(TestSource::plain)
        .last()
        .isEqualTo("Corner 2: 9, 70, 9");
  }

  @Test
  void theWandCellTakesTheSelectionAsItsBounds() throws IOException {
    hof("/hof cell new season-2 c1");
    click(2, 63, 2, "up", true, true);
    click(6, 70, 8, "up", false, true);
    assertThat(said()).contains("Cell season-2:c1 now spans 2, 63, 2 to 6, 70, 8.");
    assertThat(file()).contains("c1:");
    // A selection reaching outside the gallery stays only a selection.
    hof("/hof cell new season-2 c2");
    click(38, 63, 2, "up", true, true);
    click(44, 70, 8, "up", false, true);
    assertThat(said()).noneMatch(line -> line.startsWith("Cell season-2:c2"));
    assertThat(hof("/hof cell bounds season-2:c2").text())
        .containsExactly("Cell season-2:c2 must sit inside gallery season-2.");
    // An overlap is refused as the command refuses it.
    click(4, 63, 4, "up", true, true);
    click(9, 70, 9, "up", false, true);
    assertThat(said()).contains("Cell season-2:c2 would overlap season-2:c1.");
  }

  @Test
  void theWandCellTakesOneSelectionOnlyAndNeverMovesBoundsItHas() throws IOException {
    hof("/hof cell new season-2 c1");
    click(2, 63, 2, "up", true, true);
    click(6, 70, 8, "up", false, true);
    assertThat(said()).contains("Cell season-2:c1 now spans 2, 63, 2 to 6, 70, 8.");
    // A second selection, and a corner 2 that reuses corner 1, leave c1 as it is.
    click(20, 63, 2, "up", true, true);
    click(24, 70, 8, "up", false, true);
    click(26, 70, 9, "up", false, true);
    assertThat(minOf("c1")).isEqualTo("2, 63, 2");
    // /hof cell info doesn't make a cell the wand's either.
    hof("/hof cell new season-2 c2");
    hof("/hof cell bounds season-2:c2 10 63 2 14 70 8");
    hof("/hof cell new season-2 c3");
    hof("/hof cell info season-2:c2");
    click(30, 63, 2, "up", true, true);
    click(34, 70, 8, "up", false, true);
    assertThat(minOf("c2")).isEqualTo("10, 63, 2");
    assertThat(minOf("c3")).isEqualTo("30, 63, 2");
    assertThat(said()).contains("Cell season-2:c3 now spans 30, 63, 2 to 34, 70, 8.");
  }

  @Test
  void newBoundsMustHoldEveryAnchorTheCellHas() throws IOException {
    hof("/hof cell new season-2 c1");
    hof("/hof cell bounds season-2:c1 2 63 2 6 70 8");
    hof("/hof cell anchor season-2:c1 figure 4 64 4 0");
    assertThat(hof("/hof cell bounds season-2:c1 10 63 2 14 70 8").text())
        .containsExactly(
            "Cell season-2:c1's figure anchor at 4, 64, 4 would be outside those bounds. Move or"
                + " remove it first.");
    assertThat(minOf("c1")).isEqualTo("2, 63, 2");
  }

  @Test
  void eachAnchorTypeLandsWhereItShouldAndTheWandWritesWhatTheCommandWrites() throws IOException {
    hof("/hof cell new season-2 c1");
    hof("/hof cell bounds season-2:c1 2 63 2 6 70 8");
    hof("/hof cell new season-2 c2");
    hof("/hof cell bounds season-2:c2 12 63 2 16 70 8");
    String[][] picks = {
      {"minecraft:chest", "3", "64", "7", "up"},
      {"minecraft:armor_stand", "4", "63", "6", "up"},
      {"minecraft:lectern", "5", "64", "6", "up"},
      {"minecraft:item_frame", "2", "65", "7", "east"},
      {"minecraft:oak_door", "4", "63", "3", "up"}
    };
    for (String[] pick : picks) {
      assertThat(
              click(
                  Integer.parseInt(pick[1]),
                  Integer.parseInt(pick[2]),
                  Integer.parseInt(pick[3]),
                  pick[4],
                  false,
                  false))
          .isInstanceOf(Decision.Deny.class);
      fake.choices.getLast().click(pick[0]);
    }
    assertThat(said())
        .contains(
            "Added an archive anchor to season-2:c1 at 3, 64, 7.",
            "Added a figure anchor to season-2:c1 at 4, 64, 6.",
            "Added a plaque anchor to season-2:c1 at 5, 64, 6.",
            "Added a trophy anchor to season-2:c1 at 3, 65, 7.",
            "Added an entry anchor to season-2:c1 at 4, 64, 3.");
    for (String command :
        List.of(
            "/hof cell anchor season-2:c2 archive 13 64 7 180",
            "/hof cell anchor season-2:c2 figure 14 64 6 180",
            "/hof cell anchor season-2:c2 plaque 15 64 6 180",
            "/hof cell anchor season-2:c2 trophy 13 65 7 180",
            "/hof cell anchor season-2:c2 entry 14 64 3 180")) {
      hof(command);
    }
    String text = file();
    String c1 = text.substring(text.indexOf("    c1:"), text.indexOf("    c2:"));
    String c2 = text.substring(text.indexOf("    c2:"));
    assertThat(c1.replace("c1:", "")).isEqualTo(shiftX(c2.replace("c2:", ""), -10));
  }

  /** The text with every x of a {@code pos} or bounds list moved, for comparing two cells. */
  private static String shiftX(String text, int dx) {
    StringBuilder out = new StringBuilder();
    String[] lines = text.split("\n", -1);
    boolean next = false;
    for (String line : lines) {
      if (next && line.trim().startsWith("- ")) {
        int value = Integer.parseInt(line.trim().substring(2).trim());
        out.append(line, 0, line.indexOf('-')).append("- ").append(value + dx).append('\n');
        next = false;
        continue;
      }
      next = line.trim().equals("pos:") || line.trim().equals("min:") || line.trim().equals("max:");
      out.append(line).append('\n');
    }
    return out.substring(0, out.length() - 1);
  }

  @Test
  void aLinkMadeWithTheWandNeverWritesOverALeftOutHallYml() throws IOException {
    seasons.stop();
    Path hallYml = dir.resolve("world/seasons/hall.yml");
    String broken = "schema-version: 1\nhub: { min: [-8, 60, 0], max: [-1, 80] }\n";
    Files.writeString(hallYml, broken);
    FakePlatform again = new FakePlatform();
    seasons =
        new Seasons(
            again.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            again.clock,
            webhooks,
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
    PlayerRef op = again.join("Admin");
    again.player(op).position = new Position(OVERWORLD, 30.5, 64, 15.5, 0f, 0f);

    again.handler.decide(
        new Intent.UseWand(op.id(), Position.of(OVERWORLD, 30, 63, 15), "up", false, false));
    again.choices.getLast().click("minecraft:lodestone");

    assertThat(
            again.sent.stream()
                .filter(s -> s.player().equals(op.id()))
                .map(s -> TestSource.plain(s.message())))
        .contains(
            "hall.yml failed to load at startup, so changing the hub, the Hall spawn or links would"
                + " write over it. Fix the file and run /season reload.");
    assertThat(Files.readString(hallYml)).as("the admin's file is untouched").isEqualTo(broken);
  }

  @Test
  void outsideTheHallTheMenuOffersLinks() {
    // M8: the wand makes links anywhere, with a real destination from the start.
    assertThat(click(60, 63, 15, "up", false, false)).isInstanceOf(Decision.Deny.class);
    assertThat(fake.choices.getLast().icons())
        .contains("minecraft:lodestone", "minecraft:ender_eye", "minecraft:paper")
        .doesNotContain("minecraft:iron_bars", "minecraft:oak_door");
    fake.choices.getLast().click("minecraft:lodestone");
    assertThat(said())
        .contains("Link l1 leads to the Hall spawn, from the block at 60, 63, 15, clicked.");
  }

  @Test
  void aPendingTriggerIsTakenByLinkAdd() {
    click(61, 63, 15, "up", false, false);
    fake.choices.getLast().click("minecraft:paper");
    assertThat(hof("/hof link add later to hall").text())
        .contains("Link later leads to the Hall spawn, from the block at 61, 63, 15, clicked.");
  }

  @Test
  void aTriggerOpensItsEditor() {
    click(60, 63, 15, "up", false, false);
    fake.choices.getLast().click("minecraft:lodestone");
    int menus = fake.choices.size();
    click(60, 63, 15, "up", false, false);
    assertThat(fake.choices).hasSize(menus);
    assertThat(said()).contains("Link l1:");
  }

  @Test
  void inTheHallButInNoCellTheMenuOffersALinkAndADoorwayGate() throws IOException {
    hof("/hof cell new season-2 c1");
    hof("/hof cell bounds season-2:c1 2 64 2 8 69 8");
    assertThat(click(30, 63, 15, "up", false, false)).isInstanceOf(Decision.Deny.class);
    assertThat(fake.choices.getLast().icons()).contains("minecraft:lodestone");
    fake.choices.getLast().click("minecraft:lodestone");
    assertThat(said())
        .contains("Link l1 leads to the Hall spawn, from the block at 30, 63, 15, clicked.");
    assertThat(Files.readString(dir.resolve("world/seasons/links.yml"))).contains("l1");

    // The floor of the doorway beside c1's east side: a gate corner in front of it.
    click(9, 63, 4, "up", false, false);
    assertThat(fake.choices.getLast().icons())
        .contains("minecraft:lodestone", "minecraft:iron_bars");
    fake.choices.getLast().click("minecraft:iron_bars");
    assertThat(said()).contains("Added a gate anchor to season-2:c1 at 9, 64, 4.");
  }

  @Test
  void theCellsFloorFindsTheCellForAFigure() throws IOException {
    hof("/hof cell new season-2 c1");
    hof("/hof cell bounds season-2:c1 2 64 2 8 69 8");
    click(5, 63, 5, "up", false, false);
    fake.choices.getLast().click("minecraft:armor_stand");
    assertThat(said()).contains("Added a figure anchor to season-2:c1 at 5, 64, 5.");
  }

  @Test
  void boundsKeepADoorwayGate() throws IOException {
    hof("/hof cell new season-2 c1");
    hof("/hof cell bounds season-2:c1 2 64 2 8 69 8");
    hof("/hof cell anchor season-2:c1 gate 9 64 3");
    hof("/hof cell anchor season-2:c1 gate 9 69 7");
    assertThat(hof("/hof cell bounds season-2:c1 2 64 2 8 70 8").text())
        .containsExactly("Cell season-2:c1 now spans 2, 64, 2 to 8, 70, 8.");
    assertThat(hof("/hof cell bounds season-2:c1 2 64 2 7 70 8").text())
        .containsExactly(
            "Cell season-2:c1's gate anchor at 9, 64, 3 would be outside those bounds. Move or"
                + " remove it first.");
    assertThat(hof("/hof cell anchor season-2:c1 gate 11 64 3").text())
        .containsExactly("A gate corner goes inside cell season-2:c1 or right beside it.");
  }

  @Test
  void removeIsOfferedOnAnAnchorAndTakesItOff() throws IOException {
    hof("/hof cell new season-2 c1");
    hof("/hof cell bounds season-2:c1 2 63 2 6 70 8");
    hof("/hof cell anchor season-2:c1 archive 3 64 7");
    click(3, 64, 7, "up", false, false);
    assertThat(fake.choices.getLast().icons()).endsWith("minecraft:barrier");
    fake.choices.getLast().click("minecraft:barrier");
    assertThat(said()).contains("Removed the archive anchor at 3, 64, 7 from season-2:c1.");
    assertThat(file()).doesNotContain("archive");
  }

  @Test
  void withoutTheNodesTheWandIsJustAnItem() {
    fake.withoutAdmin.add(admin.id());
    assertThat(click(5, 63, 5, "up", true, true)).isEqualTo(Decision.allow());
    assertThat(click(5, 63, 5, "up", false, false)).isEqualTo(Decision.allow());
    assertThat(fake.player(admin).actionBars).isEmpty();
  }

  @Test
  void aMuseumOffersAnEggPlinthForASeasonWithoutOne() throws IOException {
    Files.writeString(
        dir.resolve("world/seasons/galleries/museum.yml"),
        """
        schema-version: 1
        id: museum
        kind: museum
        bounds: { min: [100, 60, 0], max: [120, 80, 20] }
        cells:
          wall:
            bounds: { min: [101, 60, 1], max: [110, 70, 5] }
            anchors:
              - { type: trophy, pos: [102, 64, 2], egg: 2 }
        """);
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), "/season reload");
    click(104, 63, 2, "up", false, false);
    assertThat(fake.choices.getLast().icons()).contains("minecraft:dragon_egg");
    assertThat(fake.choices.getLast().options())
        .extracting(o -> TestSource.plain(o.label()))
        .contains("Egg plinth for Season 3", "Egg plinth for Season 1")
        .doesNotContain("Egg plinth for Season 2");
    fake.choices.getLast().click("minecraft:dragon_egg");
    assertThat(said()).contains("Added an egg plinth for Season 3 to museum:wall at 104, 64, 2.");
  }

  @Test
  void theSelectionIsForgottenAtLogout() {
    click(5, 63, 5, "up", true, true);
    click(9, 70, 9, "up", false, true);
    fake.sessions.left(admin.id());
    hof("/hof cell new season-2 c1");
    assertThat(hof("/hof cell bounds season-2:c1").text())
        .containsExactly("Give both corners, or select them with /hof wand first.");
  }
}
