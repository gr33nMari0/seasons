// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Optional;

/** The End's dragon fight. */
public interface DragonFight {
  /** Clears or sets the flag that decides whether the next dragon drops an egg. */
  void setPreviouslyKilled(boolean previouslyKilled);

  Optional<Position> exitPortal();
}
