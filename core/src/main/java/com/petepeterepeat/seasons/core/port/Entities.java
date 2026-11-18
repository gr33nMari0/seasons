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

  /**
   * Where the monsters are within a box around a position, as vanilla counts them for sleeping:
   * hostile mobs, and neutral ones only while angry.
   *
   * @param dx half the box's width along x; likewise {@code dy} and {@code dz}
   */
  List<Position> hostiles(Position centre, double dx, double dy, double dz);
}
