// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Optional;

/**
 * What a structure file holds, as far as stamping cares (questions 234 and 235).
 *
 * @param blocks every block it sets, air included (structure voids set nothing)
 * @param entities every entity it holds
 */
public record TemplateScan(
    int sizeX, int sizeY, int sizeZ, List<Block> blocks, List<Entity> entities) {
  public TemplateScan {
    blocks = List.copyOf(blocks);
    entities = List.copyOf(entities);
  }

  /**
   * One block, at a position relative to the structure's minimum corner.
   *
   * @param type a namespaced block id, such as {@code minecraft:barrel}
   * @param items how many items it holds: a container's, a lectern's book, a jukebox's disc
   * @param marker for a structure block, its metadata if it is in DATA mode, else empty
   * @param structureBlock whether it is a structure block of any mode
   */
  public record Block(
      int x,
      int y,
      int z,
      String type,
      int items,
      Optional<String> marker,
      boolean structureBlock) {}

  /**
   * One entity.
   *
   * @param type a namespaced entity type, such as {@code minecraft:item_frame}
   * @param items how many items it carries, worn, held, framed or inside it
   */
  public record Entity(String type, int items) {}
}
