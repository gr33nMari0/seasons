// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;

/**
 * The Hall of Fame's say on changes nobody in particular makes: explosions, fire, pistons, liquids,
 * hoppers, growth and spawning. The adapter asks it from the world's events and refuses what it
 * refuses. Every method runs on the server thread and must stay cheap: some events fire often.
 */
public interface HallGuard {
  /** Nothing is protected: before Seasons has started. */
  HallGuard NONE =
      new HallGuard() {
        @Override
        public boolean protects(Position block) {
          return false;
        }

        @Override
        public boolean allows(List<Position> blocks) {
          return true;
        }

        @Override
        public void entitiesLoaded(String dimension, int chunkX, int chunkZ) {}
      };

  /** Whether a block is in the Hall, where nothing changes by itself. */
  boolean protects(Position block);

  /**
   * Whether a block is a link's, which stays even when what holds it up goes (a button's wall, a
   * plate's floor), since only the link's own block is protected outside the Hall.
   */
  default boolean holdsLink(Position block) {
    return false;
  }

  /**
   * Whether a change touching all these blocks may go ahead, such as a piston's push or water
   * flowing: none is in the Hall, or all are in one cell its owner may still decorate.
   */
  boolean allows(List<Position> blocks);

  /** The entities of a chunk have loaded, so the cells there can be reconciled. */
  void entitiesLoaded(String dimension, int chunkX, int chunkZ);
}
