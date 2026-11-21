// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The two templates the jar ships (questions 238), written as code so they can be reviewed. Run
 * {@code ./gradlew :core:writeDefaultTemplates} to write them into {@code templates/};
 * DefaultTemplatesTest checks the committed files match.
 */
public final class DefaultTemplates {
  private DefaultTemplates() {}

  /**
   * A 5 × 5 × 5 alcove open towards +z: a floor, three walls and a roof, a barrel with an archive
   * marker on it, and figure, plaque, trophy and entry markers.
   */
  public static byte[] alcove() {
    StructureBuilder b = new StructureBuilder(5, 5, 5);
    b.fill(1, 1, 1, 3, 3, 4, "minecraft:air");
    b.fill(0, 0, 0, 4, 0, 4, "minecraft:polished_andesite");
    b.fill(0, 4, 0, 4, 4, 4, "minecraft:stone_bricks");
    b.fill(0, 1, 0, 0, 3, 4, "minecraft:stone_bricks");
    b.fill(4, 1, 0, 4, 3, 4, "minecraft:stone_bricks");
    b.fill(1, 1, 0, 3, 3, 0, "minecraft:stone_bricks");
    b.block(
        1,
        1,
        1,
        "minecraft:barrel",
        Map.of("facing", "up", "open", "false"),
        Map.of("id", "minecraft:barrel"));
    b.marker(1, 2, 1, "seasons:archive");
    b.marker(2, 1, 1, "seasons:figure");
    b.marker(3, 1, 1, "seasons:plaque");
    b.marker(2, 3, 1, "seasons:trophy");
    b.marker(2, 1, 3, "seasons:entry");
    return b.build();
  }

  /**
   * A 3 × 4 × 3 plinth: a floor, a plinth block with a figure marker on it, a plaque marker in
   * front (+z) and a trophy marker beside it, for a season gallery's row of plinths or a museum.
   */
  public static byte[] plinth() {
    StructureBuilder b = new StructureBuilder(3, 4, 3);
    b.fill(0, 1, 0, 2, 3, 2, "minecraft:air");
    b.fill(0, 0, 0, 2, 0, 2, "minecraft:smooth_stone");
    b.block(1, 1, 1, "minecraft:chiseled_stone_bricks");
    b.marker(1, 2, 1, "seasons:figure");
    b.marker(1, 1, 2, "seasons:plaque");
    b.marker(0, 1, 1, "seasons:trophy");
    return b.build();
  }

  /** Writes both into a directory, by default the repository's {@code templates/}. */
  public static void main(String[] args) throws IOException {
    Path directory = Path.of(args.length > 0 ? args[0] : "templates");
    Files.createDirectories(directory);
    Files.write(directory.resolve("alcove.nbt"), alcove());
    Files.write(directory.resolve("plinth.nbt"), plinth());
  }
}
