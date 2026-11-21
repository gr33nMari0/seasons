// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.hall.StampPlan;
import com.petepeterepeat.seasons.core.hall.Template;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
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

/** {@code /hof stamp} (questions 230 to 235). */
class StampTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String GALLERY =
      """
      schema-version: 1
      id: season-2
      season: 2
      bounds: { min: [0, 63, 0], max: [60, 90, 30] }
      entry: { pos: [1, 64, 1] }
      cells: {}
      """;

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private PlayerRef admin;
  private final SavingImports imports = new SavingImports();

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
    Files.writeString(galleries.resolve("season-2.yml"), GALLERY);
    fake.imports = imports;
    start();
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), "/season init 2");
    admin = fake.join("Admin");
    standAt(2, 64, 2);
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
    seasons.stop();
  }

  private void standAt(int x, int y, int z) {
    fake.player(admin).position = new Position(OVERWORLD, x + 0.5, y, z + 0.5, 0f, 0f);
  }

  private List<String> stamp(String arguments) {
    TestSource source = new TestSource(Optional.of(admin.id()));
    TestCommands.run(fake.commands.get(1), source, "/hof stamp " + arguments);
    return source.text();
  }

  private String file() throws IOException {
    return Files.readString(dir.resolve("world/seasons/galleries/season-2.yml"));
  }

  private static List<Path> files(Path directory) throws IOException {
    try (java.util.stream.Stream<Path> files = Files.list(directory)) {
      return files.toList();
    }
  }

  private String type(int x, int y, int z) {
    return fake.blockAt(Position.of(OVERWORLD, x, y, z)).type();
  }

  @Test
  void copiesStandSideBySideWithTheirMarkersAsAnchorsAndAir() throws IOException {
    assertThat(stamp("alcove season-2 3"))
        .containsExactly("Stamped 3 copies of alcove (built in) into season-2: c1, c2, c3.");
    for (int i = 0; i < 3; i++) {
      int x = 2 + 5 * i;
      assertThat(type(x + 1, 65, 3)).as("the barrel").isEqualTo("minecraft:barrel");
      assertThat(type(x + 2, 65, 3)).as("the figure marker").isEqualTo("minecraft:air");
      assertThat(type(x + 1, 66, 3)).as("the archive marker").isEqualTo("minecraft:air");
      assertThat(type(x, 65, 2)).as("the wall").isEqualTo("minecraft:stone_bricks");
    }
    String text = file();
    assertThat(text).contains("c1:", "c2:", "c3:");
    assertThat(fake.templates.placed).hasSize(3);
    String journal = Files.readString(dir.resolve("world/seasons/data/journal.log"));
    assertThat(journal).contains("STAMP_STARTED").contains("STAMP_DONE");
    assertThat(files(dir.resolve("world/seasons/data/stamps")))
        .as("the plan is gone once done")
        .isEmpty();
    assertThat(imports.saves).as("the world is saved before the gallery file").isEqualTo(1);
    assertThat(Files.readString(dir.resolve("world/seasons/audit/2026-11.jsonl")))
        .contains("\"stamp\"")
        .contains("alcove");
  }

  @Test
  void aStepSpacesTheCopiesAndARotationTurnsThemAndTheirAnchors() throws IOException {
    assertThat(stamp("alcove season-2 2 --step 0 0 8"))
        .containsExactly("Stamped 2 copies of alcove (built in) into season-2: c1, c2.");
    assertThat(type(3, 65, 11)).isEqualTo("minecraft:barrel");
    standAt(30, 64, 2);
    assertThat(stamp("alcove season-2 1 --rotate 90"))
        .containsExactly("Stamped 1 copy of alcove (built in) into season-2: c3.");
    // The barrel at [1, 1, 1] turned clockwise: x - 1, z + 1.
    assertThat(type(29, 65, 3)).isEqualTo("minecraft:barrel");
    String c3 = file().substring(file().indexOf("    c3:"));
    assertThat(c3).contains("yaw: 90");
  }

  @Test
  void aCopyOutsideTheGalleryOverACellOrOverABuildChangesNothing() throws IOException {
    stamp("alcove season-2 1");
    String before = file();
    assertThat(stamp("alcove season-2 1"))
        .containsExactly("Copy 1 would overlap cell season-2:c1.");
    standAt(52, 64, 2);
    assertThat(stamp("alcove season-2 2"))
        .containsExactly("Copy 2 would reach outside gallery season-2.");
    standAt(20, 64, 10);
    fake.setBlock(21, 66, 12, new BlockInfo("minecraft:stone", true, false, false));
    assertThat(stamp("alcove season-2 1"))
        .containsExactly(
            "Copy 1 would replace minecraft:stone at 21, 66, 12; stamps only go where there's air.");
    assertThat(stamp("alcove season-2 1 --rotate 45"))
        .containsExactly("--rotate takes 0, 90, 180 or 270.");
    // The placeholders in these are shown as written in game; plain text drops them.
    assertThat(stamp("alcove season-2 1 --step 1 2"))
        .singleElement()
        .asString()
        .startsWith("--step takes three whole numbers: --step");
    assertThat(stamp("alcove season-2 1 --mirror"))
        .singleElement()
        .asString()
        .startsWith("Unknown option --mirror. /hof stamp takes --step");
    assertThat(stamp("nothing season-2 1"))
        .singleElement()
        .asString()
        .startsWith("There's no template called nothing. Templates: alcove, plinth");
    assertThat(file()).isEqualTo(before);
    assertThat(fake.templates.placed).hasSize(1);
  }

  @Test
  void eachTemplateRefusal() {
    Map<String, StructureBuilder> templates =
        Map.of(
            "nofigure", booth().marker(1, 1, 1, "seasons:plaque"),
            "twoplaques",
                booth()
                    .marker(1, 1, 1, "seasons:figure")
                    .marker(0, 1, 0, "seasons:plaque")
                    .marker(2, 1, 0, "seasons:plaque"),
            "onegate", booth().marker(1, 1, 1, "seasons:figure").marker(0, 1, 0, "seasons:gate"),
            "podium", booth().marker(1, 1, 1, "seasons:figure").marker(0, 1, 0, "seasons:podium"),
            "floating",
                booth().marker(1, 1, 1, "seasons:figure").marker(0, 2, 0, "seasons:archive"),
            "stash",
                booth().marker(1, 1, 1, "seasons:figure").container(0, 1, 0, "minecraft:chest", 3),
            "hopper", booth().marker(1, 1, 1, "seasons:figure").block(0, 1, 0, "minecraft:hopper"),
            "plain",
                booth()
                    .marker(1, 1, 1, "seasons:figure")
                    .block(0, 1, 0, "minecraft:structure_block"));
    Map<String, String> reasons =
        Map.of(
            "nofigure", "it has no seasons:figure marker",
            "twoplaques", "it has 2 seasons:plaque markers; a cell has at most 1",
            "onegate", "it has 1 seasons:gate marker; a gate needs 2, one at each corner",
            "podium", "seasons:podium at 0, 1, 0 isn't a marker Seasons knows",
            "floating", "the seasons:archive marker at 0, 2, 0 must sit on the block it anchors",
            "stash", "the minecraft:chest at 0, 1, 0 holds items; templates hold none",
            "hopper", "it has a minecraft:hopper at 0, 1, 0, which the Hall doesn't allow",
            "plain",
                "the structure block at 0, 1, 0 isn't a Seasons marker: only one in DATA mode with"
                    + " seasons: metadata is");
    templates.forEach((name, builder) -> fake.templates.saved.put(name, builder.build()));
    reasons.forEach((name, reason) ->
        assertThat(stamp(name + " season-2 1"))
            .as(name)
            .containsExactly("Template " + name + " can't be stamped: " + reason + "."));
    assertThat(fake.templates.placed).isEmpty();
  }

  /** A 3 × 3 × 3 template with a floor. */
  private static StructureBuilder booth() {
    return new StructureBuilder(3, 3, 3)
        .fill(0, 1, 0, 2, 2, 2, "minecraft:air")
        .fill(0, 0, 0, 2, 0, 2, "minecraft:smooth_stone");
  }

  @Test
  void templatesAreFoundInTheFolderThenSavedStructuresThenTheJar() throws IOException {
    fake.templates.saved.put(
        "booth",
        booth().marker(1, 1, 1, "seasons:figure").marker(1, 1, 2, "seasons:entry").build());
    assertThat(stamp("booth season-2 1"))
        .containsExactly("Stamped 1 copy of booth (from a structure block) into season-2: c1.");
    Path templates = dir.resolve("world/seasons/templates");
    Files.createDirectories(templates);
    Files.write(templates.resolve("booth.nbt"), DefaultTemplates.plinth());
    standAt(10, 64, 2);
    assertThat(stamp("booth season-2 1"))
        .containsExactly("Stamped 1 copy of booth (from templates/) into season-2: c2.");
    assertThat(type(11, 65, 3))
        .as("the plinth's block")
        .isEqualTo("minecraft:chiseled_stone_bricks");
    Files.write(templates.resolve("alcove.nbt"), DefaultTemplates.plinth());
    standAt(20, 64, 2);
    assertThat(stamp("alcove season-2 1"))
        .containsExactly("Stamped 1 copy of alcove (from templates/) into season-2: c3.");
  }

  @Test
  void aStampACrashInterruptedIsFinishedAtStartupFromItsPlan() throws IOException {
    seasons.stop();
    Path stamps = dir.resolve("world/seasons/data/stamps");
    Files.createDirectories(stamps);
    StampPlan plan =
        new StampPlan(
            "season-2",
            "alcove",
            Template.Source.BUNDLED,
            OVERWORLD,
            2,
            64,
            2,
            0,
            5,
            0,
            0,
            List.of("c1", "c2"),
            "Admin");
    Files.writeString(stamps.resolve("abc-1.plan"), plan.write());
    Files.write(stamps.resolve("abc-1.nbt"), DefaultTemplates.alcove());
    Journal journal =
        Journal.open(
            dir.resolve("world/seasons/data/journal.log"),
            new SerialWriter(Runnable::run),
            fake.clock);
    journal.append(JournalKind.STAMP_STARTED, admin.id(), 2, "abc-1").join();
    // The first copy was placed before the crash; recovery places it again over itself.
    fake.templates.place(DefaultTemplates.alcove(), Position.of(OVERWORLD, 2, 64, 2), 0);

    start();

    assertThat(file()).contains("c1:", "c2:");
    assertThat(type(3, 65, 3)).isEqualTo("minecraft:barrel");
    assertThat(type(8, 65, 3)).isEqualTo("minecraft:barrel");
    assertThat(type(4, 65, 3)).isEqualTo("minecraft:air");
    assertThat(Files.readString(dir.resolve("world/seasons/data/journal.log")))
        .contains("STAMP_DONE");
    assertThat(files(stamps)).isEmpty();
  }

  /** The import port, of which a stamp uses only saving the world. */
  private static final class SavingImports implements com.petepeterepeat.seasons.core.port.Imports {
    int saves;

    @Override
    public java.util.concurrent.CompletableFuture<Scan> scan(Position min, Position max) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean emptyContainer(Position block) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean fillContainer(
        Position block, List<Optional<com.petepeterepeat.seasons.core.port.NeutralItem>> slots) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(java.util.UUID entity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.UUID spawnStand(
        Position at,
        Map<String, Object> properties,
        List<Optional<com.petepeterepeat.seasons.core.port.NeutralItem>> worn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.UUID spawnFrame(
        Position at,
        Map<String, Object> properties,
        Optional<com.petepeterepeat.seasons.core.port.NeutralItem> item) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void saveWorld(String dimension) {
      saves++;
    }
  }
}
