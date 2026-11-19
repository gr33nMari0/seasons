// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.UUID;

/** What happens to entities marked as registered pets (see {@link Entities#markPet}). */
public interface PetEvents {
  /** A pet left the loaded world without being removed for good, as when its chunk unloads. */
  void unloaded(UUID pet, PlayerId owner, Position at);

  /**
   * A pet was removed from the world for good.
   *
   * @param died killed, rather than removed some other way (such as by a command)
   */
  void removed(UUID pet, PlayerId owner, boolean died);
}
