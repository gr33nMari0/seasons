// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The museum on the fake platform: {@code /museum add} and {@code remove} with their refusals and
 * crash recovery, what plinths show, the egg wall at season end and the dry run's check.
 */
class MuseumTest {
  private static final NeutralItem CORE = FakeItems.item("minecraft:heavy_core", 1);
  private static final NeutralItem EGG = FakeItems.item("minecraft:dragon_egg", 1);
  private static final String MUSEUM =
      """
      schema-version: 1
      id: museum
      kind: museum
      dimension: minecraft:overworld
      bounds: { min: [200, -61, 300], max: [214, -50, 310] }
      cells:
        egg-s2:
          bounds: { min: [202, -61, 304], max: [204, -55, 306] }
          anchors:
            - { type: trophy, pos: [203, -59, 305], egg: 2 }
        egg-s3:
          bounds: { min: [206, -61, 304], max: [208, -55, 306] }
          anchors:
            - { type: trophy, pos: [207, -59, 305], egg: 3 }
        core:
          bounds: { min: [210, -61, 302], max: [212, -55, 306] }
          anchors:
            - { type: trophy, pos: [211, -59, 305] }
            - { type: plaque, pos: [211, -60, 303], yaw: 0 }
      """;

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private ScenarioHooks hooks;
  private boolean halted;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  private void start() {
    halted = false;
    hooks =
        TestHooks.of(() -> {
          halted = true;
          throw new ArchivingTest.Halted();
        });
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            MapPublisher.NONE,
            Map.of(),
            new SerialWriter(task -> {
              if (!halted) {
                task.run();
              }
            }),
            Optional.of(hooks));
    seasons.start();
  }

  private void restart() {
    seasons.stop();
    FakePlatform before = fake;
    fake = new FakePlatform();
    fake.clock.advance(Duration.between(fake.clock.instant(), before.clock.instant()));
    fake.players.putAll(before.players);
    start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  /** Season 2 recorded and the museum written; Peter, an op, holds a heavy core. */
  private PlayerRef withMuseum() throws IOException {
    start();
    Files.createDirectories(dir.resolve("world/seasons/galleries"));
    Files.writeString(dir.resolve("world/seasons/galleries/museum.yml"), MUSEUM);
    run("/season reload");
    run("/season init 2");
    PlayerRef peter = fake.join("Peter");
    hold(peter, CORE);
    return peter;
  }

  private void hold(PlayerRef player, NeutralItem item) {
    List<Optional<NeutralItem>> storage = FakeItems.empty(36);
    storage.set(0, Optional.of(item));
    fake.player(player).inventory =
        new InventorySnapshot(storage, FakeItems.empty(4), Optional.empty(), FakeItems.empty(27));
  }

  private Optional<NeutralItem> held(PlayerRef player) {
    return fake.player(player).inventory.storage().getFirst();
  }

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private List<String> as(PlayerRef player, String command) {
    TestSource source = new TestSource(Optional.of(player.id()));
    String name = command.substring(1).split(" ", 2)[0];
    TestCommands.run(
        fake.commands.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow(),
        source,
        command);
    return source.text();
  }

  /** What one plinth shows: the item displayed, and the plaque's plain text. */
  private record Shown(Optional<NeutralItem> item, Optional<String> plaque) {}

  private Shown shown(String cell) {
    Optional<NeutralItem> item = Optional.empty();
    Optional<String> plaque = Optional.empty();
    for (Map.Entry<UUID, FakePlatform.Drawn> entry : fake.displays.entrySet()) {
      if (!entry.getValue().tag().startsWith("museum/" + cell + "/")) {
        continue;
      }
      if (fake.items.containsKey(entry.getKey())) {
        item = Optional.of(fake.items.get(entry.getKey()));
      }
      if (fake.texts.containsKey(entry.getKey())) {
        plaque = Optional.of(Messages.plain(fake.texts.get(entry.getKey())));
      }
    }
    return new Shown(item, plaque);
  }

  @Test
  void addTakesTheHeldItemAndShowsItWithAPlaque() throws IOException {
    PlayerRef peter = withMuseum();

    assertThat(as(peter, "/museum add museum:core Heavy core"))
        .containsExactly("Put minecraft:heavy_core in museum:core.");

    assertThat(held(peter)).isEmpty();
    assertThat(shown("core"))
        .isEqualTo(new Shown(Optional.of(CORE), Optional.of("Heavy core · Season 2 · Peter")));
    // The plaque stands over the plaque anchor; the trophy over its own.
    assertThat(fake.displays.values())
        .filteredOn(d -> d.tag().equals("museum/core/0/plaque"))
        .singleElement()
        .extracting(FakePlatform.Drawn::at)
        .isEqualTo(Position.of("minecraft:overworld", 211.5, -58.8, 303.5));
    assertThat(Files.readString(dir.resolve("world/seasons/museum.yml"))).contains("Heavy core");
    assertThat(Files.exists(dir.resolve("world/seasons/data/museum/museum.core.dat"))).isTrue();
    assertThat(run("/season audit Peter")).anyMatch(line -> line.contains("museum add"));
  }

  @Test
  void withoutALabelThePlaqueHasTheSeasonAndCredit() throws IOException {
    PlayerRef peter = withMuseum();

    as(peter, "/museum add museum:core");

    assertThat(shown("core").plaque()).contains("Season 2 · Peter");
  }

  @Test
  void addIsRefusedWithEachReason() throws IOException {
    PlayerRef peter = withMuseum();
    hold(peter, FakeItems.item("minecraft:stone", 1));
    fake.player(peter).inventory =
        new InventorySnapshot(
            FakeItems.empty(36), FakeItems.empty(4), Optional.empty(), FakeItems.empty(27));

    assertThat(as(peter, "/museum add museum:core"))
        .containsExactly("Hold the item you want to put in the museum.");
    hold(peter, CORE);
    assertThat(as(peter, "/museum add museum:nowhere"))
        .containsExactly("There is no museum anchor museum:nowhere.");
    fake.archivedItems.add(CORE);
    assertThat(as(peter, "/museum add museum:core"))
        .containsExactly("Archived items can't go in the museum.");
    fake.archivedItems.clear();
    as(peter, "/museum add museum:core");
    hold(peter, FakeItems.item("minecraft:stone", 1));
    assertThat(as(peter, "/museum add museum:core"))
        .containsExactly(
            "museum:core already holds minecraft:heavy_core. Take it out first with /museum"
                + " remove museum:core.");
    assertThat(held(peter)).contains(FakeItems.item("minecraft:stone", 1));
    TestSource console = TestSource.console();
    TestCommands.run(
        fake.commands.stream().filter(c -> c.name().equals("museum")).findFirst().orElseThrow(),
        console,
        "/museum add museum:core");
    assertThat(console.text())
        .containsExactly("Only a player can move items in and out of the museum.");
  }

  @Test
  void removeGivesTheItemBackOrKeepsItWhileTheInventoryIsFull() throws IOException {
    PlayerRef peter = withMuseum();
    as(peter, "/museum add museum:core Heavy core");
    fake.player(peter).inventory =
        new InventorySnapshot(
            FakeItems.empty(36).stream()
                .map(slot -> Optional.of(FakeItems.item("minecraft:stone", 64)))
                .toList(),
            FakeItems.empty(4),
            Optional.empty(),
            FakeItems.empty(27));

    assertThat(as(peter, "/museum remove museum:core"))
        .containsExactly("Your inventory is full, so minecraft:heavy_core stays in museum:core.");
    assertThat(shown("core").item()).contains(CORE);

    fake.player(peter).inventory = FakeItems.EMPTY;
    assertThat(as(peter, "/museum remove museum:core"))
        .containsExactly("Took minecraft:heavy_core from museum:core.");
    assertThat(held(peter)).contains(CORE);
    assertThat(shown("core")).isEqualTo(new Shown(Optional.empty(), Optional.empty()));
    assertThat(as(peter, "/museum remove museum:core"))
        .containsExactly("museum:core holds nothing.");
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void aCrashBeforeTheItemIsTakenLeavesItWithTheOp() throws IOException {
    PlayerRef peter = withMuseum();
    InventorySnapshot saved = fake.player(peter).inventory;
    hooks.arm(CrashPoint.MUSEUM_STORED, peter.id());

    crash(() -> as(peter, "/museum add museum:core"));
    // The op's file was saved before anything moved.
    fake.player(peter).inventory = saved;
    restart();
    fake.join("Peter");
    fake.sessions.joined(peter, true);

    assertThat(held(peter)).contains(CORE);
    assertThat(shown("core").item()).isEmpty();
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void aCrashAfterTheItemIsTakenLeavesItInTheMuseum() throws IOException {
    PlayerRef peter = withMuseum();
    hooks.arm(CrashPoint.MUSEUM_TAKEN, peter.id());

    crash(() -> as(peter, "/museum add museum:core"));
    restart();
    fake.join("Peter");
    fake.sessions.joined(peter, true);

    assertThat(held(peter)).isEmpty();
    assertThat(shown("core").item()).contains(CORE);
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void atSeasonEndTheEggPlinthShowsTheEggAndTheSlayer() throws IOException {
    PlayerRef peter = withMuseum();
    run("/season end --now");
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(peter.id())), "/season spawn set");
    run("/season launch");
    PlayerRef pat = fake.join("Pat");
    fake.dragonKilled = true;
    fake.dragonEvents.died(UUID.randomUUID(), Optional.of(pat.id()));
    assertThat(shown("egg-s3").item()).isEmpty();

    run("/season end --now");

    assertThat(shown("egg-s3"))
        .isEqualTo(new Shown(Optional.of(EGG), Optional.of("Season 3 · slain by Pat")));
    // Season 2 had no slayer.
    assertThat(shown("egg-s2")).isEqualTo(new Shown(Optional.empty(), Optional.empty()));
  }

  @Test
  void anEggPlinthWithSomethingPutThereByHandKeepsIt() throws IOException {
    PlayerRef peter = withMuseum();
    hold(peter, EGG);
    as(peter, "/museum add museum:egg-s2 The original egg");

    assertThat(shown("egg-s2"))
        .isEqualTo(new Shown(Optional.of(EGG), Optional.of("The original egg · Season 2 · Peter")));
  }

  @Test
  void theDryRunWarnsWithoutAnEggPlinth() throws IOException {
    start();
    run("/season init 4");
    assertThat(run("/season end --dry-run"))
        .contains(
            "Museum: no egg plinth for Season 4, so its dragon egg won't be shown. Add egg: 4 to a"
                + " trophy anchor in a museum gallery.");

    stop();
    fake = new FakePlatform();
    withMuseum();
    run("/season end --now");
    assertThat(run("/season end --dry-run")).noneMatch(line -> line.startsWith("Museum:"));
  }

  @Test
  void aPlinthACrashLeftUnsettledTakesNoOtherChangeUntilItSettles() throws IOException {
    PlayerRef peter = withMuseum();
    InventorySnapshot saved = fake.player(peter).inventory;
    hooks.arm(CrashPoint.MUSEUM_STORED, peter.id());
    crash(() -> as(peter, "/museum add museum:core"));
    fake.player(peter).inventory = saved;
    fake.leave(peter);
    restart();
    PlayerRef sam = fake.join("Sam");
    fake.player(sam).inventory = FakeItems.EMPTY;

    // The store holds the heavy core Peter still has, until he is back.
    assertThat(as(sam, "/museum remove museum:core"))
        .containsExactly(
            "Something is still moving in or out of museum:core. If a restart interrupted it, it"
                + " settles when the op who started it is back online.");
    assertThat(fake.player(sam).inventory.storage()).allMatch(Optional::isEmpty);

    fake.join("Peter");
    fake.sessions.joined(peter, true);
    assertThat(held(peter)).contains(CORE);
    assertThat(as(sam, "/museum remove museum:core")).containsExactly("museum:core holds nothing.");
    assertThat(fake.player(sam).inventory.storage()).allMatch(Optional::isEmpty);
  }

  @Test
  void eachEggPlinthInACellShowsItsOwnEgg() throws IOException {
    PlayerRef peter = withMuseum();
    Files.writeString(
        dir.resolve("world/seasons/galleries/museum.yml"),
        """
        schema-version: 1
        id: museum
        kind: museum
        dimension: minecraft:overworld
        bounds: { min: [200, -61, 300], max: [214, -50, 310] }
        cells:
          wall:
            bounds: { min: [202, -61, 304], max: [208, -55, 306] }
            anchors:
              - { type: trophy, pos: [203, -59, 305], egg: 2 }
              - { type: trophy, pos: [207, -59, 305], egg: 3 }
        """);
    run("/season reload");
    run("/season end --now");
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(peter.id())), "/season spawn set");
    run("/season launch");
    PlayerRef pat = fake.join("Pat");
    fake.dragonKilled = true;
    fake.dragonEvents.died(UUID.randomUUID(), Optional.of(pat.id()));

    run("/season end --now");

    // Season 2 had no slayer; Season 3's egg stands on the second trophy, its plaque over it.
    assertThat(fake.displays.entrySet())
        .filteredOn(e -> e.getValue().tag().equals("museum/wall/0/trophy"))
        .singleElement()
        .satisfies(e -> {
          assertThat(e.getValue().at())
              .isEqualTo(Position.of("minecraft:overworld", 207.5, -58.5, 305.5));
          assertThat(fake.items.get(e.getKey())).isEqualTo(EGG);
        });
    assertThat(shown("wall").plaque()).contains("Season 3 · slain by Pat");
    assertThat(fake.displays.values())
        .filteredOn(d -> d.tag().equals("museum/wall/0/plaque"))
        .singleElement()
        .extracting(FakePlatform.Drawn::at)
        .isEqualTo(Position.of("minecraft:overworld", 207.5, -57.7, 305.5));
  }

  /** Runs something that reaches an armed crash point, as the server stopping dead there. */
  private void crash(Runnable action) {
    try {
      action.run();
    } catch (ArchivingTest.Halted stopped) {
      // As the server stopping dead.
    }
    assertThat(halted).as("the crash point was reached").isTrue();
  }
}
