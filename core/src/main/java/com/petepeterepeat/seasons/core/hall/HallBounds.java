// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;

/** The Hall's protected area. Filled in M1 part 3; until then nothing is inside it. */
@FunctionalInterface
public interface HallBounds {
  boolean contains(Position position);

  /** No Hall yet. */
  static HallBounds none() {
    return position -> false;
  }
}
