// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.petepeterepeat.seasons.core.port.Entities;
import com.petepeterepeat.seasons.core.port.PetEvents;
import com.petepeterepeat.seasons.core.port.PetInfo;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import io.papermc.paper.entity.RemovalReason;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Entities by id, and registered pets: marked with {@code seasons:pet} (their owner's id) so that
 * unloading and removal can be reported, and found and brought at travel time.
 */
final class PaperEntities implements Entities, Listener {
  /** How long {@link #load} waits for a chunk's entities after the chunk itself: five seconds. */
  private static final int ENTITY_WAIT_TICKS = 100;

  private final Plugin plugin;
  private final Server server;
  private final NamespacedKey petKey;
  private final Map<UUID, Chunk> tickets = new ConcurrentHashMap<>();
  private @Nullable PetEvents pets;

  PaperEntities(Plugin plugin, Server server, NamespacedKey petKey) {
    this.plugin = plugin;
    this.server = server;
    this.petKey = petKey;
  }

  /** Sets who hears about registered pets unloading or being removed. */
  void listen(PetEvents pets) {
    this.pets = pets;
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

  @Override
  public Optional<PetInfo> pet(UUID id) {
    return entity(id).map(PaperEntities::info);
  }

  @Override
  public void markPet(UUID id, Optional<PlayerId> owner) {
    entity(id)
        .ifPresent(entity -> {
          if (owner.isPresent()) {
            entity
                .getPersistentDataContainer()
                .set(petKey, PersistentDataType.STRING, owner.get().toString());
          } else {
            entity.getPersistentDataContainer().remove(petKey);
          }
        });
  }

  @Override
  public CompletableFuture<Optional<PetInfo>> load(UUID id, Optional<Position> lastSeen) {
    Optional<Entity> loaded = entity(id);
    if (loaded.isPresent()) {
      return CompletableFuture.completedFuture(loaded.map(PaperEntities::info));
    }
    Optional<World> world = lastSeen.flatMap(at -> PaperPositions.world(at.dimension()));
    if (lastSeen.isEmpty() || world.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    Position at = lastSeen.get();
    CompletableFuture<Optional<PetInfo>> found = new CompletableFuture<>();
    var _ =
        world
            .get()
            .getChunkAtAsync(at.blockX() >> 4, at.blockZ() >> 4)
            .whenComplete((chunk, failure) -> {
              if (failure != null || chunk == null) {
                found.complete(Optional.empty());
                return;
              }
              chunk.addPluginChunkTicket(plugin);
              whenEntitiesLoaded(
                  chunk,
                  ENTITY_WAIT_TICKS,
                  () -> {
                    Optional<PetInfo> info = entity(id).map(PaperEntities::info);
                    if (info.isPresent()) {
                      release(id);
                      tickets.put(id, chunk);
                    } else {
                      letGo(chunk);
                    }
                    found.complete(info);
                  });
            });
    return found;
  }

  @Override
  public CompletableFuture<Boolean> bring(UUID id, Position destination) {
    Optional<Entity> found = entity(id);
    if (found.isEmpty()) {
      release(id);
      return CompletableFuture.completedFuture(false);
    }
    Entity entity = found.get();
    if (entity instanceof LivingEntity living && living.isLeashed()) {
      living.setLeashHolder(null);
    }
    entity.leaveVehicle();
    entity.eject();
    return entity
        .teleportAsync(PaperPositions.toLocation(destination), TeleportCause.PLUGIN)
        .whenComplete((arrived, failure) -> release(id));
  }

  @Override
  public void release(UUID id) {
    Chunk chunk = tickets.remove(id);
    if (chunk != null) {
      letGo(chunk);
    }
  }

  /**
   * Removes Seasons' ticket from a chunk unless another pet still waits in it: a plugin has one
   * ticket per chunk, however many times it adds one.
   */
  private void letGo(Chunk chunk) {
    if (!tickets.containsValue(chunk)) {
      chunk.removePluginChunkTicket(plugin);
    }
  }

  /** A chunk unloading: where each registered pet in it stood. */
  @EventHandler(priority = EventPriority.MONITOR)
  void onUnload(EntitiesUnloadEvent event) {
    PetEvents listening = pets;
    if (listening == null) {
      return;
    }
    for (Entity entity : event.getEntities()) {
      owner(entity)
          .ifPresent(owner ->
              listening.unloaded(
                  entity.getUniqueId(), owner, PaperPositions.toPosition(entity.getLocation())));
    }
  }

  /**
   * A registered pet leaving the world: killed, removed for good otherwise, or only unloaded or
   * changing dimension, which the removal reason tells apart.
   */
  @EventHandler(priority = EventPriority.MONITOR)
  void onRemove(EntityRemoveFromWorldEvent event) {
    PetEvents listening = pets;
    Entity entity = event.getEntity();
    Optional<PlayerId> owner = owner(entity);
    if (listening == null || owner.isEmpty()) {
      return;
    }
    RemovalReason reason = entity.getRemovalReason();
    if (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) {
      listening.removed(entity.getUniqueId(), owner.get(), reason == RemovalReason.KILLED);
    } else {
      listening.unloaded(
          entity.getUniqueId(), owner.get(), PaperPositions.toPosition(entity.getLocation()));
    }
  }

  /** The owner a pet was registered by, from its mark. */
  private Optional<PlayerId> owner(Entity entity) {
    String marked = entity.getPersistentDataContainer().get(petKey, PersistentDataType.STRING);
    if (marked == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(new PlayerId(UUID.fromString(marked)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static PetInfo info(Entity entity) {
    Optional<PlayerId> owner =
        entity instanceof Tameable tameable
            ? Optional.ofNullable(tameable.getOwnerUniqueId()).map(PlayerId::new)
            : Optional.empty();
    Component name = entity.customName();
    return new PetInfo(
        entity.getUniqueId(),
        entity.getType().getKey().getKey(),
        entity instanceof Tameable,
        entity instanceof Tameable tameable && tameable.isTamed(),
        owner,
        entity instanceof ChestedHorse chested && chested.isCarryingChest(),
        name == null
            ? Optional.empty()
            : Optional.of(PlainTextComponentSerializer.plainText().serialize(name)),
        PaperPositions.toPosition(entity.getLocation()));
  }

  /**
   * Runs {@code then} once a chunk has loaded its entities, which Paper does after the chunk
   * itself, or after {@code ticksLeft} ticks.
   */
  private void whenEntitiesLoaded(Chunk chunk, int ticksLeft, Runnable then) {
    if (ticksLeft <= 0 || chunk.isEntitiesLoaded()) {
      then.run();
      return;
    }
    server
        .getGlobalRegionScheduler()
        .runDelayed(plugin, task -> whenEntitiesLoaded(chunk, ticksLeft - 1, then), 1);
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
