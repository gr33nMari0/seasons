// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PagedView;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.List;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The paged view's items. Clicks need {@code Inventory#getHolder(boolean)}, which MockBukkit
 * doesn't implement, so A8 covers every click type on a real server.
 */
class PaperMenusTest {
  private static final NamespacedKey ARCHIVED = new NamespacedKey("seasons", "archived");

  private ServerMock server;
  private PaperMenus menus;
  private PaperItemCodec codec;
  private PaperInventories inventories;
  private PlayerMock viewer;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    PaperText text = new PaperText();
    PaperPlayers players = new PaperPlayers(server, text);
    codec = new PaperItemCodec();
    menus = new PaperMenus(server, players, codec, text, ARCHIVED);
    inventories = new PaperInventories(players, codec, ARCHIVED);
    viewer = server.addPlayer("Viewer");
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private Optional<NeutralItem> item(Material material) {
    return codec.encode(ItemStack.of(material));
  }

  private InventoryView open() {
    PagedView view =
        new PagedView(
            Message.of("Viewer, Season 2"),
            List.of(List.of(item(Material.DIAMOND_SWORD)), List.of(item(Material.EMERALD))),
            Message.of("Previous"),
            Message.of("Next"),
            List.of(Message.of("Page 1 of 2"), Message.of("Page 2 of 2")));
    menus.openPaged(new PlayerId(viewer.getUniqueId()), view);
    return viewer.getOpenInventory();
  }

  @Test
  void everyShownItemIsTaggedForTheTripwire() {
    InventoryView view = open();

    assertThat(view.getTopInventory().getItem(0).getType()).isEqualTo(Material.DIAMOND_SWORD);
    assertThat(view.getTopInventory().getItem(PaperMenus.PREVIOUS)).isNull();
    assertThat(inventories.isArchived(view.getTopInventory().getItem(0))).isTrue();
    assertThat(inventories.isArchived(view.getTopInventory().getItem(PaperMenus.NEXT))).isTrue();
  }
}
