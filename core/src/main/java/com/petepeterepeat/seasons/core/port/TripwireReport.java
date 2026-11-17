// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.List;
import java.util.Optional;

/**
 * Receives tripwire hits: items tagged as archived that turned up in the world, which the adapter
 * has already deleted.
 */
@FunctionalInterface
public interface TripwireReport {
  /**
   * Reports one hit.
   *
   * @param player whose inventory or pickup it was, if anyone's
   * @param where such as "in an inventory" or "on the ground", for the audit log
   * @param items what was deleted, such as "3 × minecraft:stone"
   */
  void found(Optional<PlayerId> player, String where, List<String> items);
}
