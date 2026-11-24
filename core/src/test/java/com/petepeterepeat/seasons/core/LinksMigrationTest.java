// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.hall.Bounds;
import com.petepeterepeat.seasons.core.hall.HallLayout;
import com.petepeterepeat.seasons.core.hall.LinkService;
import com.petepeterepeat.seasons.core.hall.LinksFiles;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
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
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** M8, Upgrading existing setups: 1.1's links copied into links.yml, and links.yml itself. */
class LinksMigrationTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final BlockInfo BUTTON =
      new BlockInfo("minecraft:stone_button", false, true, false, true);
  private static final BlockInfo SIGN =
      new BlockInfo("minecraft:oak_sign", false, true, false, true);
  private static final BlockInfo PLATE =
      new BlockInfo("minecraft:stone_pressure_plate", false, true, false, true);
  private static final BlockInfo TILE =
      new BlockInfo("minecraft:polished_andesite", true, false, false);

  private static final String HALL_YML =
      """
      schema-version: 1
      hub: { min: [-8, 60, 0], max: [-1, 80, 20] }
      spawn: { pos: [-2, 64, 1] }
      links:
        sign: { pos: [-5, 64, 5], to: { season: 3 } }
        button: { pos: [-5, 64, 7], to: hall }
        plate: { pos: [-5, 64, 9], to: { season: 3 } }
        tile: { pos: [-5, 63, 11], to: hall }
        door: { area: { min: [-8, 64, 14], max: [-8, 66, 16] }, to: hall }
        spot: { pos: [-5, 64, 13], to: { pos: [300, 64, 300], yaw: 90 } }
        nowhere: { pos: [-4, 64, 13] }
      """;

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  private void start(String keepStepping) throws IOException {
    Files.createDirectories(dir.resolve("world/seasons"));
    Files.writeString(dir.resolve("world/seasons/hall.yml"), HALL_YML);
    if (keepStepping != null) {
      Files.createDirectories(dir.resolve("plugins/Seasons"));
      Files.writeString(
          dir.resolve("plugins/Seasons/config.yml"),
          "schema-version: 1\nlinks:\n  upgrade:\n    keepStepping: " + keepStepping + "\n");
    }
    fake.setBlock(-5, 64, 5, SIGN);
    fake.setBlock(-5, 64, 7, BUTTON);
    fake.setBlock(-5, 64, 9, PLATE);
    fake.setBlock(-5, 63, 11, TILE);
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

  private HallLayout layout() {
    return seasons.hall().orElseThrow().layout();
  }

  private HallLayout.BlockKind kind(String id) {
    return ((HallLayout.Trigger.OnBlock) layout().link(id).orElseThrow().triggers().getFirst())
        .kind();
  }

  @Test
  void eachLinkKeepsItsIdDestinationAndAccess() throws IOException {
    start(null);
    HallLayout layout = layout();
    assertThat(layout.links().keySet())
        .containsExactly("sign", "button", "plate", "tile", "door", "spot", "nowhere");
    assertThat(layout.links().values())
        .allMatch(l -> l.access().equals(HallLayout.Access.EVERYONE));
    assertThat(layout.link("sign").orElseThrow().to())
        .contains(new HallLayout.Target.SeasonSpawn(3));
    assertThat(layout.link("spot").orElseThrow().to())
        .contains(new HallLayout.Target.At(new Position(OVERWORLD, 300, 64, 300, 90f, 0f)));
    assertThat(layout.link("nowhere").orElseThrow().to()).isEmpty();
    assertThat(layout.link("door").orElseThrow().triggers())
        .containsExactly(
            new HallLayout.Trigger.Area(Bounds.between(OVERWORLD, -8, 64, 14, -8, 66, 16)));
    assertThat(layout.upgrade()).isPresent();
    assertThat(layout.upgrade().get().done()).isFalse();
    // hall.yml is left exactly as it was.
    assertThat(Files.readString(dir.resolve("world/seasons/hall.yml"))).isEqualTo(HALL_YML);
    assertThat(Files.exists(dir.resolve("world/seasons/links.yml"))).isTrue();
  }

  @Test
  void triggerKindsByBlock() throws IOException {
    start(null);
    assertThat(kind("sign")).isEqualTo(HallLayout.BlockKind.USE);
    assertThat(kind("button")).isEqualTo(HallLayout.BlockKind.USE);
    assertThat(kind("plate")).isEqualTo(HallLayout.BlockKind.BOTH);
    assertThat(kind("tile")).isEqualTo(HallLayout.BlockKind.BOTH);
  }

  @Test
  void keepSteppingSettings() {
    assertThat(LinkService.upgradedKind(TILE, "plates-only")).isEqualTo(HallLayout.BlockKind.USE);
    assertThat(LinkService.upgradedKind(BUTTON, "all")).isEqualTo(HallLayout.BlockKind.BOTH);
    assertThat(LinkService.upgradedKind(PLATE, "plates-only")).isEqualTo(HallLayout.BlockKind.BOTH);
    assertThat(LinkService.newKind(TILE)).isEqualTo(HallLayout.BlockKind.USE);
    assertThat(LinkService.newKind(PLATE)).isEqualTo(HallLayout.BlockKind.STEP);
  }

  @Test
  void theCopyIsAtomicAndRunsOnce() throws IOException {
    start("all");
    assertThat(kind("button")).isEqualTo(HallLayout.BlockKind.BOTH);
    String written = Files.readString(dir.resolve("world/seasons/links.yml"));
    seasons.stop();
    // A second start reads links.yml and copies nothing again, even with other settings.
    Files.writeString(
        dir.resolve("plugins/Seasons/config.yml"),
        "schema-version: 1\nlinks:\n  upgrade:\n    keepStepping: plates-only\n");
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
    assertThat(kind("button")).isEqualTo(HallLayout.BlockKind.BOTH);
    assertThat(Files.readString(dir.resolve("world/seasons/links.yml"))).isEqualTo(written);
  }

  @Test
  void writesCarryTheFrozenLinksThroughWithNoSchemaVersionChange() throws IOException {
    start(null);
    // A link edit writes only links.yml.
    TestCommands.run(fake.commands.get(1), TestSource.console(), "/hof link to sign season 2");
    assertThat(Files.readString(dir.resolve("world/seasons/hall.yml"))).isEqualTo(HALL_YML);
    // A hub edit rewrites hall.yml with its 1.1 links as they were.
    TestCommands.run(fake.commands.get(1), TestSource.console(), "/hof hall hub -9 60 0 -1 80 20");
    String hall = Files.readString(dir.resolve("world/seasons/hall.yml"));
    assertThat(hall).contains("schema-version: 1", "sign:", "season: 3", "tile:", "door:");
    assertThat(Files.readString(dir.resolve("world/seasons/links.yml")))
        .contains("schema-version: 1");
  }

  @Test
  void linkTextsAreDrawnOnce() throws IOException {
    start(null);
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, "/hof link text add sign 0 64 0 <gold>Season 3");
    assertThat(source.text()).contains("Link sign gains a trigger: floating text at 0, 64, 0.");
    int before = fake.texts.size();
    fake.tick();
    fake.tick();
    assertThat(fake.texts.size()).as("drawn once, not every sweep").isEqualTo(before + 1);
  }

  @Test
  void linksYmlReadsBackAsWritten() throws Exception {
    HallLayout.Link full =
        new HallLayout.Link(
            "hof-s1",
            Optional.of("<gold>Season 1"),
            Optional.of(new HallLayout.Target.SeasonSpawn(1)),
            List.of(
                new HallLayout.Trigger.OnBlock(
                    Position.of(OVERWORLD, 3, 257, -8), HallLayout.BlockKind.USE),
                new HallLayout.Trigger.Area(Bounds.between(OVERWORLD, 10, 256, -2, 10, 259, 2)),
                new HallLayout.Trigger.Text(
                    new Position(OVERWORLD, 0.5, 258.25, -6.5, 90f, 0f),
                    List.of("<bold>Season 1", "below"),
                    1.5,
                    "center",
                    1.0,
                    true)),
            new HallLayout.Access(HallLayout.Access.Kind.PERMISSION, Optional.of("group.vip")),
            new HallLayout.When("played", OptionalInt.of(2), Optional.empty()),
            Optional.of(new Position("minecraft:the_nether", 4, 70, 4, 180f, 0f)),
            Optional.of("hof-s1-back"));
    HallLayout hall =
        HallLayout.NONE
            .withLink(full)
            .withLink(
                HallLayout.Link.of(
                    "hof-s1-back",
                    Optional.of(new HallLayout.Target.ToLink("hof-s1")),
                    List.of(),
                    HallLayout.Access.OP))
            .withLink(
                HallLayout.Link.of(
                    "p",
                    Optional.of(new HallLayout.Target.AtPoint("steps")),
                    List.of(),
                    HallLayout.Access.EVERYONE))
            .withLink(
                HallLayout.Link.of(
                    "g",
                    Optional.of(new HallLayout.Target.ToGallery("season-1")),
                    List.of(),
                    HallLayout.Access.EVERYONE))
            .withLink(
                HallLayout.Link.of(
                    "r",
                    Optional.of(new HallLayout.Target.Return()),
                    List.of(),
                    HallLayout.Access.EVERYONE))
            .withPoint(
                new HallLayout.Point(
                    "steps",
                    new Position(OVERWORLD, 512, 70, -300, 90f, 0f),
                    Optional.of(Bounds.between(OVERWORLD, 512, 70, -300, 514, 70, -298))))
            .withUpgrade(
                Optional.of(
                    new HallLayout.Upgrade(
                        "2026-11-24T08:00:00Z", true, Map.of("a", "both"), "1a2b")));
    Path file = dir.resolve("links.yml");
    LinksFiles.write(file, dir.resolve("backups"), hall);
    LinksFiles.Contents read =
        LinksFiles.read(YamlConfigurationLoader.builder().path(file).build().load(), OVERWORLD);
    assertThat(read.links()).isEqualTo(hall.links());
    assertThat(read.points()).isEqualTo(hall.points());
    assertThat(read.upgrade()).isEqualTo(hall.upgrade());
  }
}
