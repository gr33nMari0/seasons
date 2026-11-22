// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.Position;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Areas for entries, exits, links and the Hall spawn, and plaque sizes (questions 278, 279). */
class AreasTest {
  private static final String OVERWORLD = "minecraft:overworld";

  @TempDir Path dir;

  private static Position at(double x, double y, double z) {
    return Position.of(OVERWORLD, x, y, z);
  }

  @Test
  void aMoveAcrossAThinPlaneTriggersIt() {
    Bounds plane = Bounds.between(OVERWORLD, 150, -40, 100, 150, -36, 104);
    // Four blocks in one move, from one side of the plane to the other.
    assertThat(HallVisits.crosses(plane, at(147.5, -38, 102.5), at(153.5, -38, 102.5))).isTrue();
    // Beside it, or short of it.
    assertThat(HallVisits.crosses(plane, at(147.5, -38, 106.5), at(153.5, -38, 106.5))).isFalse();
    assertThat(HallVisits.crosses(plane, at(147.5, -38, 102.5), at(149.5, -38, 102.5))).isFalse();
    // Up through a hole in a ceiling.
    Bounds hole = Bounds.between(OVERWORLD, 12, -56, 27, 13, -56, 28);
    assertThat(HallVisits.crosses(hole, at(12.5, -58, 27.5), at(12.5, -54, 27.5))).isTrue();
  }

  @Test
  void areasAndPlaqueSizesReadAndWriteBack() throws Exception {
    Cell cell =
        Cell.create("c1")
            .withBounds(Bounds.between(OVERWORLD, 0, -61, 0, 6, -54, 6))
            .withAnchor(Anchor.of(AnchorType.FIGURE, Position.of(OVERWORLD, 3, -60, 5)))
            .withAnchor(
                Anchor.of(AnchorType.ENTRY, Position.of(OVERWORLD, 1, -60, 1))
                    .withArea(Optional.of(Bounds.between(OVERWORLD, 1, -60, 1, 3, -60, 2))))
            .withAnchor(
                Anchor.of(AnchorType.PLAQUE, Position.of(OVERWORLD, 2, -60, 4))
                    .withScale(OptionalDouble.of(1.5)));
    Gallery gallery =
        Gallery.create("season-2", 2, OVERWORLD)
            .withBounds(Bounds.between(OVERWORLD, 0, -61, 0, 20, -50, 10))
            .withEntry(
                Position.of(OVERWORLD, 8, -60, 1),
                Optional.of(Bounds.between(OVERWORLD, 8, -60, 1, 10, -60, 2)))
            .withExitArea(Bounds.between(OVERWORLD, 12, -60, 0, 12, -58, 2))
            .withCell(cell);
    gallery = gallery.withSettings(gallery.settings().withPlaqueScale(Optional.of(0.75)));
    Path file = dir.resolve("season-2.yml");
    GalleryFiles.write(file, dir.resolve("backups"), gallery);

    Gallery read =
        GalleryFiles.read("season-2", YamlConfigurationLoader.builder().path(file).build().load());

    assertThat(read).isEqualTo(gallery);
    assertThat(read.plaqueScale(read.cell("c1").orElseThrow().anchor(AnchorType.PLAQUE).get()))
        .isEqualTo(1.5);
    assertThat(GalleryRules.check(read)).isEmpty();
  }

  @Test
  void hallYmlReadsAreas() throws Exception {
    HallLayout hall =
        HallLayout.NONE
            .withHub(Bounds.between(OVERWORLD, -12, 250, -12, 12, 290, 12))
            .withSpawn(
                Position.of(OVERWORLD, -2, 256, -2),
                Optional.of(Bounds.between(OVERWORLD, -2, 256, -2, 2, 256, 2)))
            .withExitArea(Bounds.between(OVERWORLD, 10, 256, -1, 10, 258, 1))
            .withLink(
                new HallLayout.Link(
                    "sky",
                    Position.of(OVERWORLD, 150, 70, 100),
                    Optional.of(new HallLayout.Target.Hall()),
                    Optional.of(Bounds.between(OVERWORLD, 150, 70, 100, 150, 74, 104))));
    Path file = dir.resolve("hall.yml");
    HallFiles.write(file, dir.resolve("backups"), hall);

    assertThat(HallFiles.read(YamlConfigurationLoader.builder().path(file).build().load()))
        .isEqualTo(hall);
  }
}
