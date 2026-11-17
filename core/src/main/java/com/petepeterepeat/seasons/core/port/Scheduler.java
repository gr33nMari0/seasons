// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.time.Duration;

/**
 * Runs work in the right place. The core never assumes a thread: on Paper all of these but {@link
 * #runAsync} are the main thread; on a region-threaded platform they are regions.
 */
public interface Scheduler {
  /** Runs on the thread that owns global state. */
  void runGlobal(Runnable task);

  /** Runs later on the thread that owns global state. */
  void runGlobalLater(Duration delay, Runnable task);

  /** Runs on the thread that owns the player. Skipped if they are offline. */
  void runForPlayer(PlayerId player, Runnable task);

  /** Runs on the thread that owns the position. */
  void runAt(Position position, Runnable task);

  /** Runs off the game threads, for slow work on read-only snapshots. */
  void runAsync(Runnable task);
}
