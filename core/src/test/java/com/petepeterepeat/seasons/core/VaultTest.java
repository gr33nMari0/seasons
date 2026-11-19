// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.command.CommandNode;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The ender vault on the fake platform: the real vault, journal, files and commands. */
class VaultTest {
  private static final NeutralItem EMERALDS = FakeItems.item("minecraft:emerald", 5);

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;
  private ScenarioHooks hooks;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  private void start() {
    hooks =
        TestHooks.of(() -> {
          throw new ArchivingTest.Halted();
        });
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            Map.of(),
            new SerialWriter(Runnable::run),
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

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private List<String> vault(PlayerRef player) {
    int before = fake.sent.size();
    CommandNode root =
        fake.commands.stream().filter(c -> c.name().equals("vault")).findFirst().orElseThrow();
    TestSource source = new TestSource(Optional.of(player.id()));
    TestCommands.run(root, source, "/vault");
    return said(player, before);
  }

  private List<String> said(PlayerRef player, int from) {
    return fake.sent.subList(from, fake.sent.size()).stream()
        .filter(sent -> sent.player().equals(player.id()))
        .map(sent -> TestSource.plain(sent.message()))
        .toList();
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    if (fake.sessions != null) {
      fake.sessions.joined(player, fake.player(player).saves > 0);
    }
    return player;
  }

  /** Pat's Season 2 ender chest sealed, and Season 3 launched into its Frontier stage. */
  private PlayerRef sealedAndLaunched() {
    start();
    PlayerRef pat = fake.join("Pat");
    fake.player(pat).inventory = FakeItems.gear();
    run("/season init 2");
    run("/season end --now");
    PlayerRef admin = fake.join("Admin");
    fake.player(admin).position = Position.of("minecraft:overworld", 500, -60, 300);
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(admin.id())), "/season spawn set");
    fake.leave(admin);
    run("/season launch");
    return pat;
  }

  private FakePlatform.Withdraw menu() {
    return fake.withdraws.getLast();
  }

  private int carried(PlayerRef player, String id) {
    return (int)
        fake.player(player).inventory.storage().stream()
            .flatMap(Optional::stream)
            .filter(item -> item.equals(FakeItems.item(id, 5)))
            .count();
  }

  private Optional<List<Optional<NeutralItem>>> page(PlayerRef player, int season)
      throws IOException {
    return new VaultFiles(dir.resolve("world/seasons/data/vaults")).page(player.id(), season);
  }

  @Test
  void beforeHomecomingTheVaultIsReadOnly() throws IOException {
    PlayerRef pat = sealedAndLaunched();

    assertThat(vault(pat))
        .containsExactly(
            "Your ender vault is sealed until Homecoming: you can look, but not take.");
    assertThat(menu().view().pages().getFirst().getFirst()).contains(EMERALDS);
    int before = fake.sent.size();
    menu().taken().clicked(0, 0);

    assertThat(said(pat, before)).containsExactly("That page is sealed until Homecoming.");
    assertThat(carried(pat, "minecraft:emerald")).isZero();
    assertThat(page(pat, 2).orElseThrow().getFirst()).contains(EMERALDS);
  }

  @Test
  void afterHomecomingEverythingCanBeWithdrawnOnce() throws IOException {
    PlayerRef pat = sealedAndLaunched();
    run("/season unlock homecoming");

    assertThat(vault(pat)).containsExactly("Click an item to take it.");
    int before = fake.sent.size();
    menu().taken().clicked(0, 0);

    assertThat(said(pat, before)).containsExactly("Took 5 × minecraft:emerald from your vault.");
    assertThat(carried(pat, "minecraft:emerald")).isEqualTo(1);
    assertThat(page(pat, 2).orElseThrow().getFirst()).isEmpty();
    // The menu reopens on the same page, showing the slot empty; a second click takes nothing.
    assertThat(menu().view().pages().getFirst().getFirst()).isEmpty();
    fake.withdraws.get(fake.withdraws.size() - 2).taken().clicked(0, 0);
    assertThat(carried(pat, "minecraft:emerald")).isEqualTo(1);
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }

  @Test
  void aFullInventoryLeavesTheItemInTheVault() throws IOException {
    PlayerRef pat = sealedAndLaunched();
    run("/season unlock homecoming");
    fake.player(pat).inventory =
        new InventorySnapshot(
            FakeItems.empty(36).stream()
                .map(slot -> Optional.of(FakeItems.item("minecraft:stone", 64)))
                .toList(),
            FakeItems.empty(4),
            Optional.empty(),
            FakeItems.empty(27));
    vault(pat);
    int before = fake.sent.size();
    menu().taken().clicked(0, 0);

    assertThat(said(pat, before))
        .containsExactly("Your inventory is full, so it stays in your vault.");
    assertThat(page(pat, 2).orElseThrow().getFirst()).contains(EMERALDS);
    assertThat(menu().view().pages().getFirst().getFirst()).contains(EMERALDS);
  }

  @Test
  void theReturnedPageCanBeWithdrawnFromAtOnce() throws IOException {
    PlayerRef pat = sealedAndLaunched();
    NeutralItem diamonds = FakeItems.item("minecraft:diamond", 3);
    var _ = hooks.returnToVault(pat.id(), diamonds);

    vault(pat);
    assertThat(menu().view().pages()).hasSize(2);
    assertThat(menu().view().pages().get(1).getFirst()).contains(diamonds);
    menu().taken().clicked(1, 0);

    assertThat(fake.player(pat).inventory.storage()).contains(Optional.of(diamonds));
    // The Season 2 page is still sealed.
    menu().taken().clicked(0, 0);
    assertThat(page(pat, 2).orElseThrow().getFirst()).contains(EMERALDS);
  }

  @Test
  void betweenSeasonsTheVaultIsReadOnlyAgain() throws IOException {
    PlayerRef pat = sealedAndLaunched();
    run("/season unlock homecoming");
    run("/season end --now");

    assertThat(vault(pat))
        .containsExactly("Your ender vault is read-only until the next season starts.");
    int before = fake.sent.size();
    menu().taken().clicked(1, 0);
    assertThat(said(pat, before))
        .containsExactly("Your vault is read-only until the next season starts.");
  }

  @Test
  void anEmptyVaultSaysSo() {
    start();
    PlayerRef pat = fake.join("Pat");
    run("/season init 2");
    assertThat(vault(pat)).containsExactly("Your ender vault is empty.");
    assertThat(fake.withdraws).isEmpty();
  }

  @Test
  void unarchivingAfterAWithdrawalRestoresThePageAsItIsNow() throws IOException {
    PlayerRef pat = sealedAndLaunched();
    run("/season unlock homecoming");
    vault(pat);
    menu().taken().clicked(0, 0);
    assertThat(carried(pat, "minecraft:emerald")).isEqualTo(1);

    assertThat(run("/season unarchive Pat 2 --force").getFirst()).startsWith("Restored");

    // The emeralds went out of the vault once; the ender chest doesn't bring them back.
    assertThat(fake.player(pat).inventory.enderChest()).allMatch(Optional::isEmpty);
    assertThat(page(pat, 2)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = CrashPoint.class,
      names = {"VAULT_WITHDRAW_STARTED", "VAULT_WITHDRAW_PERSISTED", "VAULT_WITHDRAW_GIVEN"})
  void aCrashMidWithdrawalLeavesTheItemExactlyOnce(CrashPoint point) throws IOException {
    PlayerRef pat = sealedAndLaunched();
    run("/season unlock homecoming");
    vault(pat);
    hooks.arm(point, pat.id());
    InventorySnapshot saved = fake.player(pat).inventory;
    try {
      menu().taken().clicked(0, 0);
    } catch (ArchivingTest.Halted halted) {
      // As the server stopping dead.
    }
    // What the player's saved file holds after the crash: given only if saved after the give.
    if (point != CrashPoint.VAULT_WITHDRAW_GIVEN) {
      fake.player(pat).inventory = saved;
    }
    fake.leave(pat);
    restart();
    join("Pat");

    boolean given = point == CrashPoint.VAULT_WITHDRAW_GIVEN;
    assertThat(carried(pat, "minecraft:emerald")).isEqualTo(given ? 1 : 0);
    assertThat(page(pat, 2).orElseThrow().getFirst())
        .isEqualTo(given ? Optional.empty() : Optional.of(EMERALDS));
    assertThat(run("/season status")).anyMatch(line -> line.endsWith(", 0 waiting for recovery"));
  }
}
