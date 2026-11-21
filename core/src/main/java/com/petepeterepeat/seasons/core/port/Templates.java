// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Optional;

/**
 * Cell templates (questions 230 to 235): vanilla structure files, which the core finds and checks,
 * and the adapter reads and places.
 */
public interface Templates {
  /**
   * A structure a structure block saved as {@code seasons:<name>}, kept by the game in memory or
   * under the world's {@code generated/seasons/structures/}, as structure file bytes.
   */
  Optional<byte[]> saved(String name);

  /** The names of the structures saved under the {@code seasons} namespace. */
  List<String> savedNames();

  /**
   * Reads a structure file.
   *
   * @throws IllegalArgumentException if it isn't a structure file the game can read
   */
  TemplateScan scan(byte[] structure);

  /**
   * Places a structure with its minimum corner (before turning) at a block, turned clockwise seen
   * from above by {@code rotation} degrees (0, 90, 180 or 270) about that corner, as the game turns
   * structures, entities included. Call on the server thread, with its chunks loaded.
   */
  void place(byte[] structure, Position origin, int rotation);
}
