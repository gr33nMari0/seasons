// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** The wand on Paper (questions 226): a marked item, and its clicks raised as UseWand. */
class PaperWandTest {
  private ServerMock server;
  private WorldMock world;
  private PaperInventories inventories;
  private PaperEvents events;
  private final List<Intent> decided = new ArrayList<>();
  private Decision answer = new Decision.Deny(java.util.Optional.empty());

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    NamespacedKey archived = new NamespacedKey("seasons", "archived");
    PaperText text = new PaperText();
    PaperPlayers players = new PaperPlayers(server, text, new NamespacedKey("seasons", "c"));
    PaperItemCodec codec = new PaperItemCodec();
    inventories =
        new PaperInventories(
            players, codec, archived, new NamespacedKey("seasons", "wand"), new PaperText());
    events =
        new PaperEvents(
            MockBukkit.createMockPlugin(),
            text,
            inventories,
            players,
            new PaperDisplays(server, text, codec, new NamespacedKey("seasons", "d"), archived),
            new PaperAdvancements(server, players),
            new PaperEntities(
                MockBukkit.createMockPlugin(), server, new NamespacedKey("seasons", "pet")));
    events.handle(intent -> {
      decided.add(intent);
      return intent instanceof Intent.UseWand ? answer : Decision.allow();
    });
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private PlayerMock holdingWand() {
    PlayerMock alex = server.addPlayer("Alex");
    assertThat(
            inventories.giveWand(
                new PlayerId(alex.getUniqueId()),
                "minecraft:blaze_rod",
                Message.of("Seasons wand"),
                Message.of("A wand")))
        .isTrue();
    alex.getInventory().setHeldItemSlot(alex.getInventory().first(Material.BLAZE_ROD));
    return alex;
  }

  private PlayerInteractEvent click(PlayerMock player, Action action, EquipmentSlot hand) {
    Block block = world.getBlockAt(3, 64, 4);
    PlayerInteractEvent event =
        new PlayerInteractEvent(
            player, action, player.getInventory().getItemInMainHand(), block, BlockFace.EAST, hand);
    events.onInteract(event);
    return event;
  }

  @Test
  void onlyAMarkedWandIsAWand() {
    PlayerMock alex = holdingWand();
    assertThat(inventories.isWand(alex.getInventory().getItemInMainHand())).isTrue();
    assertThat(inventories.isWand(ItemStack.of(Material.BLAZE_ROD))).isFalse();

    alex.getInventory().setItemInMainHand(ItemStack.of(Material.BLAZE_ROD));
    click(alex, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
    assertThat(decided).noneMatch(Intent.UseWand.class::isInstance);
  }

  @Test
  void aWandClickIsRaisedWithItsSideAndTakenOver() {
    PlayerMock alex = holdingWand();
    alex.setSneaking(true);
    PlayerInteractEvent event = click(alex, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND);
    assertThat(decided.getFirst())
        .isEqualTo(
            new Intent.UseWand(
                new PlayerId(alex.getUniqueId()),
                PaperPositions.toPosition(world.getBlockAt(3, 64, 4).getLocation()),
                "east",
                true,
                true));
    assertThat(event.useInteractedBlock()).isEqualTo(Event.Result.DENY);
    assertThat(event.useItemInHand()).isEqualTo(Event.Result.DENY);
  }

  @Test
  void onlyTheMainHandCountsAndItsOffHandTwinIsTakenOverToo() {
    PlayerMock alex = holdingWand();
    click(alex, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
    PlayerInteractEvent offHand = click(alex, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND);
    assertThat(decided).filteredOn(Intent.UseWand.class::isInstance).hasSize(1);
    assertThat(offHand.useInteractedBlock()).isEqualTo(Event.Result.DENY);
  }

  @Test
  void aClickTheCoreLetsGoIsAnOrdinaryClick() {
    answer = Decision.allow();
    PlayerMock alex = holdingWand();
    PlayerInteractEvent event = click(alex, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND);
    assertThat(decided).anyMatch(Intent.UseBlock.class::isInstance);
    assertThat(event.useInteractedBlock()).isNotEqualTo(Event.Result.DENY);
  }
}
