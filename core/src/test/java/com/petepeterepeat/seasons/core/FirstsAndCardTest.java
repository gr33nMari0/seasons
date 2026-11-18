// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Statistics;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Season firsts (A13), the season card (A14) and galleries left out at startup. */
class FirstsAndCardTest {
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

  private void start() {
    hooks = new ScenarioHooks(() -> {});
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
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

  @Test
  void theFirstToEachMilestoneIsAnnouncedOnceAndRemembered() {
    start();
    run("/season init 2");
    PlayerRef alex = join("Alex");
    PlayerRef sam = join("Sam");

    fake.milestones.advancementDone(alex.id(), "minecraft:story/mine_stone", "Stone Age");
    fake.milestones.advancementDone(sam.id(), "minecraft:story/mine_stone", "Stone Age");
    fake.milestones.enteredDimension(sam.id(), "minecraft:the_nether");
    fake.milestones.enteredDimension(alex.id(), "minecraft:the_nether");
    fake.milestones.enteredDimension(alex.id(), "minecraft:overworld");
    fake.player(sam).carried.add("minecraft:trident");
    fake.player(alex).carried.add("minecraft:dirt");
    fake.tick();
    fake.player(alex).carried.add("minecraft:trident");
    fake.tick();

    assertThat(broadcasts())
        .containsExactly(
            "Season first! Alex completed [Stone Age].",
            "Season first! Sam reached the Nether.",
            "Season first! Sam found Trident.");
    assertThat(posts)
        .containsExactly(
            "Season first! Alex completed [Stone Age].",
            "Season first! Sam reached the Nether.",
            "Season first! Sam found Trident.");

    // Recorded in season.json: after a restart they are still taken.
    restart();
    PlayerRef late = join("Late");
    fake.milestones.advancementDone(late.id(), "minecraft:story/mine_stone", "Stone Age");
    assertThat(fake.broadcasts).isEmpty();
    assertThat(run("/season firsts")).first().isEqualTo("Season 2 firsts:");
    assertThat(run("/season firsts"))
        .hasSize(4)
        .element(1)
        .asString()
        .startsWith("Alex completed [Stone Age]");
  }

  @Test
  void nothingIsAFirstOnceTheSeasonHasEnded() {
    start();
    run("/season init 2");
    PlayerRef alex = join("Alex");
    run("/season end --now");

    fake.milestones.enteredDimension(alex.id(), "minecraft:the_end");

    assertThat(broadcasts()).noneMatch(line -> line.startsWith("Season first!"));
  }

  @Test
  void theSeasonCardHasHighlightsFirstsAndEveryGeneralStatistic() throws IOException {
    writeGallery("season-2", 2, "c1", "[0, -64, 0]", "[10, -50, 10]");
    start();
    run("/season init 2");
    PlayerRef alex = fake.join("Alex");
    fake.player(alex).statistics.put("minecraft:deaths", 1L);
    fake.player(alex).statistics.put("minecraft:play_time", 0L);
    fake.player(alex).counts.put(Statistics.DIAMONDS_MINED, 2L);
    fake.sessions.joined(alex, false);
    // The baseline was taken at their first login of the season: the card shows what came after.
    fake.player(alex).statistics.put("minecraft:deaths", 4L);
    fake.player(alex).statistics.put("minecraft:play_time", 20L * 60 * 90);
    fake.player(alex).statistics.put("minecraft:walk_one_cm", 150_000L);
    fake.player(alex).counts.put(Statistics.DIAMONDS_MINED, 9L);
    fake.milestones.advancementDone(alex.id(), "minecraft:story/mine_stone", "Stone Age");
    run("/season decorate open");
    run("/season end --now");

    List<String> card = hooks.card(alex.id(), 2).join();

    assertThat(card)
        .contains(
            "Alex",
            "Season 2",
            "Play time: 1 h 30 min",
            "Travelled: 1,500 blocks",
            "Season firsts",
            "completed [Stone Age]",
            "Statistics",
            "minecraft:walk_one_cm: 1,500 blocks")
        .anyMatch(line -> line.startsWith("Ended at "))
        .anyMatch(line -> line.startsWith("Furthest block: "));
    assertThat(card)
        .contains("Diamonds mined: 7", "Deaths: 3")
        .anyMatch(line -> line.startsWith("Advancements: "));
  }

  @Test
  void theCardOpensFromThePlaqueAnchorAsABook() throws IOException {
    writeGallery("season-2", 2, "c1", "[0, -64, 0]", "[10, -50, 10]");
    start();
    run("/season init 2");
    PlayerRef alex = join("Alex");
    run("/season decorate open");
    run("/season end --now");

    fake.handler.decide(
        new com.petepeterepeat.seasons.core.event.Intent.UseBlock(
            alex.id(),
            com.petepeterepeat.seasons.core.port.Position.of("minecraft:overworld", 4, -60, 4),
            true));

    assertThat(fake.player(alex).books).hasSize(1);
    assertThat(fake.player(alex).books.getFirst().getFirst())
        .extracting(Messages::plain)
        .startsWith("Alex", "Season 2");
  }

  @Test
  void aBrokenGalleryIsLeftOutAndHoldsTheEndAndLaunch() throws IOException {
    writeGallery("good", 2, "c1", "[0, -64, 0]", "[10, -50, 10]");
    Path broken = dir.resolve("world/seasons/galleries/broken.yml");
    Files.writeString(broken, "schema-version: 1\nid: broken\nbounds: [nonsense\n");
    start();
    run("/season init 2");
    run("/season schedule end 2026-12-31T19:00:00Z");

    assertThat(run("/season status"))
        .anyMatch(line -> line.startsWith("Gallery broken couldn't be loaded at startup"));
    assertThat(run("/season end --now"))
        .singleElement()
        .asString()
        .startsWith("Season 2 can't end while a gallery file failed to load");
    // Everything else loaded: the good gallery and the season's planned end.
    assertThat(hofCommand("/hof gallery info good")).isNotEmpty();
    assertThat(run("/season status")).anyMatch(line -> line.startsWith("Planned end:"));
    assertThat(hofCommand("/hof gallery create broken 2"))
        .singleElement()
        .asString()
        .contains("failed to load at startup");

    Files.delete(broken);
    assertThat(run("/season reload")).first().asString().startsWith("Reloaded ");
    assertThat(run("/season status")).noneMatch(line -> line.startsWith("Gallery broken"));
    assertThat(run("/season end --now")).first().asString().startsWith("Ending Season 2 now");
  }

  @Test
  void aGalleryBrokenDuringTheIntermissionHoldsTheLaunch() throws IOException {
    start();
    run("/season init 2");
    run("/season end --now");
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    Files.writeString(galleries.resolve("broken.yml"), "schema-version: 1\nbounds: [nonsense\n");

    restart();

    assertThat(run("/season launch"))
        .anyMatch(
            line -> line.startsWith("Season 3 can't launch while a gallery file failed to load"));
  }

  private List<String> hofCommand(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  private void writeGallery(String id, int season, String cell, String min, String max)
      throws IOException {
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    Files.writeString(
        galleries.resolve(id + ".yml"),
        """
        schema-version: 1
        id: %s
        kind: season
        season: %d
        dimension: minecraft:overworld
        bounds: { min: %s, max: %s }
        cells:
          %s:
            bounds: { min: %s, max: %s }
            anchors:
              - { type: figure, pos: [2, -60, 2] }
              - { type: plaque, pos: [4, -60, 4] }
        """
            .formatted(id, season, min, max, cell, min, max));
  }
}
