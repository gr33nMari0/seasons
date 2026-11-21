// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ArchiveSlot;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.curate.Curation;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Curation on the fake platform: the curator view, the four actions with their reasons, audit
 * entries, notices and webhook posts, rollbacks, undo and the recycle bin, and crash recovery at
 * each step.
 */
class CurationTest {
  private static final URI PUBLIC = URI.create("https://discord.example/public");
  private static final URI ADMIN = URI.create("https://discord.example/admin");
  private static final NeutralItem SWORD = FakeItems.item("minecraft:diamond_sword", 1);
  private static final NeutralItem DIRT = FakeItems.item("minecraft:dirt", 64);
  private static final NeutralItem TORCH = FakeItems.item("minecraft:torch", 32);
  private static final NeutralItem HELMET = FakeItems.item("minecraft:iron_helmet", 1);
  private static final ArchiveSlot SWORD_SLOT = ArchiveSlot.of(ArchiveSlot.STORAGE, 0);
  private static final ArchiveSlot HELMET_SLOT = ArchiveSlot.of(ArchiveSlot.ARMOUR, 3);
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
        core:
          bounds: { min: [210, -61, 302], max: [212, -55, 306] }
          anchors:
            - { type: trophy, pos: [211, -59, 305] }
      """;

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private ScenarioHooks hooks;
  private boolean halted;
  private final List<Post> posts = new ArrayList<>();

  private record Post(URI webhook, String content) {}

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {
          posts.add(new Post(webhook, content));
        }

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
    fake.imports = before.imports;
    start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  /** Pat archived into Season 2 with the gear; Peter, an op, online to curate; the museum. */
  private PlayerRef archived() throws IOException {
    Files.createDirectories(dir.resolve("plugins/Seasons"));
    Files.writeString(
        dir.resolve("plugins/Seasons/secrets.yml"),
        "schema-version: 1\ndiscordWebhook: \"" + PUBLIC + "\"\nadminWebhook: \"" + ADMIN + "\"\n");
    Files.createDirectories(dir.resolve("world/seasons/galleries"));
    Files.writeString(dir.resolve("world/seasons/galleries/museum.yml"), MUSEUM);
    start();
    PlayerRef pat = fake.join("Pat");
    fake.player(pat).inventory = FakeItems.gear();
    run("/season init 2");
    run("/season end --now");
    posts.clear();
    return pat;
  }

  private PlayerRef peter() {
    PlayerRef peter = fake.join("Peter");
    if (fake.sessions != null) {
      fake.sessions.joined(peter, true);
    }
    fake.player(peter).inventory = FakeItems.EMPTY;
    return peter;
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

  private List<String> said(PlayerRef player, int from) {
    return fake.sent.subList(from, fake.sent.size()).stream()
        .filter(sent -> sent.player().equals(player.id()))
        .map(sent -> TestSource.plain(sent.message()))
        .toList();
  }

  private Optional<ArchiveRecord> archive(PlayerRef player) throws IOException {
    return new ArchiveFiles(dir.resolve("world/seasons/data/archives")).read(player.id(), 2);
  }

  private List<Optional<NeutralItem>> returned(PlayerRef player) throws IOException {
    return new VaultFiles(dir.resolve("world/seasons/data/vaults")).read(player.id()).returned();
  }

  /** Opens Pat's archive, picks the item on page 1 at a slot, and chooses an action's icon. */
  private void pick(PlayerRef curator, int slot, String icon) {
    assertThat(as(curator, "/hof curate Pat 2"))
        .containsExactly(
            "Curating Pat's Season 2 archive. Click an item to choose what happens to it.");
    FakePlatform.Withdraw view = fake.withdraws.getLast();
    assertThat(Messages.plain(view.view().title())).isEqualTo("Curating Pat · Season 2");
    view.taken().clicked(0, slot);
    fake.choices.getLast().click(icon);
  }

  private List<String> to(URI webhook) {
    return posts.stream().filter(p -> p.webhook().equals(webhook)).map(Post::content).toList();
  }

  private List<String> audit() {
    return run("/season audit Pat");
  }

  @Test
  void theViewShowsTheArchiveAndAClickOffersTheActions() throws IOException {
    archived();
    PlayerRef peter = peter();

    as(peter, "/hof curate Pat 2");

    FakePlatform.Withdraw view = fake.withdraws.getLast();
    assertThat(view.view().pages().getFirst().get(36)).contains(SWORD);
    view.taken().clicked(0, 36);
    assertThat(Messages.plain(fake.choices.getLast().title()))
        .isEqualTo("1 × minecraft:diamond_sword: what happens to it?");
    assertThat(fake.choices.getLast().icons())
        .containsExactly(
            "minecraft:item_frame",
            "minecraft:ender_chest",
            "minecraft:hopper",
            "minecraft:lava_bucket",
            "minecraft:barrier");
    // An empty slot offers nothing.
    int opened = fake.choices.size();
    view.taken().clicked(0, 20);
    assertThat(fake.choices).hasSize(opened);
  }

  @Test
  void unknownPlayersAndSeasonsAreRefused() throws IOException {
    archived();
    PlayerRef peter = peter();

    assertThat(as(peter, "/hof curate Nobody 2"))
        .containsExactly("Nobody called Nobody has played here.");
    assertThat(as(peter, "/hof curate Pat 5")).containsExactly("Pat has no archive for Season 5.");
  }

  @Test
  void eachNodeOffersItsOwnActionAndViewAloneOffersNone() throws IOException {
    archived();
    PlayerRef peter = peter();
    fake.denied.put(
        peter.id(),
        new HashSet<>(
            Set.of(
                Curation.PERMISSION_MUSEUM,
                Curation.PERMISSION_TAKE,
                Curation.PERMISSION_DESTROY)));

    as(peter, "/hof curate Pat 2");
    fake.withdraws.getLast().taken().clicked(0, 36);
    assertThat(fake.choices.getLast().icons())
        .containsExactly("minecraft:ender_chest", "minecraft:barrier");

    fake.denied.get(peter.id()).add(Curation.PERMISSION_RETURN);
    int opened = fake.choices.size();
    int before = fake.sent.size();
    fake.withdraws.getLast().taken().clicked(0, 36);
    assertThat(fake.choices).hasSize(opened);
    assertThat(said(peter, before))
        .containsExactly("You may look at this archive, but not move anything out of it.");
  }

  @Test
  void everyActionNeedsAReasonAndCancellingLeavesTheItem() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    int before = fake.sent.size();

    pick(peter, 36, "minecraft:hopper");
    assertThat(fake.closed).contains(peter.id());
    fake.type(peter, "   ");
    fake.type(peter, "Cancel");

    assertThat(said(peter, before))
        .containsSubsequence(
            "Type the reason to take 1 × minecraft:diamond_sword in chat, or cancel. Nobody else"
                + " sees it.",
            "A reason is required. Type it in chat, or cancel.",
            "Cancelled: 1 × minecraft:diamond_sword stays in the archive.");
    assertThat(fake.taken).containsExactly("   ", "Cancel");
    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).contains(SWORD);
    // Once answered, chat is chat again.
    fake.type(peter, "hello");
    assertThat(fake.chatted).containsExactly("hello");
  }

  @Test
  void anUnansweredQuestionTimesOut() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    pick(peter, 36, "minecraft:hopper");
    int before = fake.sent.size();

    fake.runFor(Curation.PROMPT.plusSeconds(1));
    fake.type(peter, "Too late");

    assertThat(said(peter, before))
        .containsExactly(
            "No reason came in time, so 1 × minecraft:diamond_sword stays in the archive.");
    assertThat(fake.chatted).containsExactly("Too late");
    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).contains(SWORD);
  }

  @Test
  void returnPutsItOnTheReturnedPageAndEveryoneIsToldTheirPart() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    int before = fake.sent.size();

    pick(peter, 36, "minecraft:ender_chest");
    fake.type(peter, "Wrong season");

    assertThat(said(peter, before))
        .endsWith("Returned 1 × minecraft:diamond_sword to Pat's vault.");
    assertThat(said(pat, before))
        .containsExactly(
            "A curator returned 1 × minecraft:diamond_sword from your Season 2 archive to your"
                + " vault's Returned page: Wrong season");
    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).isEmpty();
    assertThat(returned(pat)).containsExactly(Optional.of(SWORD));
    assertThat(to(ADMIN))
        .containsExactly(
            "Curation: Peter returned 1 × minecraft:diamond_sword from Pat's Season 2 archive"
                + " (storage.0) to Pat's Returned page. Reason: Wrong season");
    assertThat(to(PUBLIC))
        .containsExactly(
            "1 × minecraft:diamond_sword from Pat's Season 2 archive was returned to them.");
    assertThat(audit())
        .anyMatch(line ->
            line.contains(
                    "Peter curate return Pat (ok): Season 2, storage.0, 1 × minecraft:diamond_sword"
                        + " to Pat's Returned page; reason: Wrong season; item: ")
                && line.length() > 150);
  }

  @Test
  void anOfflineOwnerIsToldAtTheirNextLogin() throws IOException {
    PlayerRef pat = archived();
    fake.leave(pat);
    PlayerRef peter = peter();

    pick(peter, 36, "minecraft:lava_bucket");
    fake.type(peter, "Broken");
    int before = fake.sent.size();
    PlayerRef back = fake.join("Pat");
    fake.sessions.joined(back, true);

    assertThat(said(back, before))
        .contains(
            "A curator removed 1 × minecraft:diamond_sword from your Season 2 archive: Broken");
  }

  @Test
  void takeGivesTheRealItemOrPutsItBackWhenTheInventoryIsFull() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    fake.player(peter).inventory =
        new InventorySnapshot(
            FakeItems.empty(36).stream()
                .map(slot -> Optional.of(FakeItems.item("minecraft:stone", 64)))
                .toList(),
            FakeItems.empty(4),
            Optional.empty(),
            FakeItems.empty(27));
    int before = fake.sent.size();

    pick(peter, 37, "minecraft:hopper");
    fake.type(peter, "Repairs");

    assertThat(said(peter, before))
        .endsWith("Your inventory is full, so 64 × minecraft:dirt stays in the archive.");
    assertThat(archive(pat).orElseThrow().item(ArchiveSlot.of(ArchiveSlot.STORAGE, 1)))
        .contains(DIRT);
    assertThat(audit()).anyMatch(line -> line.contains("curate take Pat (refused)"));

    fake.player(peter).inventory = FakeItems.EMPTY;
    pick(peter, 37, "minecraft:hopper");
    fake.type(peter, "Repairs");

    assertThat(fake.player(peter).inventory.storage()).contains(Optional.of(DIRT));
    assertThat(archive(pat).orElseThrow().item(ArchiveSlot.of(ArchiveSlot.STORAGE, 1))).isEmpty();
  }

  @Test
  void museumPutsItStraightOnAFreePlinthWithAPlaque() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    int before = fake.sent.size();

    pick(peter, 13, "minecraft:item_frame");
    FakePlatform.Choice plinths = fake.choices.getLast();
    // The egg plinth isn't offered.
    assertThat(plinths.options())
        .extracting(o -> Messages.plain(o.label()))
        .containsExactly("museum:core");
    plinths.click("minecraft:name_tag");
    fake.type(peter, "For the museum");

    assertThat(said(peter, before)).endsWith("Put 32 × minecraft:torch in museum:core.");
    assertThat(fake.items.values()).contains(TORCH);
    assertThat(fake.texts.values().stream().map(Messages::plain)).contains("Season 2 · Pat");
    assertThat(fake.player(peter).inventory).isEqualTo(FakeItems.EMPTY);
    assertThat(archive(pat).orElseThrow().item(ArchiveSlot.of(ArchiveSlot.STORAGE, 13))).isEmpty();
  }

  @Test
  void aPlinthTakenWhileTheReasonIsAskedForLeavesTheItem() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    pick(peter, 13, "minecraft:item_frame");
    fake.choices.getLast().click("minecraft:name_tag");
    hold(peter, FakeItems.item("minecraft:heavy_core", 1));
    as(peter, "/museum add museum:core");
    int before = fake.sent.size();

    fake.type(peter, "Too late");

    assertThat(said(peter, before))
        .containsExactly(
            "museum:core isn't free any more, so 32 × minecraft:torch stays in the archive.");
    assertThat(archive(pat).orElseThrow().item(ArchiveSlot.of(ArchiveSlot.STORAGE, 13)))
        .contains(TORCH);
  }

  @Test
  void destroyKeepsItThirtyDaysAndUndoPutsItBack() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    int before = fake.sent.size();

    pick(peter, 0, "minecraft:lava_bucket");
    fake.type(peter, "Broken");
    String done = said(peter, before).getLast();
    assertThat(done)
        .matches(
            "Destroyed 1 × minecraft:iron_helmet\\. /hof curate undo \\S+ brings it back within 30"
                + " days\\.");
    String id = done.replaceAll(".*undo (\\S+) brings.*", "$1");
    assertThat(archive(pat).orElseThrow().item(HELMET_SLOT)).isEmpty();
    assertThat(archive(pat).orElseThrow().figureEquipment().getFirst()).isEmpty();

    assertThat(as(peter, "/hof curate undo nothing"))
        .containsExactly("There is nothing in the recycle bin with id nothing.");
    assertThat(as(peter, "/hof curate undo " + id))
        .containsExactly("Put 1 × minecraft:iron_helmet back in Pat's Season 2 archive.");
    assertThat(archive(pat).orElseThrow().item(HELMET_SLOT)).contains(HELMET);
    assertThat(as(peter, "/hof curate undo " + id))
        .containsExactly("There is nothing in the recycle bin with id " + id + ".");
    assertThat(audit())
        .anyMatch(line ->
            line.contains(
                "curate undo Pat (ok): Season 2, armour.3, 1 × minecraft:iron_helmet back from the recycle bin"));
    assertThat(to(ADMIN))
        .anyMatch(post -> post.startsWith("Curation: Peter put 1 × minecraft:iron_helmet back"));
  }

  @Test
  void theRecycleBinLetsGoAfterThirtyDays() throws IOException {
    archived();
    PlayerRef peter = peter();
    int before = fake.sent.size();
    pick(peter, 0, "minecraft:lava_bucket");
    fake.type(peter, "Broken");
    String id = said(peter, before).getLast().replaceAll(".*undo (\\S+) brings.*", "$1");

    fake.clock.advance(Curation.KEEP.plusMinutes(1));

    assertThat(as(peter, "/hof curate undo " + id))
        .containsExactly("There is nothing in the recycle bin with id " + id + ".");
    assertThat(audit()).anyMatch(line -> line.contains("curate purge Pat (ok)"));
    assertThat(Files.exists(dir.resolve("world/seasons/data/recycle/" + id + ".dat"))).isFalse();
  }

  @Test
  void withoutARequiredReasonChoosingIsEnough() throws IOException {
    PlayerRef pat = archived();
    Files.writeString(
        dir.resolve("plugins/Seasons/config.yml"),
        "schema-version: 1\ncurate:\n  requireReason: false\n");
    run("/season reload");
    PlayerRef peter = peter();

    pick(peter, 36, "minecraft:hopper");

    assertThat(fake.player(peter).inventory.storage()).contains(Optional.of(SWORD));
    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).isEmpty();
    assertThat(audit()).anyMatch(line -> line.contains("reason: no reason given"));
  }

  @Test
  void actionsOneAfterAnotherEachRun() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();

    pick(peter, 36, "minecraft:hopper");
    fake.type(peter, "One");
    pick(peter, 37, "minecraft:hopper");
    fake.type(peter, "Two");

    assertThat(fake.player(peter).inventory.storage())
        .contains(Optional.of(SWORD), Optional.of(DIRT));
    ArchiveRecord after = archive(pat).orElseThrow();
    assertThat(after.item(SWORD_SLOT)).isEmpty();
    assertThat(after.item(ArchiveSlot.of(ArchiveSlot.STORAGE, 1))).isEmpty();
  }

  @Test
  void aSlotThatChangedSinceTheViewOpenedMovesNothing() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    as(peter, "/hof curate Pat 2");
    FakePlatform.Withdraw stale = fake.withdraws.getLast();
    pick(peter, 36, "minecraft:hopper");
    fake.type(peter, "First");
    int before = fake.sent.size();

    stale.taken().clicked(0, 36);
    fake.choices.getLast().click("minecraft:ender_chest");
    fake.type(peter, "Second");

    assertThat(said(peter, before))
        .endsWith(
            "That slot changed since the view opened, so nothing moved. Open the archive again.");
    assertThat(returned(pat)).isEmpty();
  }

  @Test
  void sneakClickingAFigureOpensTheCuratorViewAndAPlainClickTheArchiveView() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    Files.writeString(
        dir.resolve("world/seasons/galleries/season-2.yml"),
        """
        schema-version: 1
        id: season-2
        kind: season
        season: 2
        dimension: minecraft:overworld
        bounds: { min: [0, -64, 0], max: [10, -50, 10] }
        cells:
          c1:
            bounds: { min: [1, -64, 1], max: [9, -50, 9] }
            owners: { "2": %s }
            anchors:
              - { type: figure, pos: [3, -60, 3] }
        """
            .formatted(pat.id()));
    run("/season reload");
    int views = fake.withdraws.size();
    int archiveViews = fake.opened.size();

    fake.handler.decide(new Intent.UseDisplay(peter.id(), "season-2/c1/0/figure", true));
    assertThat(fake.withdraws).hasSize(views + 1);
    assertThat(Messages.plain(fake.withdraws.getLast().view().title()))
        .isEqualTo("Curating Pat · Season 2");

    fake.handler.decide(new Intent.UseDisplay(peter.id(), "season-2/c1/0/figure", false));
    assertThat(fake.opened).hasSize(archiveViews + 1);

    fake.denied.put(peter.id(), Set.of(Curation.PERMISSION_VIEW));
    fake.handler.decide(new Intent.UseDisplay(peter.id(), "season-2/c1/0/figure", true));
    assertThat(fake.opened).hasSize(archiveViews + 2);
    assertThat(fake.withdraws).hasSize(views + 1);
  }

  // Crash recovery: the item exactly once, in the archive or where it went.

  @Test
  void aTakeStoppedOnceJournalledLeavesTheItemInTheArchive() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    pick(peter, 36, "minecraft:hopper");
    hooks.arm(CrashPoint.CURATE_JOURNALLED, peter.id());

    fake.type(peter, "Crash");
    assertThat(halted).isTrue();
    restart();
    PlayerRef back = peter();

    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).contains(SWORD);
    assertThat(fake.player(back).inventory.storage()).doesNotContain(Optional.of(SWORD));
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void aTakeStoppedOnceRemovedPutsTheItemBack() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    pick(peter, 36, "minecraft:hopper");
    hooks.arm(CrashPoint.CURATE_REMOVED, peter.id());

    fake.type(peter, "Crash");
    assertThat(halted).isTrue();
    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).isEmpty();
    restart();
    // Recovery waits for the curator, whose saved inventory says whether it moved.
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 1 waiting for recovery"));
    peter();

    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).contains(SWORD);
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
    assertThat(audit()).anyMatch(line -> line.contains("curate take Pat (recovered)"));
  }

  @Test
  void aTakeStoppedOnceDeliveredStands() throws IOException {
    PlayerRef pat = archived();
    PlayerRef peter = peter();
    pick(peter, 36, "minecraft:hopper");
    hooks.arm(CrashPoint.CURATE_DELIVERED, peter.id());

    fake.type(peter, "Crash");
    assertThat(halted).isTrue();
    restart();
    PlayerRef back = fake.join("Peter");
    fake.sessions.joined(back, true);

    assertThat(fake.player(back).inventory.storage()).contains(Optional.of(SWORD));
    assertThat(archive(pat).orElseThrow().item(SWORD_SLOT)).isEmpty();
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void eachActionStoppedPartWaySettlesWithTheItemExactlyOnce() throws IOException {
    for (String icon :
        List.of("minecraft:ender_chest", "minecraft:lava_bucket", "minecraft:item_frame")) {
      for (CrashPoint point :
          List.of(
              CrashPoint.CURATE_JOURNALLED,
              CrashPoint.CURATE_REMOVED,
              CrashPoint.CURATE_DELIVERED)) {
        if (seasons != null) {
          seasons.stop();
        }
        fake = new FakePlatform();
        posts.clear();
        for (Path p : List.of(dir.resolve("world"), dir.resolve("plugins"))) {
          deleteAll(p);
        }
        PlayerRef pat = archived();
        PlayerRef peter = peter();
        pick(peter, 36, icon);
        if (icon.equals("minecraft:item_frame")) {
          fake.choices.getLast().click("minecraft:name_tag");
        }
        hooks.arm(point, peter.id());
        fake.type(peter, "Crash");
        assertThat(halted).as(icon + " " + point).isTrue();
        restart();

        boolean inArchive = archive(pat).orElseThrow().item(SWORD_SLOT).isPresent();
        boolean arrived =
            switch (icon) {
              case "minecraft:ender_chest" -> returned(pat).contains(Optional.of(SWORD));
              case "minecraft:lava_bucket" -> {
                try (var bin = Files.list(dir.resolve("world/seasons/data/recycle"))) {
                  yield bin.findAny().isPresent();
                } catch (java.nio.file.NoSuchFileException e) {
                  yield false;
                }
              }
              default -> Files.exists(dir.resolve("world/seasons/data/museum/museum.core.dat"));
            };
        assertThat(inArchive != arrived).as(icon + " " + point + ": exactly once").isTrue();
        assertThat(inArchive)
            .as(icon + " " + point)
            .isEqualTo(point != CrashPoint.CURATE_DELIVERED);
        assertThat(run("/season status"))
            .as(icon + " " + point)
            .anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
      }
    }
  }

  // Imported records and /hof import undo (review of #24)

  private static final NeutralItem DIAMONDS = FakeItems.item("minecraft:diamond", 3);
  private static final ArchiveSlot CHEST_SLOT = new ArchiveSlot(ArchiveSlot.CONTAINER, 0, 0);
  private static final String UNDO_BUSY =
      "Undoing the import of cell veteran stopped: its owner's record is busy: being archived,"
          + " unarchived or curated, or a curation is waiting for recovery.";

  /** A Season 1 cell of Veteran's, one chest of diamonds, imported into its record. */
  private ArchivingTest.OneChest imported() throws IOException {
    ArchivingTest.OneChest chest = new ArchivingTest.OneChest();
    fake.imports = chest;
    Path galleries = dir.resolve("world/seasons/galleries");
    Files.createDirectories(galleries);
    String veteran =
        java.util
            .UUID
            .nameUUIDFromBytes("Veteran".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    Files.writeString(
        galleries.resolve("season-1.yml"),
        """
        schema-version: 1
        id: season-1
        kind: season
        season: 1
        dimension: minecraft:overworld
        bounds: { min: [0, -64, 0], max: [10, -50, 10] }
        cells:
          veteran:
            bounds: { min: [1, -64, 1], max: [9, -50, 9] }
            owners: { "1": %s }
        """
            .formatted(veteran));
    start();
    run("/season init 2");
    fake.join("Veteran");
    assertThat(hof("/hof import season-1"))
        .containsExactly("Imported 1 cell from season-1; 0 skipped.");
    assertThat(chest.slots).containsOnly(Optional.empty());
    return chest;
  }

  private List<String> hof(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(
        fake.commands.stream().filter(c -> c.name().equals("hof")).findFirst().orElseThrow(),
        source,
        command);
    return source.text();
  }

  private Optional<ArchiveRecord> veteranRecord() throws IOException {
    return new ArchiveFiles(dir.resolve("world/seasons/data/archives"))
        .read(fake.join("Veteran").id(), 1);
  }

  /** Opens Veteran's imported record, picks the chest's diamonds and chooses Take. */
  private void pickDiamonds(PlayerRef curator) {
    assertThat(as(curator, "/hof curate Veteran 1"))
        .containsExactly(
            "Curating Veteran's Season 1 archive. Click an item to choose what happens to it.");
    fake.withdraws.getLast().taken().clicked(0, 0);
    fake.choices.getLast().click("minecraft:hopper");
  }

  @Test
  void anImportUndoIsRefusedWhileACurationOfTheRecordRuns() throws IOException {
    ArchivingTest.OneChest chest = imported();
    PlayerRef peter = peter();
    pickDiamonds(peter);
    List<String> undo = new ArrayList<>();
    // The undo comes in while the curation holds the record, between journalling and removing.
    hooks.onSave(() -> undo.addAll(hof("/hof import undo season-1")));
    hooks.armSave(CrashPoint.CURATE_JOURNALLED, peter.id());

    fake.type(peter, "For the curator");

    assertThat(undo).containsExactly(UNDO_BUSY);
    assertThat(fake.player(peter).inventory.storage()).contains(Optional.of(DIAMONDS));
    assertThat(veteranRecord().orElseThrow().item(CHEST_SLOT)).isEmpty();

    // Once it is done, the undo puts back what is left: the diamonds exist once, with Peter.
    assertThat(hof("/hof import undo season-1")).containsExactly("Put back 1 cell of season-1.");
    assertThat(chest.slots).containsOnly(Optional.empty());
  }

  @Test
  void anImportUndoIsRefusedWhileACrashLeftACurationOfTheRecordOpen() throws IOException {
    ArchivingTest.OneChest chest = imported();
    PlayerRef peter = peter();
    pickDiamonds(peter);
    hooks.arm(CrashPoint.CURATE_REMOVED, peter.id());
    fake.type(peter, "Crash");
    assertThat(halted).isTrue();
    restart();

    // The Take waits for Peter; the record no longer holds the diamonds, and the copy kept aside
    // does. An undo now would leave the record with nothing to put back, then recovery would
    // have no record to return the diamonds to.
    assertThat(hof("/hof import undo season-1")).containsExactly(UNDO_BUSY);
    assertThat(chest.slots).containsOnly(Optional.empty());
    assertThat(veteranRecord()).isPresent();

    peter();
    assertThat(veteranRecord().orElseThrow().item(CHEST_SLOT)).contains(DIAMONDS);
    assertThat(hof("/hof import undo season-1")).containsExactly("Put back 1 cell of season-1.");
    assertThat(chest.slots).containsExactly(Optional.of(DIAMONDS));
  }

  private void hold(PlayerRef player, NeutralItem item) {
    List<Optional<NeutralItem>> storage = FakeItems.empty(36);
    storage.set(0, Optional.of(item));
    fake.player(player).inventory =
        new InventorySnapshot(storage, FakeItems.empty(4), Optional.empty(), FakeItems.empty(27));
  }

  private static void deleteAll(Path path) throws IOException {
    if (Files.notExists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
