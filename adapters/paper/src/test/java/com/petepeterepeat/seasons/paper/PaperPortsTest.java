// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.ProgressBar;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperPortsTest {
  private static final NamespacedKey ARCHIVED = new NamespacedKey("seasons", "archived");

  private ServerMock server;
  private PaperText text;
  private PaperPlayers players;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    text = new PaperText();
    players = new PaperPlayers(server, text);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void listsOnlinePlayers() {
    PlayerMock alex = server.addPlayer("Alex");

    assertThat(players.online())
        .containsExactly(new PlayerRef(new PlayerId(alex.getUniqueId()), "Alex"));
    assertThat(players.find(new PlayerId(alex.getUniqueId()))).isPresent();
  }

  @Test
  void placeholdersAreLiteralText() {
    PlayerMock alex = server.addPlayer("Alex");

    players.sendMessage(
        new PlayerId(alex.getUniqueId()),
        new Message("Hi <name>", Map.of("name", "<red><click:run_command:/op me>Steve")));

    assertThat(PlainTextComponentSerializer.plainText().serialize(alex.nextComponentMessage()))
        .isEqualTo("Hi <red><click:run_command:/op me>Steve");
  }

  @Test
  void permissionsFollowTheServer() {
    PlayerMock alex = server.addPlayer("Alex");
    PaperPermissions permissions = new PaperPermissions(players);
    PlayerId id = new PlayerId(alex.getUniqueId());

    assertThat(permissions.has(id, "seasons.admin.season")).isFalse();
    alex.setOp(true);
    assertThat(permissions.has(id, "seasons.admin.season")).isTrue();
  }

  @Test
  void clearingAPlayerLeavesTheContainerTheyHaveOpen() {
    PlayerMock alex = server.addPlayer("Alex");
    alex.getInventory().addItem(ItemStack.of(Material.DIRT));
    Inventory chest = server.createInventory(null, InventoryType.CHEST);
    chest.addItem(ItemStack.of(Material.DIAMOND));
    alex.openInventory(chest);

    inventories().clear(new PlayerId(alex.getUniqueId()), true);

    assertThat(alex.getInventory().isEmpty()).isTrue();
    assertThat(chest.contains(Material.DIAMOND)).isTrue();
  }

  @Test
  void theEnderChestIsClearedOnlyWhenAsked() {
    PlayerMock alex = server.addPlayer("Alex");
    alex.getEnderChest().addItem(ItemStack.of(Material.EMERALD, 5));
    alex.openInventory(server.createInventory(null, InventoryType.CHEST));

    inventories().clear(new PlayerId(alex.getUniqueId()), false);
    assertThat(alex.getEnderChest().contains(Material.EMERALD)).isTrue();
    inventories().clear(new PlayerId(alex.getUniqueId()), true);
    assertThat(alex.getEnderChest().isEmpty()).isTrue();
  }

  @Test
  void settlingEmptiesTheCursorIntoOverflow() {
    PlayerMock alex = server.addPlayer("Alex");
    alex.setItemOnCursor(ItemStack.of(Material.TORCH, 3));

    assertThat(inventories().settle(new PlayerId(alex.getUniqueId()))).hasSize(1);
    assertThat(alex.getItemOnCursor().isEmpty()).isTrue();
  }

  @Test
  void archivedCopiesArePurgedAndDescribed() {
    PlayerMock alex = server.addPlayer("Alex");
    ItemStack tagged = ItemStack.of(Material.STONE, 3);
    tagged.editMeta(
        meta -> meta.getPersistentDataContainer().set(ARCHIVED, PersistentDataType.BOOLEAN, true));
    alex.getInventory().addItem(tagged, ItemStack.of(Material.DIRT, 5));
    alex.openInventory(server.createInventory(null, InventoryType.CHEST));

    assertThat(inventories().purgeArchived(new PlayerId(alex.getUniqueId())))
        .containsExactly("3 × minecraft:stone");
    assertThat(alex.getInventory().contains(Material.STONE)).isFalse();
    assertThat(alex.getInventory().contains(Material.DIRT)).isTrue();
  }

  private PaperInventories inventories() {
    return new PaperInventories(players, new PaperItemCodec(), ARCHIVED);
  }

  @Test
  void broadcastsReachEveryone() {
    PlayerMock alex = server.addPlayer("Alex");

    players.broadcast(Message.of("Season 2 ends in 7 days"));

    assertThat(PlainTextComponentSerializer.plainText().serialize(alex.nextComponentMessage()))
        .isEqualTo("Season 2 ends in 7 days");
  }

  @Test
  void theCountdownBarIsShownUpdatedAndHidden() {
    PlayerMock alex = server.addPlayer("Alex");

    ProgressBar bar = players.showProgressBar(Message.of("Season 2 ends in 10:00"), 1f);
    assertThat(alex.activeBossBars()).hasSize(1);
    bar.update(Message.of("Season 2 ends in 9:59"), 2f);
    assertThat(alex.activeBossBars())
        .singleElement()
        .satisfies(shown -> {
          assertThat(PlainTextComponentSerializer.plainText().serialize(shown.name()))
              .isEqualTo("Season 2 ends in 9:59");
          assertThat(shown.progress()).isEqualTo(1f);
        });
    bar.hide();
    assertThat(alex.activeBossBars()).isEmpty();
  }
}
