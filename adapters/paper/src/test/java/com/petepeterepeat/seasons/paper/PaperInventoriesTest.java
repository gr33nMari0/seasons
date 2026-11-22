// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Which menu slots archiving takes as the player's own (security review H1). The archive itself,
 * with real menus that hand their inputs back as they close, is {@code sec-workstation-inputs}.
 */
class PaperInventoriesTest {
  private ServerMock server;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void workstationInputsAreThePlayersButNeverResultsOrContainers() {
    assertThat(PaperInventories.inputSlots(server.createInventory(null, InventoryType.ANVIL)))
        .containsExactly(0, 1);
    assertThat(PaperInventories.inputSlots(server.createInventory(null, InventoryType.STONECUTTER)))
        .containsExactly(0);
    assertThat(PaperInventories.inputSlots(server.createInventory(null, InventoryType.CHEST)))
        .isEmpty();
    assertThat(PaperInventories.inputSlots(server.createInventory(null, InventoryType.FURNACE)))
        .isEmpty();
  }
}
