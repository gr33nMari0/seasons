// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.DragonEvents;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.season.DragonState;
import com.petepeterepeat.seasons.core.season.DragonState.Egg;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.SeasonStore;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dragon egg every season, on the fake platform: the slayer, the fanfare, the egg handed over
 * through the vault, the flag at launch, and a crash at each step of the hand-over.
 */
class DragonTest {
  private static final NeutralItem EGG = FakeItems.item("minecraft:dragon_egg", 1);
  private static final UUID DRAGON = UUID.randomUUID();

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private boolean halted;
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

  private ScenarioHooks hooks;

  private void start() {
    halted = false;
    try {
      java.nio.file.Files.createDirectories(dir.resolve("plugins/Seasons"));
      java.nio.file.Files.writeString(
          dir.resolve("plugins/Seasons/secrets.yml"),
          "schema-version: 1\ndiscordWebhook: \"https://discord.example/webhook\"\n");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
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
    fake.eggs.addAll(before.eggs);
    fake.dragonKilled = before.dragonKilled;
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

  /** Season 2 recorded and ended, Season 3 launched, with the End's flag set by an earlier kill. */
  private void launched() {
    start();
    run("/season init 2");
    run("/season end --now");
    PlayerRef admin = fake.join("Admin");
    fake.player(admin).position = Position.of("minecraft:overworld", 500, -60, 300);
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(admin.id())), "/season spawn set");
    fake.leave(admin);
    fake.dragonKilled = true;
    run("/season launch");
  }

  private DragonEvents dragon() {
    return java.util.Objects.requireNonNull(fake.dragonEvents);
  }

  /** The season state as saved, which is as it stands: test writes happen at once. */
  private SeasonState season() {
    try {
      return new SeasonStore(
              dir.resolve("world/seasons/data/season.json"), new SerialWriter(Runnable::run))
          .load()
          .orElseThrow();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private DragonState state() {
    return season().dragon();
  }

  private List<String> broadcasts() {
    return fake.broadcasts.stream().map(Messages::plain).toList();
  }

  private List<String> said(PlayerRef player) {
    return fake.sent.stream()
        .filter(sent -> sent.player().equals(player.id()))
        .map(sent -> Messages.plain(sent.message()))
        .toList();
  }

  private List<String> titles(PlayerRef player) {
    return fake.player(player).titles.stream().map(Messages::plain).toList();
  }

  private long eggsOn(PlayerRef player) {
    return fake.player(player).inventory.storage().stream()
        .flatMap(Optional::stream)
        .filter(EGG::equals)
        .count();
  }

  private List<Optional<NeutralItem>> returned(PlayerRef player) throws IOException {
    return new VaultFiles(dir.resolve("world/seasons/data/vaults")).read(player.id()).returned();
  }

  private Optional<SeasonFirst> dragonFirst() {
    return season().first(FirstsTracker.DRAGON);
  }

  private void full(PlayerRef player) {
    fake.player(player).inventory =
        new InventorySnapshot(
            FakeItems.empty(36).stream()
                .map(slot -> Optional.of(FakeItems.item("minecraft:stone", 64)))
                .toList(),
            FakeItems.empty(4),
            Optional.empty(),
            FakeItems.empty(27));
  }

  /** The egg appears on the portal and the watch notices it at its next check. */
  private Position eggAppears() {
    Position at = fake.dropEgg();
    fake.tick();
    return at;
  }

  @Test
  void launchClearsThePreviouslyKilledFlag() {
    launched();

    assertThat(fake.dragonKilled).isFalse();
    assertThat(state().flagPending()).isFalse();
  }

  @Test
  void withoutANewEggEachSeasonLaunchLeavesTheFlag() {
    start();
    run("/season config set dragon.newEggEachSeason false");
    run("/season init 2");
    run("/season end --now");
    PlayerRef admin = fake.join("Admin");
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(admin.id())), "/season spawn set");
    fake.leave(admin);
    fake.dragonKilled = true;
    run("/season launch");

    assertThat(fake.dragonKilled).isTrue();
  }

