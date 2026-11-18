// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.HallGuard;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * The Hall's guard against changes nobody in particular makes, from Paper's events. Hoppers and
 * containers need real block inventories, so A23 covers them on a real server.
 */
class PaperHallGuardTest {
  private ServerMock server;
  private World world;
  private PaperHallGuard guard;

  /** The Hall is every block with x below 10; it all counts as one cell being decorated. */
  private static final HallGuard BELOW_TEN =
      new HallGuard() {
        @Override
        public boolean protects(Position block) {
          return block.blockX() < 10;
        }

        @Override
        public boolean allows(List<Position> blocks) {
          return blocks.stream().allMatch(this::protects)
              || blocks.stream().noneMatch(this::protects);
        }

        @Override
        public void entitiesLoaded(String dimension, int chunkX, int chunkZ) {}
      };

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    NamespacedKey archived = new NamespacedKey("seasons", "archived");
    PaperText text = new PaperText();
    PaperPlayers players = new PaperPlayers(server, text, new NamespacedKey("seasons", "c"));
    PaperItemCodec codec = new PaperItemCodec();
    PaperEvents events =
        new PaperEvents(
            MockBukkit.createMockPlugin(),
            text,
            new PaperInventories(players, codec, archived),
            players,
            new PaperDisplays(server, text, codec, new NamespacedKey("seasons", "d"), archived),
            new PaperAdvancements(server, players));
    guard = events.hallGuard();
    events.hall(BELOW_TEN);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private Block at(int x) {
    return world.getBlockAt(x, 64, 0);
  }

  @Test
  void waterFlowsOutsideTheHallButNotIntoIt() {
    BlockFromToEvent outside = new BlockFromToEvent(at(12), at(11));
    BlockFromToEvent in = new BlockFromToEvent(at(10), at(9));

    guard.onFlow(outside);
    guard.onFlow(in);

    assertThat(outside.isCancelled()).isFalse();
    assertThat(in.isCancelled()).isTrue();
  }

  @Test
  void aPistonCantPushIntoTheHall() {
    BlockPistonExtendEvent outside =
        new BlockPistonExtendEvent(at(14), List.of(at(13)), BlockFace.WEST);
    BlockPistonExtendEvent in = new BlockPistonExtendEvent(at(11), List.of(at(10)), BlockFace.WEST);

    guard.onPistonExtend(outside);
    guard.onPistonExtend(in);

    assertThat(outside.isCancelled()).isFalse();
    assertThat(in.isCancelled()).isTrue();
  }

  @Test
  void nothingBurnsInTheHall() {
    BlockBurnEvent outside = new BlockBurnEvent(at(12), at(13));
    BlockBurnEvent in = new BlockBurnEvent(at(9), at(10));

    guard.onBurn(outside);
    guard.onBurn(in);

    assertThat(outside.isCancelled()).isFalse();
    assertThat(in.isCancelled()).isTrue();
  }
}
