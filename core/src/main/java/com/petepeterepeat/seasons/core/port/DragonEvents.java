// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Optional;
import java.util.UUID;

/** What the adapter reports of ender dragons in the End, for the dragon slayer and the egg. */
public interface DragonEvents {
  /**
   * A player hurt an ender dragon in the End: they caused the damage, by a hit, an arrow, a crystal
   * they set off or anything else of theirs.
   *
   * @param dealt the health it cost, never more than the dragon had
   */
  void hurt(UUID dragon, PlayerId player, double dealt);

  /**
   * An ender dragon died in the End.
   *
   * @param finalBlow the player who caused the killing damage, if a player did
   */
  void died(UUID dragon, Optional<PlayerId> finalBlow);

  /** The End was loaded. */
  void endLoaded();

  /** The End was saved, so a block changed in it before now survives a crash. */
  void endSaved();
}
