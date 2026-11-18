// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.HallGuard;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Hall's deny-by-default rules (A22 to A25, A44's building half), its guard against changes
 * nobody makes, the audit of refusals, and reconciliation.
 */
class HallRulesTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Position IN_C1 = Position.of(OVERWORLD, 5, 64, 5);
  private static final Position ALSO_IN_C1 = Position.of(OVERWORLD, 6, 64, 5);
  private static final Position IN_C2 = Position.of(OVERWORLD, 11, 64, 5);
  private static final Position CORRIDOR = Position.of(OVERWORLD, 2, 64, 2);
  private static final Position OUTSIDE = Position.of(OVERWORLD, 50, 64, 50);
  private static final Position JUST_OUTSIDE = Position.of(OVERWORLD, 31, 64, 5);
  private static final Position HALL_EDGE = Position.of(OVERWORLD, 30, 64, 5);
  private static final Position BETWEEN_CELLS = Position.of(OVERWORLD, 9, 64, 5);
  private static final BlockInfo CHEST = new BlockInfo("minecraft:chest", true, false, false);
  private static final BlockInfo POT = new BlockInfo("minecraft:decorated_pot", true, false, false);
  private static final BlockInfo FURNACE = new BlockInfo("minecraft:furnace", true, false, false);
  private static final BlockInfo DOOR = new BlockInfo("minecraft:oak_door", true, false, false);
  private static final BlockInfo LEVER = new BlockInfo("minecraft:lever", false, true, false);
  private static final BlockInfo SHULKER =
      new BlockInfo("minecraft:light_blue_shulker_box", true, false, false);

  @TempDir Path dir;
  private final FakePlatform fake = new FakePlatform();
  private final AtomicLong nanos = new AtomicLong();

  /** How far the reconciler's clock moves each time it is read. */
  private long step;

  private Seasons seasons;
  private PlayerRef alex;
  private PlayerRef kim;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void setUp() throws IOException {
    writeGallery();
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.useNanoTime(() -> nanos.addAndGet(step));
    seasons.start();
    run("/season init 2");
    alex = join("Alex");
    kim = join("Kim");
    hof("/hof cell assign season-2:c1 Alex");
    hof("/hof cell assign season-2:c2 Kim");
    run("/season decorate open");
  }

  @AfterEach
  void stop() {
    seasons.stop();
  }

  private void run(String command) {
    TestCommands.run(fake.commands.getFirst(), TestSource.console(), command);
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.get(1), source, command);
    return source.text();
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    fake.sessions.joined(player, false);
    return player;
  }

  private Decision decide(Intent intent) {
    return fake.handler.decide(intent);
  }

  private static String text(Decision decision) {
    return decision instanceof Decision.Deny deny
        ? deny.message().map(Messages::plain).orElse("(quietly)")
        : "allowed";
  }

  private void block(Position at, BlockInfo info) {
    fake.setBlock(at.blockX(), at.blockY(), at.blockZ(), info);
  }

  @SafeVarargs
  private void container(Position at, BlockInfo info, Optional<NeutralItem>... items) {
    block(at, info);
    fake.containers.put(FakePlatform.key(at), List.of(items));
  }

  private List<JsonObject> audit() throws IOException {
    Path file = dir.resolve("world/seasons/audit/2026-11.jsonl");
    if (!Files.exists(file)) {
      return List.of();
    }
    return Files.readAllLines(file).stream()
        .map(line -> JsonParser.parseString(line).getAsJsonObject())
        .toList();
  }

  private List<JsonObject> audited(String action) throws IOException {
    return audit().stream().filter(e -> e.get("action").getAsString().equals(action)).toList();
  }

  @Test
  void anOwnerDecoratesTheirOwnCellAndNobodyElseBuildsInTheHall() {
    assertThat(decide(new Intent.PlaceBlock(alex.id(), IN_C1, "minecraft:stone")))
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.PlaceBlock(kim.id(), IN_C1, "minecraft:stone"))))
        .isEqualTo("You can't build here.");
    assertThat(text(decide(new Intent.PlaceBlock(alex.id(), CORRIDOR, "minecraft:stone"))))
        .isEqualTo("You can't build here.");
    assertThat(text(decide(new Intent.BreakBlock(kim.id(), IN_C1))))
        .isEqualTo("You can't build here.");
    assertThat(decide(new Intent.PlaceBlock(kim.id(), OUTSIDE, "minecraft:stone")))
        .isEqualTo(Decision.allow());

    run("/season decorate close");
    assertThat(text(decide(new Intent.PlaceBlock(alex.id(), IN_C1, "minecraft:stone"))))
        .isEqualTo("You can't build here.");
  }

  @Test
  void noHopperDropperDispenserCrafterOrEnderChestGoesInTheHall() {
    for (String block :
        List.of(
            "minecraft:hopper",
            "minecraft:dropper",
            "minecraft:dispenser",
            "minecraft:crafter",
            "minecraft:ender_chest")) {
      assertThat(text(decide(new Intent.PlaceBlock(alex.id(), IN_C1, block))))
          .as(block)
          .isEqualTo(
              "Hoppers, droppers, dispensers, crafters and ender chests can't go in the Hall of"
                  + " Fame.");
    }
    assertThat(text(decide(new Intent.PlaceEntity(alex.id(), IN_C1, "minecraft:hopper_minecart"))))
        .startsWith("Hoppers, droppers");
    assertThat(text(decide(new Intent.PlaceEntity(alex.id(), IN_C1, "minecraft:chest_minecart"))))
        .isEqualTo("You can't build here.");
  }

  @Test
  void onlyListedContainersGoInACellAndOnlyEmptyOnesBreak() {
    container(IN_C1, FURNACE);
    assertThat(text(decide(new Intent.PlaceBlock(alex.id(), IN_C1, "minecraft:furnace"))))
        .isEqualTo("That container can't go in a cell.");
    container(IN_C1, SHULKER);
    assertThat(decide(new Intent.PlaceBlock(alex.id(), IN_C1, SHULKER.type())))
        .as("every colour of shulker box counts")
        .isEqualTo(Decision.allow());

    container(IN_C1, CHEST, Optional.of(FakeItems.item("minecraft:diamond", 3)), Optional.empty());
    assertThat(text(decide(new Intent.BreakBlock(alex.id(), IN_C1))))
        .isEqualTo("Take everything out of it first.");
    container(IN_C1, CHEST, Optional.empty(), Optional.empty());
    assertThat(decide(new Intent.BreakBlock(alex.id(), IN_C1))).isEqualTo(Decision.allow());
  }

  @Test
  void onlyTheDecoratingOwnerOpensTheRealContainerAndEveryoneElseACopy() {
    Optional<NeutralItem> diamonds = Optional.of(FakeItems.item("minecraft:diamond", 3));
    container(IN_C1, CHEST, diamonds, Optional.empty());

    assertThat(decide(new Intent.OpenContainer(alex.id(), IN_C1))).isEqualTo(Decision.allow());
    assertThat(fake.copies).isEmpty();
    assertThat(decide(new Intent.UseBlock(kim.id(), IN_C1, true, false, true, false, true)))
        .as("the click goes through, so the open can be swapped for a copy")
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.OpenContainer(kim.id(), IN_C1)))).isEqualTo("(quietly)");
    assertThat(fake.copies).hasSize(1);
    assertThat(fake.copies.getFirst().viewer()).isEqualTo(kim.id());
    assertThat(fake.copies.getFirst().items()).containsExactly(diamonds, Optional.empty());
    assertThat(Messages.plain(fake.copies.getFirst().title())).isEqualTo("Alex's cell · Season 2");

    // After the lock, the owner gets a copy too.
    run("/season decorate close");
    assertThat(text(decide(new Intent.OpenContainer(alex.id(), IN_C1)))).isEqualTo("(quietly)");
    assertThat(fake.copies).hasSize(2);
    assertThat(fake.copies.getLast().viewer()).isEqualTo(alex.id());
  }

  @Test
  void aDecoratedPotShowsACopyAndTakesNothing() {
    Optional<NeutralItem> emerald = Optional.of(FakeItems.item("minecraft:emerald", 1));
    container(IN_C1, POT, emerald);
    assertThat(text(decide(new Intent.UseBlock(kim.id(), IN_C1, true, false, false, false, true))))
        .isEqualTo("(quietly)");
    assertThat(fake.copies)
        .singleElement()
        .satisfies(copy -> assertThat(copy.items()).containsExactly(emerald));
    assertThat(decide(new Intent.UseBlock(alex.id(), IN_C1, true, false, false, false, true)))
        .isEqualTo(Decision.allow());
  }

  @Test
  void visitorsOpenDoorsAndUseNothingElse() {
    block(IN_C1, DOOR);
    assertThat(decide(new Intent.UseBlock(kim.id(), IN_C1, true, false, true, false, true)))
        .isEqualTo(Decision.allow());
    block(IN_C1, LEVER);
    assertThat(text(decide(new Intent.UseBlock(kim.id(), IN_C1, true, false, true, false, true))))
        .isEqualTo("You can't use that in the Hall of Fame.");
    assertThat(decide(new Intent.UseBlock(alex.id(), IN_C1, true, false, true, false, true)))
        .as("the owner uses anything in their cell while decorating")
        .isEqualTo(Decision.allow());
    block(IN_C1, FakePlatform.STONE);
    assertThat(text(decide(new Intent.UseBlock(kim.id(), IN_C1, true, false, true, false, false))))
        .as("a click on a plain block is refused quietly")
        .isEqualTo("(quietly)");
    container(IN_C1, FURNACE);
    assertThat(text(decide(new Intent.OpenContainer(kim.id(), IN_C1))))
        .isEqualTo("You can't use that in the Hall of Fame.");
  }

  @Test
  void entitiesAreDecorationOnlyForTheirDecoratingOwner() {
    assertThat(decide(new Intent.UseEntity(kim.id(), IN_C1, "minecraft:cushion")))
        .as("anyone sits on a cushion")
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.UseEntity(kim.id(), IN_C1, "minecraft:item_frame"))))
        .isEqualTo("You can't change anything in the Hall of Fame.");
    assertThat(decide(new Intent.UseEntity(alex.id(), IN_C1, "minecraft:item_frame")))
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.UseEntity(alex.id(), IN_C1, "minecraft:pig"))))
        .isEqualTo("You can't change anything in the Hall of Fame.");

    assertThat(text(decide(new Intent.PlaceEntity(kim.id(), IN_C1, "minecraft:cushion"))))
        .isEqualTo("You can't build here.");
    assertThat(decide(new Intent.PlaceEntity(alex.id(), IN_C1, "minecraft:cushion")))
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.HurtEntity(kim.id(), IN_C1, "minecraft:cushion"))))
        .isEqualTo("You can't change anything in the Hall of Fame.");
    assertThat(decide(new Intent.HurtEntity(alex.id(), IN_C1, "minecraft:armor_stand")))
        .isEqualTo(Decision.allow());

    run("/season decorate close");
    assertThat(text(decide(new Intent.PlaceEntity(alex.id(), IN_C1, "minecraft:cushion"))))
        .isEqualTo("You can't build here.");
    assertThat(text(decide(new Intent.HurtEntity(alex.id(), IN_C1, "minecraft:cushion"))))
        .isEqualTo("You can't change anything in the Hall of Fame.");
    assertThat(decide(new Intent.UseEntity(kim.id(), IN_C1, "minecraft:cushion")))
        .isEqualTo(Decision.allow());
  }

  @Test
  void noLavaAnywhereAndWaterOnlyForTheOwner() {
    assertThat(text(decide(new Intent.Bucket(alex.id(), IN_C1, "minecraft:lava_bucket"))))
        .isEqualTo("Lava can't go in the Hall of Fame.");
    assertThat(decide(new Intent.Bucket(alex.id(), IN_C1, "minecraft:water_bucket")))
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.Bucket(kim.id(), IN_C1, "minecraft:water_bucket"))))
        .isEqualTo("You can't build here.");
    assertThat(text(decide(new Intent.ChangeBlock(kim.id(), IN_C1, "minecraft:flint_and_steel"))))
        .isEqualTo("You can't build here.");
    assertThat(decide(new Intent.ChangeBlock(alex.id(), IN_C1, "minecraft:flint_and_steel")))
        .isEqualTo(Decision.allow());
  }

  @Test
  void bypassHoldersChangeAnythingAndAreAudited() throws IOException {
    fake.bypassing.add(kim.id());
    assertThat(decide(new Intent.PlaceBlock(kim.id(), IN_C1, "minecraft:hopper")))
        .isEqualTo(Decision.allow());
    assertThat(audited("hall-bypass"))
        .singleElement()
        .satisfies(entry -> {
          assertThat(entry.get("actor").getAsString()).isEqualTo("Kim");
          assertThat(entry.get("target").getAsString()).isEqualTo("season-2:c1");
          assertThat(entry.get("result").getAsString()).isEqualTo("allowed");
          assertThat(entry.get("details").getAsString())
              .isEqualTo("place minecraft:hopper at 5, 64, 5");
        });
  }

  @Test
  void refusalsAreAuditedOnceARunWithTheCountAfter() throws IOException {
    for (int i = 0; i < 5; i++) {
      decide(new Intent.BreakBlock(kim.id(), IN_C1));
      fake.clock.advance(Duration.ofSeconds(1));
    }
    decide(new Intent.PlaceBlock(kim.id(), CORRIDOR, "minecraft:stone"));
    assertThat(audited("hall-blocked"))
        .extracting(e -> e.get("target").getAsString() + " " + e.get("details").getAsString())
        .containsExactly(
            "season-2:c1 break minecraft:air at 5, 64, 5",
            "hall place minecraft:stone at 2, 64, 2");

    fake.clock.advance(Duration.ofSeconds(11));
    fake.tick();
    assertThat(audited("hall-blocked"))
        .extracting(e -> e.get("details").getAsString())
        .contains("break minecraft:air, 4 more times");
  }

  @Test
  void decoratingOwnersPickUpInTheirCellAndNobodyElseInTheHall() {
    fake.player(alex).position = IN_C1;
    fake.player(kim).position = IN_C1;
    assertThat(decide(new Intent.PickupItem(alex.id()))).isEqualTo(Decision.allow());
    assertThat(decide(new Intent.PickupItem(kim.id()))).isNotEqualTo(Decision.allow());
    run("/season decorate close");
    assertThat(decide(new Intent.PickupItem(alex.id()))).isNotEqualTo(Decision.allow());
  }

  @Test
  void afterTheirArchiveAnOwnerArrangesTheirCellButTakesNothingOut() {
    Optional<NeutralItem> diamonds = Optional.of(FakeItems.item("minecraft:diamond", 3));
    container(ALSO_IN_C1, CHEST, diamonds);
    run("/season end --now");
    fake.player(alex).position = IN_C1;
    assertThat(decide(new Intent.PlaceBlock(alex.id(), IN_C1, "minecraft:stone")))
        .as("the time after their archive: they still build")
        .isEqualTo(Decision.allow());
    assertThat(decide(new Intent.BreakBlock(alex.id(), IN_C1)))
        .as("but what they break drops nothing")
        .isEqualTo(Decision.allowWithoutDrops());
    assertThat(decide(new Intent.PickupItem(alex.id()))).isNotEqualTo(Decision.allow());

    assertThat(text(decide(new Intent.OpenContainer(alex.id(), ALSO_IN_C1))))
        .as("their stash opens as a copy")
        .isEqualTo("(quietly)");
    assertThat(fake.copies)
        .singleElement()
        .satisfies(copy -> assertThat(copy.items()).containsExactly(diamonds));
    block(IN_C1, LEVER);
    assertThat(text(decide(new Intent.UseBlock(alex.id(), IN_C1, true, false, true, false, true))))
        .isEqualTo("You can't use that in the Hall of Fame.");
    block(IN_C1, FakePlatform.STONE);
    assertThat(decide(new Intent.UseBlock(alex.id(), IN_C1, true, false, false, false, false)))
        .as("a click to build against a plain block")
        .isEqualTo(Decision.allow());

    assertThat(decide(new Intent.UseEntity(alex.id(), IN_C1, "minecraft:item_frame")))
        .as("a frame can still be turned or filled")
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.UseEntity(alex.id(), IN_C1, "minecraft:armor_stand"))))
        .isEqualTo("Your cell keeps what's in it now: nothing comes out of it.");
    assertThat(text(decide(new Intent.HurtEntity(alex.id(), IN_C1, "minecraft:item_frame"))))
        .isEqualTo("Your cell keeps what's in it now: nothing comes out of it.");
  }

  @Test
  void aDoubleChestNeverReachesAcrossACellOrTheHallsEdge() {
    Optional<NeutralItem> diamonds = Optional.of(FakeItems.item("minecraft:diamond", 3));
    container(IN_C1, CHEST, diamonds);
    container(ALSO_IN_C1, CHEST);
    container(IN_C2, CHEST);
    container(HALL_EDGE, CHEST, diamonds);
    container(JUST_OUTSIDE, CHEST);
    container(OUTSIDE, CHEST);

    assertThat(decide(new Intent.JoinContainers(alex.id(), List.of(IN_C1, ALSO_IN_C1))))
        .as("within their own cell")
        .isEqualTo(Decision.allow());
    assertThat(text(decide(new Intent.JoinContainers(alex.id(), List.of(IN_C1, IN_C2)))))
        .isEqualTo("A double chest can't reach across a cell's edge or the Hall's.");
    assertThat(text(decide(new Intent.JoinContainers(kim.id(), List.of(JUST_OUTSIDE, HALL_EDGE)))))
        .as("from outside the Hall")
        .isEqualTo("A double chest can't reach across a cell's edge or the Hall's.");
    assertThat(decide(new Intent.JoinContainers(kim.id(), List.of(OUTSIDE, JUST_OUTSIDE))))
        .as("outside the Hall nothing is decided")
        .isEqualTo(Decision.allow());

    // One already there, however it came: a half outside or in another cell opens a copy.
    assertThat(text(decide(new Intent.OpenContainer(kim.id(), List.of(JUST_OUTSIDE, HALL_EDGE)))))
        .isEqualTo("(quietly)");
    assertThat(text(decide(new Intent.OpenContainer(alex.id(), List.of(IN_C2, IN_C1)))))
        .isEqualTo("(quietly)");
    assertThat(decide(new Intent.OpenContainer(alex.id(), List.of(ALSO_IN_C1, IN_C1))))
        .isEqualTo(Decision.allow());
    assertThat(fake.copies).hasSize(2);
  }

  @Test
  void nothingCrossesIntoTheHallByItself() {
    HallGuard guard = fake.guard;
    assertThat(guard.protects(IN_C1)).isTrue();
    assertThat(guard.protects(CORRIDOR)).isTrue();
    assertThat(guard.protects(OUTSIDE)).isFalse();

    assertThat(guard.allows(List.of(OUTSIDE, JUST_OUTSIDE))).isTrue();
    assertThat(guard.allows(List.of(JUST_OUTSIDE, CORRIDOR))).as("into the Hall").isFalse();
    assertThat(guard.allows(List.of(CORRIDOR, CORRIDOR))).as("outside any cell").isFalse();
    assertThat(guard.allows(List.of(IN_C1, IN_C2))).as("from cell to cell").isFalse();
    assertThat(guard.allows(List.of(IN_C1, ALSO_IN_C1)))
        .as("within a cell being decorated")
        .isTrue();

    run("/season decorate close");
    assertThat(guard.allows(List.of(IN_C1, ALSO_IN_C1))).as("within a locked cell").isFalse();
  }

  @Test
  void reconciliationOnChunkLoadRedrawsAndRemovesAndAuditsEachRepair() throws IOException {
    run("/season end --now");
    assertThat(fake.displays.values()).anyMatch(d -> d.tag().equals("season-2/c1/2/figure"));
    java.util.UUID figure =
        fake.displays.entrySet().stream()
            .filter(e -> e.getValue().tag().equals("season-2/c1/2/figure"))
            .findFirst()
            .orElseThrow()
            .getKey();
    // The figure pushed out of its cell, though still in its chunk.
    FakePlatform.Drawn drawn = fake.displays.get(figure);
    fake.displays.put(
        figure,
        new FakePlatform.Drawn(drawn.tag(), BETWEEN_CELLS, drawn.equipment(), drawn.kind()));
    java.util.UUID stray = java.util.UUID.randomUUID();
    fake.displays.put(
        stray, new FakePlatform.Drawn("season-2/c1/7/plaque", IN_C1, List.of(), "text"));

    fake.guard.entitiesLoaded(OVERWORLD, 0, 0);
    fake.tick();
    fake.tick();

    assertThat(fake.displays.values())
        .filteredOn(d -> d.tag().equals("season-2/c1/2/figure"))
        .singleElement()
        .satisfies(d -> assertThat(d.equipment()).isEqualTo(drawn.equipment()));
    assertThat(fake.displays).doesNotContainKey(stray).doesNotContainKey(figure);
    assertThat(audited("reconcile"))
        .extracting(e -> e.get("target").getAsString() + ": " + e.get("details").getAsString())
        .contains(
            "season-2:c1: drew the missing figure",
            "season-2:c1: removed a stray display season-2/c1/2/figure",
            "season-2:c1: removed a stray display season-2/c1/7/plaque");

    // Once right, nothing more is done.
    int before = audited("reconcile").size();
    fake.guard.entitiesLoaded(OVERWORLD, 0, 0);
    fake.tick();
    fake.tick();
    assertThat(audited("reconcile")).hasSize(before);
  }

  @Test
  void aDisplayFarFromItsCellIsRemovedWhenItsChunkLoads() throws IOException {
    java.util.UUID far = java.util.UUID.randomUUID();
    Position away = Position.of(OVERWORLD, 100, 64, 5);
    fake.displays.put(
        far, new FakePlatform.Drawn("season-2/c1/2/figure", away, List.of(), "figure"));
    java.util.UUID unknown = java.util.UUID.randomUUID();
    fake.displays.put(
        unknown, new FakePlatform.Drawn("season-9/c1/9/figure", away, List.of(), "figure"));

    fake.guard.entitiesLoaded(OVERWORLD, 100 >> 4, 0);

    assertThat(fake.displays).doesNotContainKey(far);
    assertThat(fake.displays)
        .as("a display of a cell not known is kept, in case its gallery file comes back")
        .containsKey(unknown);
    assertThat(audited("reconcile"))
        .extracting(e -> e.get("details").getAsString())
        .contains("removed a stray display season-2/c1/2/figure");
  }

  @Test
  void reconciliationFixesAFiguresGear() throws IOException {
    run("/season end --now");
    Map.Entry<java.util.UUID, FakePlatform.Drawn> figure =
        fake.displays.entrySet().stream()
            .filter(e -> e.getValue().tag().equals("season-2/c1/2/figure"))
            .findFirst()
            .orElseThrow();
    List<Optional<NeutralItem>> worn = figure.getValue().equipment();
    List<Optional<NeutralItem>> changed = new ArrayList<>(worn);
    changed.set(1, Optional.of(FakeItems.item("minecraft:dirt", 1)));
    fake.displays.put(
        figure.getKey(),
        new FakePlatform.Drawn("season-2/c1/2/figure", figure.getValue().at(), changed, "figure"));

    fake.guard.entitiesLoaded(OVERWORLD, 0, 0);
    fake.tick();
    fake.tick();

    assertThat(fake.displays.values())
        .filteredOn(d -> d.tag().equals("season-2/c1/2/figure"))
        .singleElement()
        .satisfies(d -> assertThat(d.equipment()).isEqualTo(worn));
    assertThat(audited("reconcile"))
        .extracting(e -> e.get("details").getAsString())
        .contains("fixed the figure's gear");
  }

  @Test
  void reconciliationStaysWithinItsBudgetEachTick() {
    for (Position at : List.of(IN_C1, IN_C2, Position.of(OVERWORLD, 17, 64, 5))) {
      fake.displays.put(
          java.util.UUID.randomUUID(),
          new FakePlatform.Drawn("season-2/x/9/plaque", at, List.of(), "text"));
    }
    // Every look at the clock finds 0.6 ms gone: a tick does one step and stops.
    step = 600_000;
    fake.guard.entitiesLoaded(OVERWORLD, 0, 0);

    List<Integer> left = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      tickReconciler();
      left.add(fake.displays.size());
    }
    assertThat(left).as("strays left after each tick").containsExactly(3, 2, 2, 1, 1, 0);
  }

  /** One tick of reconciliation alone, with the clock moving 0.6 ms a look. */
  private void tickReconciler() {
    long start = nanos.get();
    fake.tick();
    assertThat(nanos.get() - start).isLessThanOrEqualTo(3 * 600_000);
  }

  private void writeGallery() throws IOException {
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    Files.writeString(
        galleries.resolve("season-2.yml"),
        """
        schema-version: 1
        id: season-2
        kind: season
        season: 2
        dimension: minecraft:overworld
        bounds: { min: [0, 60, 0], max: [30, 80, 20] }
        entry: { pos: [1, 64, 1] }
        cells:
          c1:
            bounds: { min: [4, 60, 4], max: [8, 70, 8] }
            anchors:
              - { type: figure, pos: [6, 64, 7] }
          c2:
            bounds: { min: [10, 60, 4], max: [14, 70, 8] }
            anchors:
              - { type: figure, pos: [12, 64, 7] }
          c3:
            bounds: { min: [16, 60, 4], max: [20, 70, 8] }
        """);
  }
}
