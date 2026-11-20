// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petepeterepeat.seasons.core.config.ConfigLoader;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class GalleryFilesTest {
  private static final String OVERWORLD = "minecraft:overworld";

  @TempDir Path dir;

  private static Position at(int x, int y, int z, float yaw) {
    return new Position(OVERWORLD, x, y, z, yaw, 0f);
  }

  static Gallery gallery() {
    PlayerId owner = new PlayerId(UUID.fromString("e07dc330-cb9f-34d0-b06c-7bb7282b33c4"));
    Cell cell =
        Cell.create("c1")
            .withBounds(Bounds.between(OVERWORLD, 0, -61, 0, 4, -54, 8))
            .withAnchor(Anchor.of(AnchorType.FIGURE, at(2, -60, 6, 180f)))
            .withAnchor(
                new Anchor(
                    AnchorType.ARCHIVE, at(3, -60, 6, 0f), OptionalInt.of(2), OptionalInt.empty()))
            .withOwner(2, Optional.of(owner))
            .withImported(true);
    return Gallery.create("season-2", 2, OVERWORLD)
        .withBounds(Bounds.between(OVERWORLD, 0, -61, 0, 20, -50, 10))
        .withEntry(at(1, -60, 1, 90f))
        .withCell(cell)
        .withCell(Cell.create("bare"));
  }

  @Test
  void aGalleryRoundTripsThroughItsFile() throws Exception {
    Path file = dir.resolve("season-2.yml");
    GalleryFiles.write(file, dir.resolve("backups"), gallery());

    Gallery read =
        GalleryFiles.read("season-2", YamlConfigurationLoader.builder().path(file).build().load());

    assertThat(read).isEqualTo(gallery());
  }

  @Test
  void anchorsOutsideTheirCellAndOverlappingGalleriesAreProblems() {
    Gallery gallery = gallery();
    Cell stray =
        Cell.create("stray")
            .withBounds(Bounds.between(OVERWORLD, 30, -61, 0, 34, -54, 8))
            .withAnchors(List.of(Anchor.of(AnchorType.FIGURE, at(100, -60, 100, 0f))));
    Gallery other =
        Gallery.create("other", 2, OVERWORLD)
            .withBounds(Bounds.between(OVERWORLD, 10, -61, 0, 40, -50, 10));

    assertThat(GalleryRules.check(List.of(gallery.withCell(stray), other)))
        .extracting(GalleryRules.Problem::message)
        .contains(
            "overlaps gallery season-2",
            "must sit inside the gallery's bounds",
            "the figure anchor must sit inside the cell's bounds");
  }

  private static ConfigurationNode yaml(String text) throws Exception {
    return YamlConfigurationLoader.builder().buildAndLoadString(text);
  }

  @Test
  void aGallerysOwnSettingsReadAndWriteBack() throws Exception {
    Gallery.Settings settings =
        new Gallery.Settings(
            Optional.of(SeasonsConfig.CellAssignment.CLAIM),
            Optional.of(SeasonsConfig.CellAccess.GATE),
            Optional.of(FigureRenderer.ARMOUR_STAND),
            Optional.of(false),
            Optional.of(Duration.ofMinutes(30)));
    Path file = dir.resolve("season-2.yml");
    GalleryFiles.write(file, dir.resolve("backups"), gallery().withSettings(settings));

    Gallery read =
        GalleryFiles.read("season-2", YamlConfigurationLoader.builder().path(file).build().load());

    assertThat(read.settings()).isEqualTo(settings);
    assertThat(java.nio.file.Files.readString(file))
        .contains(
            "assignment: claim", "access: gate", "renderer: armour-stand", "afterArchive: 30m");
  }

  @Test
  void unsetSettingsComeFromConfigYml() throws Exception {
    SeasonsConfig config = ConfigLoader.defaults().config();
    Gallery plain = GalleryFiles.read("season-2", yaml("season: 2\n"));
    Gallery own =
        GalleryFiles.read(
            "season-2", yaml("season: 2\naccess: bounds\ndecorating: { afterArchive: 5m }\n"));

    assertThat(plain.settings()).isEqualTo(Gallery.Settings.NONE);
    assertThat(plain.assignment(config)).isEqualTo(SeasonsConfig.CellAssignment.RANDOM);
    assertThat(plain.access(config)).isEqualTo(SeasonsConfig.CellAccess.OPEN);
    assertThat(plain.renderer()).isEqualTo(FigureRenderer.MANNEQUIN);
    assertThat(plain.afterArchive(config)).isEqualTo(Duration.ofMinutes(15));
    assertThat(own.access(config)).isEqualTo(SeasonsConfig.CellAccess.BOUNDS);
    assertThat(own.afterArchive(config)).isEqualTo(Duration.ofMinutes(5));
    assertThat(own.lockAtEnd(config)).isTrue();
  }

  @Test
  void aBadSettingSaysWhere() {
    assertThatThrownBy(() -> GalleryFiles.read("season-2", yaml("season: 2\naccess: walls\n")))
        .isInstanceOf(GalleryFiles.Invalid.class)
        .hasMessage("is one of open, bounds, gate, not 'walls'")
        .extracting(e -> ((GalleryFiles.Invalid) e).path())
        .isEqualTo("access");
    assertThatThrownBy(() -> GalleryFiles.read("season-2", yaml("season: 2\nrenderer: statue\n")))
        .hasMessage("is mannequin or armour-stand, not 'statue'");
    assertThatThrownBy(() ->
            GalleryFiles.read("season-2", yaml("season: 2\ndecorating: { afterArchive: soon }\n")))
        .extracting(e -> ((GalleryFiles.Invalid) e).path())
        .isEqualTo("decorating.afterArchive");
  }

  @Test
  void aCellsPoseReadsAndABadOneSaysWhere() throws Exception {
    Gallery read =
        GalleryFiles.read("season-2", yaml("season: 2\ncells:\n  c1:\n    pose: crouching\n"));
    assertThat(read.cell("c1").orElseThrow().pose()).contains("crouching");

    Path file = dir.resolve("posed.yml");
    GalleryFiles.write(file, dir.resolve("backups"), read);
    assertThat(java.nio.file.Files.readString(file)).contains("pose: crouching");

    assertThatThrownBy(() ->
            GalleryFiles.read("season-2", yaml("season: 2\ncells:\n  c1:\n    pose: dabbing\n")))
        .extracting(e -> ((GalleryFiles.Invalid) e).path())
        .isEqualTo("cells.c1.pose");
  }

  @Test
  void anEggPlinthReadsAndWritesBackAndOnlyATrophyCanBeOne() throws Exception {
    String museum =
        """
        kind: museum
        cells:
          egg-s3:
            anchors:
              - { type: trophy, pos: [1, -59, 1], egg: 3 }
        """;
    Gallery read = GalleryFiles.read("museum", yaml(museum));
    assertThat(read.cell("egg-s3").orElseThrow().anchor(AnchorType.TROPHY).orElseThrow().egg())
        .isEqualTo(OptionalInt.of(3));

    Path file = dir.resolve("museum.yml");
    GalleryFiles.write(file, dir.resolve("backups"), read);
    assertThat(
            GalleryFiles.read(
                "museum", YamlConfigurationLoader.builder().path(file).build().load()))
        .isEqualTo(read);

    assertThatThrownBy(
            () -> GalleryFiles.read("museum", yaml(museum.replace("type: trophy", "type: plaque"))))
        .extracting(e -> ((GalleryFiles.Invalid) e).path())
        .isEqualTo("cells.egg-s3.anchors.0.egg");
  }

  @Test
  void onlyAMuseumHasEggPlinthsAndOnlyOnePerSeason() {
    Anchor egg =
        new Anchor(AnchorType.TROPHY, at(2, -60, 2, 0f), OptionalInt.empty(), OptionalInt.of(3));
    Cell plinth =
        Cell.create("egg")
            .withBounds(Bounds.between(OVERWORLD, 0, -61, 0, 4, -54, 4))
            .withAnchor(egg);
    Gallery season =
        Gallery.create("season-3", 3, OVERWORLD)
            .withBounds(Bounds.between(OVERWORLD, 0, -61, 0, 10, -50, 10))
            .withCell(plinth);
    assertThat(GalleryRules.check(List.of(season)))
        .extracting(GalleryRules.Problem::message)
        .contains("only a museum can have an egg plinth");

    Gallery museum = museum("museum", 100, plinth);
    Gallery annex = museum("annex", 200, plinth);
    assertThat(GalleryRules.check(List.of(museum)))
        .extracting(GalleryRules.Problem::message)
        .isEmpty();
    assertThat(GalleryRules.check(List.of(museum, annex)))
        .extracting(GalleryRules.Problem::message)
        .containsExactly("Season 3 already has its egg plinth at museum:egg");
  }

  /** A museum gallery at {@code x} with one plinth moved there. */
  private static Gallery museum(String id, int x, Cell plinth) {
    Anchor egg = plinth.anchors().getFirst();
    Anchor moved = new Anchor(egg.type(), at(x + 2, -60, 2, 0f), egg.page(), egg.egg());
    Cell cell =
        Cell.create("egg")
            .withBounds(Bounds.between(OVERWORLD, x, -61, 0, x + 4, -54, 4))
            .withAnchor(moved);
    return new Gallery(
        id,
        Gallery.Kind.MUSEUM,
        0,
        OVERWORLD,
        Optional.of(Bounds.between(OVERWORLD, x, -61, 0, x + 10, -50, 10)),
        Optional.empty(),
        List.of(),
        List.of(cell),
        Gallery.Settings.NONE,
        Optional.empty());
  }
}
