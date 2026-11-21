// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
   * structures, entities included. Placing the same copy again leaves it as one copy: first every
   * entity an earlier placement under {@code tag} left (around the box between {@code min} and
   * {@code max}, the copy's footprint) is removed, once the entities there have loaded, then the
   * structure is placed and every entity it added is marked with {@code tag} (questions 234).
   *
   * @param tag the stamp's id and the copy's index, such as {@code abc-1/0}
   * @return completes on the server thread, in the tick the structure was placed in; fails, having
   *     placed nothing, if the entities around the copy can't be loaded
   */
  CompletableFuture<Void> place(
      byte[] structure, Position origin, int rotation, Position min, Position max, String tag);
}
