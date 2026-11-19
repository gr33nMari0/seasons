// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Entities by id: where they are, moving them, and who owns them. */
public interface Entities {
  Optional<Position> position(UUID entity);

  /** Teleports a loaded entity, players included. Completes with false if it could not. */
  CompletableFuture<Boolean> teleport(UUID entity, Position destination);

  /** The owner of a tamed animal. */
  Optional<PlayerId> owner(UUID entity);

  /** What the game says about a loaded entity, if it is loaded. */
  Optional<PetInfo> pet(UUID entity);

  /**
   * Marks a loaded entity as a player's registered pet, so {@link PetEvents} hear about it, or
   * takes the mark off. Does nothing if it isn't loaded.
   */
  void markPet(UUID entity, Optional<PlayerId> owner);

  /**
   * Finds an entity, loading the chunk at its last known position without stalling the tick and
   * waiting for that chunk's entities. If it is there, the chunk stays loaded until {@link #bring}
   * or {@link #release}. Completes with what the game says about it, or empty if it isn't there.
   */
  CompletableFuture<Optional<PetInfo>> load(UUID entity, Optional<Position> lastSeen);

  /**
   * Unleashes an entity, takes it out of any vehicle and off anything riding it, then teleports it,
   * across dimensions if needed, and lets go of the chunk {@link #load} kept. Completes with
   * whether it arrived.
   */
  CompletableFuture<Boolean> bring(UUID entity, Position destination);

  /** Lets go of the chunk {@link #load} kept loaded for an entity that isn't brought. */
  void release(UUID entity);

  /**
   * Where the monsters are within a box around a position, as vanilla counts them for sleeping:
   * hostile mobs, and neutral ones only while angry.
   *
   * @param dx half the box's width along x; likewise {@code dy} and {@code dz}
   */
  List<Position> hostiles(Position centre, double dx, double dy, double dz);
}
