// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Entities;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Server;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Tameable;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

final class PaperEntities implements Entities {
  private final Server server;

  PaperEntities(Server server) {
    this.server = server;
  }

  @Override
  public Optional<Position> position(UUID id) {
    return entity(id).map(entity -> PaperPositions.toPosition(entity.getLocation()));
  }

  @Override
  public CompletableFuture<Boolean> teleport(UUID id, Position destination) {
    return entity(id)
        .map(entity ->
            entity.teleportAsync(PaperPositions.toLocation(destination), TeleportCause.PLUGIN))
        .orElseGet(() -> CompletableFuture.completedFuture(false));
  }

  @Override
  public Optional<PlayerId> owner(UUID id) {
    return entity(id)
        .filter(Tameable.class::isInstance)
        .map(entity -> ((Tameable) entity).getOwnerUniqueId())
        .map(PlayerId::new);
  }

  @Override
  public List<Position> hostiles(Position centre, double dx, double dy, double dz) {
    Optional<org.bukkit.World> world = PaperPositions.world(centre.dimension());
    if (world.isEmpty()) {
      return List.of();
    }
    return world
        .get()
        .getNearbyEntities(PaperPositions.toLocation(centre), dx, dy, dz, PaperEntities::hostile)
        .stream()
        .map(entity -> PaperPositions.toPosition(entity.getLocation()))
        .toList();
  }

  /** As vanilla counts monsters for sleeping: hostile mobs, and zombified piglins only if angry. */
  static boolean hostile(Entity entity) {
    if (!(entity instanceof Enemy) || entity.isDead()) {
      return false;
    }
    return !(entity instanceof PigZombie piglin) || piglin.isAngry();
  }

  private Optional<Entity> entity(UUID id) {
    return Optional.ofNullable(server.getEntity(id));
  }
}
