// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ZoneGuard;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The zones' guard against changes nobody in particular makes, from Paper's events, and the player
 * intents that reach the zones outside the Hall. Hoppers, containers and portals need a real
 * server, so A30 and A35 cover them there.
 */
class PaperZoneGuardTest {
  private ServerMock server;
  private World world;
  private PaperZoneGuard guard;
  private PaperEvents events;

  /** What the core was asked, and refused, in place of the core. */
  private final List<Intent> asked = new ArrayList<>();

  /** One blocked zone: every block with x below 10. */
  private static final ZoneGuard BELOW_TEN =
      new ZoneGuard() {
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
        public boolean blocks(Position at) {
          return protects(at);
        }
      };

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("world");
    NamespacedKey archived = new NamespacedKey("seasons", "archived");
    PaperText text = new PaperText();
    PaperPlayers players = new PaperPlayers(server, text, new NamespacedKey("seasons", "c"));
    PaperItemCodec codec = new PaperItemCodec();
    events =
        new PaperEvents(
            MockBukkit.createMockPlugin(),
            text,
            new PaperInventories(
                players,
                codec,
                archived,
                new org.bukkit.NamespacedKey("seasons", "wand"),
                new PaperText()),
            players,
            new PaperDisplays(server, text, codec, new NamespacedKey("seasons", "d"), archived),
            new PaperAdvancements(server, players),
            new PaperEntities(
                MockBukkit.createMockPlugin(), server, new NamespacedKey("seasons", "pet")));
    guard = events.zoneGuard();
    events.zones(BELOW_TEN);
    events.handle(intent -> {
      asked.add(intent);
      return new Decision.Deny(java.util.Optional.empty());
    });
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private Block at(int x) {
    return world.getBlockAt(x, 64, 0);
  }

  @Test
  void liquidsFlowOnEitherSideButNotAcrossTheEdge() {
    BlockFromToEvent outside = new BlockFromToEvent(at(12), at(11));
    BlockFromToEvent inside = new BlockFromToEvent(at(5), at(4));
    BlockFromToEvent in = new BlockFromToEvent(at(10), at(9));
    BlockFromToEvent out = new BlockFromToEvent(at(9), at(10));

    List.of(outside, inside, in, out).forEach(guard::onFlow);

    assertThat(outside.isCancelled()).isFalse();
    assertThat(inside.isCancelled()).isFalse();
    assertThat(in.isCancelled()).isTrue();
    assertThat(out.isCancelled()).isTrue();
  }

  @Test
  void aPistonCantPushAcrossTheEdge() {
    BlockPistonExtendEvent outside =
        new BlockPistonExtendEvent(at(14), List.of(at(13)), BlockFace.WEST);
    BlockPistonExtendEvent in = new BlockPistonExtendEvent(at(11), List.of(at(10)), BlockFace.WEST);

    guard.onPistonExtend(outside);
    guard.onPistonExtend(in);

    assertThat(outside.isCancelled()).isFalse();
    assertThat(in.isCancelled()).isTrue();
  }

  @Test
  void nothingInsideBurnsOrCatchesFire() {
    BlockBurnEvent outside = new BlockBurnEvent(at(12), at(13));
    BlockBurnEvent in = new BlockBurnEvent(at(9), at(10));
    BlockIgniteEvent lightning =
        new BlockIgniteEvent(at(5), BlockIgniteEvent.IgniteCause.LIGHTNING, (Block) null);

    guard.onBurn(outside);
    guard.onBurn(in);
    guard.onIgnite(lightning);

    assertThat(outside.isCancelled()).isFalse();
    assertThat(in.isCancelled()).isTrue();
    assertThat(lightning.isCancelled()).isTrue();
  }

  @Test
  void anExplosionBreaksNothingInside() {
    List<Block> blocks = new ArrayList<>(List.of(at(8), at(9), at(10), at(11)));
    BlockExplodeEvent explosion =
        new BlockExplodeEvent(at(10), at(10).getState(), blocks, 4f, ExplosionResult.DESTROY);

    guard.onBlockExplode(explosion);

    assertThat(explosion.blockList()).containsExactly(at(10), at(11));
  }

  @Test
  void aPlayersHitPickupOrCatchOutsideTheHallIsAskedAboutWhereTheThingIs() {
    PlayerMock pat = server.addPlayer("Pat");
    pat.teleport(new Location(world, 12.5, 64, 0.5));
    ArmorStand stand = world.spawn(new Location(world, 9.5, 64, 0.5), ArmorStand.class);
    Item item = world.dropItem(new Location(world, 9.5, 64, 1.5), new ItemStack(Material.DIAMOND));

    EntityDamageByEntityEvent blow = hit(pat, stand);
    events.onDamage(blow);
    events.onPickup(new EntityPickupItemEvent(pat, item, 0));
    PlayerFishEvent reel =
        new PlayerFishEvent(pat, item, null, PlayerFishEvent.State.CAUGHT_ENTITY);
    events.onFish(reel);

    assertThat(asked)
        .containsExactly(
            new Intent.HurtEntity(
                PaperEvents.id(pat),
                PaperPositions.toPosition(stand.getLocation()),
                "minecraft:armor_stand"),
            new Intent.PickupItem(
                PaperEvents.id(pat), PaperPositions.toPosition(item.getLocation())),
            new Intent.UseEntity(
                PaperEvents.id(pat),
                PaperPositions.toPosition(item.getLocation()),
                "minecraft:item"));
    assertThat(blow.isCancelled()).isTrue();
    assertThat(reel.isCancelled()).isTrue();
  }

  /** A player's hit, as the server raises it; the event's constructors are internal to Paper. */
  @SuppressWarnings("removal")
  private static EntityDamageByEntityEvent hit(Player attacker, Entity target) {
    return new EntityDamageByEntityEvent(
        attacker,
        target,
        EntityDamageEvent.DamageCause.ENTITY_ATTACK,
        DamageSource.builder(DamageType.PLAYER_ATTACK)
            .withCausingEntity(attacker)
            .withDirectEntity(attacker)
            .build(),
        1);
  }
}
