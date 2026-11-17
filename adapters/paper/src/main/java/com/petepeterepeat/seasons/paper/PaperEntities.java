// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Entities;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
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

  private Optional<Entity> entity(UUID id) {
    return Optional.ofNullable(server.getEntity(id));
  }
}
