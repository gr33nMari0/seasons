// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

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
}