  @Test
  void anUnloadedEndGetsTheFlagClearedWhenItLoads() {
    start();
    run("/season init 2");
    run("/season end --now");
    PlayerRef admin = fake.join("Admin");
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(admin.id())), "/season spawn set");
    fake.endLoaded = false;
    run("/season launch");

    assertThat(state().flagPending()).isTrue();
    assertThat(said(admin))
        .contains(
            "The End isn't loaded, so the dragon's previously-killed flag will be cleared when it"
                + " loads.");

    fake.endLoaded = true;
    dragon().endLoaded();
    assertThat(fake.dragonKilled).isFalse();
    assertThat(state().flagPending()).isFalse();
  }

  @Test
  void theFinalBlowMakesTheSlayerOverMoreDamage() {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef sam = fake.join("Sam");

    dragon().hurt(DRAGON, sam.id(), 153);
    dragon().hurt(DRAGON, pat.id(), 2);
    dragon().died(DRAGON, Optional.of(pat.id()));

    assertThat(state().kill().orElseThrow().slayer()).contains(pat.id());
    assertThat(broadcasts()).contains("Pat slew the ender dragon!");
    assertThat(posts).containsExactly("Pat slew the ender dragon in Season 3!");
    assertThat(titles(sam)).contains("The dragon is slain!");
    assertThat(titles(pat)).contains("The dragon is slain!");
    assertThat(fake.fireworks)
        .filteredOn(at -> at.dimension().equals("minecraft:the_end"))
        .hasSize(5)
        .allMatch(at -> at.y() == 77);
    assertThat(dragonFirst()).map(SeasonFirst::name).contains("Pat");
    assertThat(run("/season firsts"))
        .anyMatch(line -> line.startsWith("Pat slew the ender dragon ("));
  }

  @Test
  void withNoPlayerFinalBlowTheMostDamageMakesTheSlayer() {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef sam = fake.join("Sam");

    dragon().hurt(DRAGON, pat.id(), 51);
    dragon().hurt(DRAGON, sam.id(), 51);
    dragon().hurt(DRAGON, sam.id(), 51);
    dragon().died(DRAGON, Optional.empty());

    assertThat(state().kill().orElseThrow().slayer()).contains(sam.id());
    assertThat(broadcasts()).contains("Sam slew the ender dragon!");
  }

  @Test
  void aTieGoesToWhoeverHitFirst() {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef sam = fake.join("Sam");

    dragon().hurt(DRAGON, sam.id(), 40);
    dragon().hurt(DRAGON, pat.id(), 40);
    dragon().died(DRAGON, Optional.empty());

    assertThat(state().kill().orElseThrow().slayer()).contains(sam.id());
  }

  @Test
  void damageToAnotherDragonDoesNotCount() {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef sam = fake.join("Sam");

    dragon().hurt(UUID.randomUUID(), pat.id(), 200);
    dragon().hurt(DRAGON, sam.id(), 10);
    dragon().died(DRAGON, Optional.empty());

    assertThat(state().kill().orElseThrow().slayer()).contains(sam.id());
  }

  @Test
  void aDragonNobodyHurtLeavesItsEggOnThePortalUnannounced() {
    launched();
    fake.dragonKilled = false;
    fake.join("Pat");

    dragon().died(DRAGON, Optional.empty());
    fake.dropEgg();
    fake.tick();

    assertThat(state().kill().orElseThrow().slayer()).isEmpty();
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.PORTAL);
    assertThat(fake.eggs).hasSize(1);
    assertThat(broadcasts()).noneMatch(line -> line.contains("slew"));
    assertThat(posts).isEmpty();
    assertThat(dragonFirst()).isEmpty();
  }

  @Test
  void onlyTheSeasonsFirstKillCounts() {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef sam = fake.join("Sam");
    dragon().died(DRAGON, Optional.of(pat.id()));
    int heard = fake.broadcasts.size();

    UUID second = UUID.randomUUID();
    dragon().hurt(second, sam.id(), 200);
    dragon().died(second, Optional.of(sam.id()));

    assertThat(state().kill().orElseThrow().slayer()).contains(pat.id());
    assertThat(state().fight()).isEmpty();
    assertThat(fake.broadcasts).hasSize(heard);
    assertThat(posts).hasSize(1);
  }

  @Test
  void nothingCountsOutsideTheSeasonsPlay() {
    start();
    run("/season init 2");
    run("/season end --now");
    PlayerRef pat = fake.join("Pat");

    dragon().hurt(DRAGON, pat.id(), 200);
    dragon().died(DRAGON, Optional.of(pat.id()));

    assertThat(state()).isEqualTo(DragonState.NONE);
    assertThat(broadcasts()).noneMatch(line -> line.contains("slew"));
  }

  @Test
  void theEggIsTakenOffThePortalAndGivenToTheSlayer() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.WAITING);

    Position egg = eggAppears();

    assertThat(fake.eggs).isEmpty();
    assertThat(fake.eggsRemoved).containsExactly(egg);
    assertThat(eggsOn(pat)).isEqualTo(1);
    assertThat(said(pat)).contains("The dragon egg is yours.");
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.GIVEN);
    assertThat(returned(pat)).allMatch(Optional::isEmpty);
    // The journal had the hand-over before the egg left the portal.
    List<JournalKind> kinds = journal().stream().map(e -> e.kind()).toList();
    assertThat(kinds).containsSubsequence(JournalKind.EGG_DELIVERED, JournalKind.VAULT_WITHDRAW);
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void anEggAlreadyOnThePortalIsLeftAlone() {
    launched();
    PlayerRef pat = fake.join("Pat");
    Position old = fake.dropEgg();
    dragon().died(DRAGON, Optional.of(pat.id()));

    fake.tick();
    assertThat(fake.eggsRemoved).isEmpty();
    Position egg = eggAppears();

    assertThat(fake.eggs).containsExactly(old);
    assertThat(fake.eggsRemoved).containsExactly(egg);
    assertThat(eggsOn(pat)).isEqualTo(1);
  }

  @Test
  void aFullInventorySendsTheEggToTheReturnedPage() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    full(pat);
    dragon().died(DRAGON, Optional.of(pat.id()));

    eggAppears();

    assertThat(said(pat))
        .contains(
            "Your inventory is full, so the dragon egg waits on your vault's Returned page:"
                + " /vault");
    assertThat(returned(pat)).containsExactly(Optional.of(EGG));
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.RETURNED);
    assertThat(fake.eggs).isEmpty();
  }

  @Test
  void aSlayerWhoLeftFindsTheEggOnTheReturnedPage() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    fake.leave(pat);

    eggAppears();

    assertThat(returned(pat)).containsExactly(Optional.of(EGG));
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.RETURNED);
  }

  @Test
  void withEggToPortalTheEggStaysButTheSlayerIsAnnounced() {
    launched();
    run("/season config set dragon.eggTo portal");
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));

    eggAppears();

    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.PORTAL);
    assertThat(fake.eggs).hasSize(1);
    assertThat(eggsOn(pat)).isZero();
    assertThat(broadcasts()).contains("Pat slew the ender dragon!");
  }

  @Test
  void aKillWithTheFlagSetDropsNoEggButIsAnnounced() {
    launched();
    fake.dragonKilled = true;
    PlayerRef pat = fake.join("Pat");

    dragon().died(DRAGON, Optional.of(pat.id()));

    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.NONE);
    assertThat(broadcasts()).contains("Pat slew the ender dragon!");
    assertThat(fake.repeating).hasSize(repeatingBefore());
  }

  /** The repeating tasks Seasons always runs, without a watch on the portal. */
  private int repeatingBefore() {
    FakePlatform other = new FakePlatform();
    FakePlatform was = fake;
    fake = other;
    try {
      launched();
      int count = fake.repeating.size();
      seasons.stop();
      return count;
    } finally {
      fake = was;
    }
  }

  @Test
  void anEggThatNeverAppearsIsReported() {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef admin = fake.join("Admin");
    dragon().died(DRAGON, Optional.of(pat.id()));

    for (int i = 0; i < 30; i++) {
      fake.tick();
    }

    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.MISSING);
    assertThat(said(admin))
        .contains(
            "No dragon egg appeared on the exit portal within 30 seconds of Season 3's first kill,"
                + " so Pat was given none.");
    fake.dropEgg();
    fake.tick();
    assertThat(fake.eggsRemoved).isEmpty();
  }

  @Test
  void theDamageDealtSurvivesARestart() {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef sam = fake.join("Sam");
    dragon().hurt(DRAGON, pat.id(), 51);
    dragon().hurt(DRAGON, sam.id(), 102);

    restart();
    dragon().died(DRAGON, Optional.empty());

    assertThat(state().kill().orElseThrow().slayer()).contains(sam.id());
  }

  @Test
  void aCrashWithTheEggOffThePortalPutsItOnTheReturnedPage() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    hooks.arm(CrashPoint.EGG_CLEARED, pat.id());

    crash(this::eggAppears);
    assertThat(fake.eggs).isEmpty();
    fake.leave(pat);
    restart();

    assertThat(returned(pat)).containsExactly(Optional.of(EGG));
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.RETURNED);
    assertThat(eggsOn(pat)).isZero();
  }

  @Test
  void aCrashBeforeTheEggLeftThePortalTakesItAtStartup() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    hooks.arm(CrashPoint.EGG_CLEARED, pat.id());
    Position egg = fake.dropEgg();

    crash(fake::tick);
    // The world wasn't saved: after the restart the egg is back on the portal.
    fake.eggs.add(egg);
    fake.leave(pat);
    restart();

    assertThat(fake.eggs).isEmpty();
    assertThat(returned(pat)).containsExactly(Optional.of(EGG));
  }

  @Test
  void aCrashWithTheEggOnTheReturnedPageLeavesItThere() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    hooks.arm(CrashPoint.EGG_RETURNED, pat.id());

    crash(this::eggAppears);
    fake.leave(pat);
    restart();

    assertThat(returned(pat)).containsExactly(Optional.of(EGG));
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.RETURNED);
  }

  @Test
  void theEggIsGuardedFromTheKillUntilItIsTaken() {
    launched();
    PlayerRef pat = fake.join("Pat");
    assertThat(fake.eggGuarded).isFalse();

    dragon().died(DRAGON, Optional.of(pat.id()));
    assertThat(fake.eggGuarded).isTrue();
    eggAppears();

    assertThat(fake.eggGuarded).isFalse();
    assertThat(eggsOn(pat)).isEqualTo(1);
  }

  @Test
  void anEggThatLeftThePortalBeforeItWasTakenGivesNone() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    PlayerRef admin = fake.join("Admin");
    dragon().died(DRAGON, Optional.of(pat.id()));
    // Moved between being seen and being taken, as a click would teleport it.
    fake.beforeEggRemoved = fake.eggs::clear;

    eggAppears();

    assertThat(eggsOn(pat)).isZero();
    assertThat(returned(pat)).allMatch(Optional::isEmpty);
    assertThat(state().kill().orElseThrow().egg()).isEqualTo(Egg.MISSING);
    assertThat(fake.eggGuarded).isFalse();
    assertThat(said(admin))
        .contains(
            "Season 3's dragon egg left the exit portal before it could be taken, so Pat was given"
                + " none. It is still in the End.");
  }

  @Test
  void aCrashThatRollsTheEndBackIsPutRightAtStartup() throws IOException {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    Position egg = eggAppears();
    assertThat(eggsOn(pat)).isEqualTo(1);

    // A crash before the End was saved: the egg is back on the portal and the flag clear again.
    halted = true;
    fake.eggs.add(egg);
    fake.dragonKilled = false;
    restart();

    assertThat(fake.eggs).isEmpty();
    assertThat(fake.dragonKilled).isTrue();
    assertThat(eggsOn(pat)).isEqualTo(1);
    assertThat(returned(pat)).allMatch(Optional::isEmpty);
    assertThat(state().kill().orElseThrow().eggAt()).isEmpty();
  }

  @Test
  void onceTheEndIsSavedAnEggPutOnThePortalIsLeftAlone() {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    Position egg = eggAppears();
    assertThat(state().kill().orElseThrow().eggAt()).contains(egg);

    dragon().endSaved();
    assertThat(state().kill().orElseThrow().eggAt()).isEmpty();
    // Pat puts the egg back on the portal as a display, and the server restarts.
    fake.eggs.add(egg);
    restart();

    assertThat(fake.eggs).containsExactly(egg);
  }

  @Test
  void aCleanStopCountsAsTheEndSaved() {
    launched();
    PlayerRef pat = fake.join("Pat");
    dragon().died(DRAGON, Optional.of(pat.id()));
    Position egg = eggAppears();

    fake.eggs.add(egg);
    restart();

    assertThat(fake.eggs).containsExactly(egg);
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

  private List<com.petepeterepeat.seasons.core.store.JournalEntry> journal() {
    try {
      return Journal.open(
              dir.resolve("world/seasons/data/journal.log"),
              new SerialWriter(Runnable::run),
              Clock.systemUTC())
          .entries();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
