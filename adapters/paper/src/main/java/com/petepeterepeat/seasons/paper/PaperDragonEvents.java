// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.DragonEvents;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.Optional;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.jspecify.annotations.Nullable;

/**
 * The End's dragons, for the slayer: who hurt one and how much, who dealt the final blow, and the
 * End loading and being saved. A player is whoever caused the damage, as its damage source says, so
 * a hit, an arrow, a trident, or a crystal or TNT they set off all count as theirs.
 */
final class PaperDragonEvents implements Listener {
  private @Nullable DragonEvents listener;

  void listen(DragonEvents events) {
    this.listener = events;
  }

  /** After every other plugin has had its say, so only damage that lands counts. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onHurt(EntityDamageEvent event) {
    DragonEvents listening = listener;
    if (listening == null
        || !(event.getEntity() instanceof EnderDragon dragon)
        || !inTheEnd(dragon)) {
      return;
    }
    Optional<PlayerId> player = causedBy(event.getDamageSource());
    if (player.isPresent()) {
      // The health it costs: a blow bigger than what is left counts only what was left.
      double dealt = Math.min(event.getFinalDamage(), dragon.getHealth());
      listening.hurt(dragon.getUniqueId(), player.get(), dealt);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  void onDeath(EntityDeathEvent event) {
    DragonEvents listening = listener;
    if (listening != null && event.getEntity() instanceof EnderDragon dragon && inTheEnd(dragon)) {
      listening.died(dragon.getUniqueId(), causedBy(event.getDamageSource()));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  void onWorldLoad(WorldLoadEvent event) {
    DragonEvents listening = listener;
    if (listening != null
        && event.getWorld().getKey().asString().equals(PaperDragonFight.THE_END)) {
      listening.endLoaded();
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  void onWorldSave(WorldSaveEvent event) {
    DragonEvents listening = listener;
    if (listening != null
        && event.getWorld().getKey().asString().equals(PaperDragonFight.THE_END)) {
      listening.endSaved();
    }
  }

  private static boolean inTheEnd(Entity entity) {
    return entity.getWorld().getKey().asString().equals(PaperDragonFight.THE_END);
  }

  private static Optional<PlayerId> causedBy(DamageSource source) {
    return source.getCausingEntity() instanceof Player player
        ? Optional.of(new PlayerId(player.getUniqueId()))
        : Optional.empty();
  }
}
