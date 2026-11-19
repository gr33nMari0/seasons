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
            .withAnchor(new Anchor(AnchorType.ARCHIVE, at(3, -60, 6, 0f), OptionalInt.of(2)))
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
}
