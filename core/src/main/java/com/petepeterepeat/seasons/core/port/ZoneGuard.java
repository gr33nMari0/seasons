// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;

/**
 * The zones' say on changes nobody in particular makes: explosions, fire, pistons, liquids,
 * hoppers, growth across an edge, and portals. The adapter asks it from the world's events and
 * refuses what it refuses. Every method runs on the server thread and must stay cheap: some events
 * fire often.
 */
public interface ZoneGuard {
  /** Nothing is guarded: before Seasons has started. */
  ZoneGuard NONE =
      new ZoneGuard() {
        @Override
        public boolean protects(Position block) {
          return false;
        }

        @Override
        public boolean allows(List<Position> blocks) {
          return true;
        }

        @Override
        public boolean blocks(Position at) {
          return false;
        }
      };

  /** Whether a block is in a blocked or no-build zone now, where nothing breaks or burns. */
  boolean protects(Position block);

  /**
   * Whether a change touching all these blocks, such as a piston's push or water flowing, stays on
   * one side of every blocked or no-build zone's edge.
   */
  boolean allows(List<Position> blocks);

  /** Whether nothing may enter a position now, as an entity through a portal. */
  boolean blocks(Position at);
}
