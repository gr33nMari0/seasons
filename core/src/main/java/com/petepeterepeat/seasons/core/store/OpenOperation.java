// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.List;
import java.util.Optional;

/**
 * An operation the journal shows as started but not finished. The key (operation, player, season)
 * makes rerunning it harmless.
 *
 * @param steps its entries since the latest start, oldest first; never empty
 */
public record OpenOperation(
    Operation operation, Optional<PlayerId> player, int season, List<JournalEntry> steps) {
  public OpenOperation {
    steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("An open operation has at least its start entry");
    }
  }

  /** The last step written before the crash. */
  public JournalEntry lastStep() {
    return steps.getLast();
  }
}
