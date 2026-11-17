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
}
