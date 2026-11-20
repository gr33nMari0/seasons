// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ImportedCell;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.FurthestBlock;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Statistics;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.season.Awards;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.SeasonAwards;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Awards (Stats, season cards and awards in the spec): worked out, kept, shown and announced. */
class AwardsTest {
  /** The bundled award list. */
  private static final Map<String, SeasonsConfig.Award> DEFAULTS = bundledAwards();

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private ScenarioHooks hooks;
  private final List<String> posts = new ArrayList<>();

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {
          posts.add(content);
        }

        @Override
        public void close() {}
      };

  private static Map<String, SeasonsConfig.Award> bundledAwards() {
    Map<String, SeasonsConfig.Award> awards = new LinkedHashMap<>();
    awards.put("most-deaths", new SeasonsConfig.Award("deaths", "skeleton_skull"));
    awards.put("furthest-travelled", new SeasonsConfig.Award("travelled", "compass"));
    awards.put("most-dedicated", new SeasonsConfig.Award("play-time", "clock"));
    awards.put("deepest-pockets", new SeasonsConfig.Award("diamonds", "diamond"));
    awards.put("dragon-slayer", new SeasonsConfig.Award("dragon", "dragon_head"));
    awards.put("furthest-builder", new SeasonsConfig.Award("furthest-builder", "spyglass"));
    awards.put("most-advancements", new SeasonsConfig.Award("advancements", "knowledge_book"));
    return awards;
  }

  private static ArchiveRecord archive(String name, Map<String, Long> stats, int advancements) {
    return archive(name, stats, advancements, Optional.empty());
  }

  private static ArchiveRecord archive(
      String name, Map<String, Long> stats, int advancements, Optional<FurthestBlock> furthest) {
    return new ArchiveRecord(
        id(name),
        name,
        2,
        Instant.EPOCH,
        FakeItems.EMPTY,
        List.of(),
        EnderChestPolicy.VAULT,
        new AdvancementSnapshot(Map.of(), advancements, 122),
        Experience.NONE,
        stats,
        furthest,
        Position.of("minecraft:overworld", 0, 0, 0),
        Optional.empty(),
        "hash");
  }

  private static PlayerId id(String name) {
    return new PlayerId(
        UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  private static Optional<SeasonAwards.Award> award(List<SeasonAwards.Award> awards, String id) {
    return awards.stream().filter(a -> a.id().equals(id)).findFirst();
  }

  private static List<String> winners(List<SeasonAwards.Award> awards, String id) {
    return award(awards, id).orElseThrow().winners().stream()
        .map(SeasonAwards.Winner::name)
        .toList();
  }

  @Test
  void tiesShareTheAward() {
    List<SeasonAwards.Award> awards =
        Awards.compute(
            DEFAULTS,
            List.of(
                archive("Sam", Map.of("minecraft:deaths", 5L), 0),
                archive("Alex", Map.of("minecraft:deaths", 5L), 0),
                archive("Pat", Map.of("minecraft:deaths", 2L), 0)),
            Optional.empty());

    assertThat(winners(awards, "most-deaths")).containsExactly("Alex", "Sam");
    assertThat(award(awards, "most-deaths").orElseThrow().value()).isEqualTo(5);
    assertThat(
            Messages.plain(
                Awards.line(Messages.defaults(), award(awards, "most-deaths").orElseThrow())))
        .isEqualTo("Most deaths: Alex and Sam (5)");
  }

  @Test
  void aZeroBestWinsNothing() {
    List<SeasonAwards.Award> awards =
        Awards.compute(
            DEFAULTS,
            List.of(archive("Alex", Map.of(), 0), archive("Sam", Map.of(), 0)),
            Optional.empty());

    // Every award is kept, so the season shows it went to nobody.
    assertThat(awards).hasSize(7).allSatisfy(a -> assertThat(a.winners()).isEmpty());
    assertThat(new SeasonAwards(2, Instant.EPOCH, awards).given()).isEmpty();
    assertThat(
            Awards.lines(Messages.defaults(), new SeasonAwards(2, Instant.EPOCH, awards)).stream()
                .map(Messages::plain))
        .containsExactly("No awards in Season 2.");
  }

  @Test
  void eachMeasure() {
    FurthestBlock far =
        new FurthestBlock(
            Optional.empty(),
            Optional.of(
                new FurthestBlock.Best(3000, Position.of("minecraft:overworld", 3000, -60, 0))));
    FurthestBlock nearer =
        new FurthestBlock(
            Optional.of(
                new FurthestBlock.Best(2000, Position.of("minecraft:overworld", 2000, -60, 0))),
            Optional.of(
                new FurthestBlock.Best(9000, Position.of("minecraft:overworld", 9000, -60, 0))));
    SeasonsConfig.Award jumps = new SeasonsConfig.Award("minecraft:jump", "rabbit_foot");
    Map<String, SeasonsConfig.Award> list = new LinkedHashMap<>(DEFAULTS);
    list.put("most-jumps", jumps);
    list.put("off-one", new SeasonsConfig.Award("off", "stick"));
    List<ArchiveRecord> archives =
        List.of(
            archive(
                "Alex",
                Map.of(
                    "minecraft:walk_one_cm",
                    100_000L,
                    "minecraft:boat_one_cm",
                    60_000L,
                    Statistics.DIAMONDS_MINED,
                    7L,
                    "minecraft:jump",
                    40L),
                20),
            archive(
                "Sam",
                Map.of("minecraft:sprint_one_cm", 150_000L, "minecraft:play_time", 72_000L),
                3,
                Optional.of(far)),
            archive("Pat", Map.of("minecraft:play_time", 1_000L), 1, Optional.of(nearer)));
    SeasonFirst slain =
        new SeasonFirst(2, FirstsTracker.DRAGON, "", id("Pat"), "Pat", Instant.EPOCH);

    List<SeasonAwards.Award> awards = Awards.compute(list, archives, Optional.of(slain));

    assertThat(winners(awards, "most-deaths")).isEmpty();
    // 1,600 blocks by walking and boat beat 1,500 sprinting.
    assertThat(winners(awards, "furthest-travelled")).containsExactly("Alex");
    assertThat(winners(awards, "most-dedicated")).containsExactly("Sam");
    assertThat(winners(awards, "deepest-pockets")).containsExactly("Alex");
    assertThat(winners(awards, "dragon-slayer")).containsExactly("Pat");
    // From the season spawn where there was one, else from 0, 0: Sam's 3,000 beats Pat's 2,000.
    assertThat(winners(awards, "furthest-builder")).containsExactly("Sam");
    assertThat(winners(awards, "most-advancements")).containsExactly("Alex");
    assertThat(winners(awards, "most-jumps")).containsExactly("Alex");
    assertThat(award(awards, "off-one")).isEmpty();

    Messages messages = Messages.defaults();
    assertThat(
            awards.stream()
                .filter(a -> !a.winners().isEmpty())
                .map(a -> Messages.plain(Awards.line(messages, a))))
        .containsExactly(
            "Furthest travelled: Alex (1,600 blocks)",
            "Most dedicated: Sam (1 h 0 min)",
            "Deepest pockets: Alex (7)",
            "Dragon slayer: Pat",
            "Furthest builder: Sam (3,000 blocks)",
            "Most advancements: Alex (20)",
            "Most jumps: Alex (40)");
  }

  @Test
  void importedArchivesTakeNoPart() {
    ArchiveRecord imported =
        new ArchiveRecord(
            id("Old"),
            "Old",
            2,
            Instant.EPOCH,
            FakeItems.EMPTY,
            List.of(),
            EnderChestPolicy.VAULT,
            new AdvancementSnapshot(Map.of(), 99, 122),
            Experience.NONE,
            Map.of("minecraft:deaths", 99L),
            Optional.empty(),
            Position.of("minecraft:overworld", 0, 0, 0),
            Optional.empty(),
            "hash",
            OptionalInt.empty(),
            Optional.of(
                new ImportedCell(
                    "season-1", "c1", Optional.empty(), List.of(), List.of(), List.of())));

    List<SeasonAwards.Award> awards =
        Awards.compute(
            DEFAULTS,
            List.of(imported, archive("Alex", Map.of("minecraft:deaths", 1L), 1)),
            Optional.empty());

    assertThat(winners(awards, "most-deaths")).containsExactly("Alex");
    assertThat(winners(awards, "most-advancements")).containsExactly("Alex");
  }

  @Test
  void theCardListsAwardsAndTheDragonCredit() {
    ArchiveRecord pats = archive("Pat", Map.of("minecraft:deaths", 2L), 1);
    SeasonFirst slain =
        new SeasonFirst(2, FirstsTracker.DRAGON, "", id("Pat"), "Pat", Instant.EPOCH);
    SeasonAwards awards =
        new SeasonAwards(
            2, Instant.EPOCH, Awards.compute(DEFAULTS, List.of(pats), Optional.of(slain)));

    List<String> card =
        com.petepeterepeat.seasons.core.hall.SeasonCard.of(
                pats, Optional.empty(), List.of(slain), Optional.of(awards), Messages.defaults())
            .plainLines();

    assertThat(card)
        .containsSubsequence(
            "Slew the ender dragon",
            "Season firsts",
            "slew the ender dragon",
            "Awards",
            "Most deaths (2)",
            "Dragon slayer",
            "Most advancements (1)",
            "Statistics");
  }

  // End to end on the fake platform.

  private void start() {
    hooks = TestHooks.of(() -> {});
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            MapPublisher.NONE,
            Map.of("SEASONS_DISCORD_WEBHOOK", "https://discord.example/hook"),
            new SerialWriter(Runnable::run),
            Optional.of(hooks));
    seasons.start();
  }

  private void restart() {
    seasons.stop();
    FakePlatform before = fake;
    fake = new FakePlatform();
    fake.players.putAll(before.players);
    start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    if (fake.sessions != null) {
      fake.sessions.joined(player, fake.player(player).saves > 0);
    }
    return player;
  }

  private List<String> broadcasts() {
    return fake.broadcasts.stream().map(Messages::plain).toList();
  }

  /** A gallery of four cells along x, each 10 wide, with a figure, a plaque and two trophies. */
  private void writeGallery() throws IOException {
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    StringBuilder cells = new StringBuilder();
    for (int i = 0; i < 4; i++) {
      int x = i * 10;
      cells.append(
          """
            c%d:
              bounds: { min: [%d, -64, 0], max: [%d, -50, 10] }
              anchors:
                - { type: figure, pos: [%d, -60, 2] }
                - { type: plaque, pos: [%d, -60, 4] }
                - { type: trophy, pos: [%d, -60, 6] }
                - { type: trophy, pos: [%d, -60, 8] }
          """
              .formatted(i + 1, x, x + 9, x + 2, x + 4, x + 6, x + 8));
    }
    Files.writeString(
        galleries.resolve("season-2.yml"),
        """
        schema-version: 1
        id: season-2
        kind: season
        season: 2
        dimension: minecraft:overworld
        bounds: { min: [0, -64, 0], max: [39, -50, 10] }
        cells:
        """
            + cells);
  }

  /** The item ids shown at a block, by trophy displays. */
  private List<String> shownAt(int x, int z) {
    List<String> shown = new ArrayList<>();
    fake.displays.forEach((uuid, drawn) -> {
      if (drawn.kind().equals("item") && drawn.at().blockX() == x && drawn.at().blockZ() == z) {
        try {
          shown.add(String.valueOf(Nbt.read(fake.items.get(uuid).nbt()).get("id")));
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      }
    });
    return shown;
  }

  @Test
  void computedOnceAndKeptAcrossARestart() throws IOException {
    writeGallery();
    start();
    run("/season init 2");
    PlayerRef alex = join("Alex");
    PlayerRef sam = join("Sam");
    PlayerRef late = join("Late");
    run("/season decorate open");
    fake.player(alex).statistics.put("minecraft:deaths", 5L);
    fake.player(sam).statistics.put("minecraft:deaths", 5L);
    fake.player(late).statistics.put("minecraft:deaths", 50L);
    fake.milestones.advancementDone(alex.id(), "minecraft:story/mine_stone", "Stone Age");
    fake.leave(late);

    run("/season end --now");

    assertThat(broadcasts())
        .containsSubsequence(
            "Season 2 has ended. Welcome to the Intermission.",
            "Season 2 awards:",
            "Most deaths: Alex and Sam (5)",
            "Season 2 firsts:",
            "Alex was first to 1 advancement.");
    assertThat(posts.stream().filter(p -> p.startsWith("Season 2 awards:")))
        .singleElement()
        .asString()
        .contains("\nMost deaths: Alex and Sam (5)\n");
    // Copied into the winners' archives, with each player's firsts.
    ArchiveFiles archives = new ArchiveFiles(dir.resolve("world/seasons/data/archives"));
    ArchiveRecord alexs = archives.read(alex.id(), 2).orElseThrow();
    assertThat(alexs.honours().awards()).containsExactly("most-deaths");
    assertThat(alexs.honours().firsts())
        .extracting(SeasonFirst::key)
        .containsExactly("advancement:minecraft:story/mine_stone");
    // Each winner's trophy, a line on their plaque and on their card.
    List<String> trophies = new ArrayList<>();
    for (int x = 6; x < 40; x += 10) {
      trophies.addAll(shownAt(x, 6));
    }
    assertThat(trophies)
        .containsExactlyInAnyOrder("minecraft:skeleton_skull", "minecraft:skeleton_skull");
    assertThat(fake.texts.values().stream().map(Messages::plain))
        .filteredOn(text -> text.contains("Most deaths"))
        .hasSize(2);
    assertThat(hooks.card(alex.id(), 2).join()).containsSubsequence("Awards", "Most deaths (5)");

    // A restart neither announces them again nor works them out again; Late, archived at login,
    // doesn't take the award.
    restart();
    join("Late");
    assertThat(broadcasts()).noneMatch(line -> line.endsWith("awards:"));
    assertThat(run("/season firsts")).contains("Season 2 awards:", "Most deaths: Alex and Sam (5)");
    assertThat(hooks.card(late.id(), 2).join()).containsSubsequence("Awards", "None this season");
  }

  @Test
  void aLateArchiveChangesNothing() {
    start();
    run("/season init 2");
    PlayerRef alex = join("Alex");
    fake.player(alex).statistics.put("minecraft:deaths", 1L);
    PlayerRef late = join("Late");
    fake.player(late).statistics.put("minecraft:deaths", 9L);
    fake.leave(late);
    run("/season end --now");
    join("Late");

    assertThat(run("/season firsts")).contains("Most deaths: Alex (1)");
    assertThat(posts.stream().filter(p -> p.startsWith("Season 2 awards:"))).hasSize(1);
  }
}
