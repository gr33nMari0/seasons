// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.port.Events;
import com.petepeterepeat.seasons.core.port.PlayerId;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/** Turns Paper events into core intents and applies the core's decisions. */
final class PaperEvents implements Events, Listener {
  private final PaperText text;
  private IntentHandler handler = intent -> Decision.allow();

  PaperEvents(PaperText text) {
    this.text = text;
  }

  @Override
  public void handle(IntentHandler handler) {
    this.handler = handler;
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onBreak(BlockBreakEvent event) {
    Player player = event.getPlayer();
    apply(
        event,
        player,
        new Intent.BreakBlock(
            id(player), PaperPositions.toPosition(event.getBlock().getLocation())));
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  void onPlace(BlockPlaceEvent event) {
    Player player = event.getPlayer();
    apply(
        event,
        player,
        new Intent.PlaceBlock(
            id(player),
            PaperPositions.toPosition(event.getBlockPlaced().getLocation()),
            event.getBlockPlaced().getType().getKey().asString()));
  }

  private void apply(Cancellable event, Player player, Intent intent) {
    switch (handler.decide(intent)) {
      case Decision.Allow allow -> {}
      case Decision.Deny deny -> {
        event.setCancelled(true);
        deny.message().ifPresent(message -> player.sendMessage(text.render(message)));
      }
      case Decision.Redirect redirect -> {
        event.setCancelled(true);
        var _ = player.teleportAsync(PaperPositions.toLocation(redirect.to()));
        redirect.message().ifPresent(message -> player.sendMessage(text.render(message)));
      }
    }
  }

  private static PlayerId id(Player player) {
    return new PlayerId(player.getUniqueId());
  }
}
