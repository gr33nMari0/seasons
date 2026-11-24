// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.Optional;

/** Who ran a command: a player, or the console. */
public interface CommandSource {
  /** The player who ran it, or empty for the console. */
  Optional<PlayerId> player();

  void reply(Message message);

  /**
   * The players a vanilla selector or a name picks, as seen from where the source is (M8, {@code
   * /hof link send}), and how many other entities it picked too; empty if this source can't read
   * selectors, or the selector is bad.
   */
  default Optional<Selected> select(String selector) {
    return Optional.empty();
  }

  /** Whether a command block runs the command. */
  default boolean commandBlock() {
    return false;
  }

  /** What a selector picked. */
  record Selected(java.util.List<PlayerId> players, int others) {
    public Selected {
      players = java.util.List.copyOf(players);
    }
  }
}
