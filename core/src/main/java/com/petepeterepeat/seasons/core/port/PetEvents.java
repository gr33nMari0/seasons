// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.UUID;

/** What happens to entities marked as registered pets (see {@link Entities#markPet}). */
public interface PetEvents {
  /**
   * A pet left the loaded world without dying, as when its chunk unloads or something removes it.
   * Only travel can tell whether it is still somewhere.
   */
  void unloaded(UUID pet, PlayerId owner, Position at);

  /** A pet was killed. */
  void died(UUID pet, PlayerId owner);

  /**
   * A parrot left the world to sit on its owner's shoulder, where it goes wherever they go until it
   * hops off.
   */
  void onShoulder(UUID pet, PlayerId owner);
}
