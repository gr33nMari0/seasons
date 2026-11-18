// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.event;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;

/** The core's answer to an intent, which the adapter applies. */
public sealed interface Decision {
  static Decision allow() {
    return Allow.INSTANCE;
  }

  static Decision deny(Message message) {
    return new Deny(Optional.of(message));
  }

  /** Let it happen, but with nothing dropped, as a block broken after its owner's archive. */
  static Decision allowWithoutDrops() {
    return AllowWithoutDrops.INSTANCE;
  }

  /** Let it happen. */
  enum Allow implements Decision {
    INSTANCE
  }

  /** Let it happen, but drop nothing: the block goes, and no item or experience comes of it. */
  enum AllowWithoutDrops implements Decision {
    INSTANCE
  }

  /** Stop it, and tell the player why if there is a message. */
  record Deny(Optional<Message> message) implements Decision {}

  /** Stop it and move the player to a position instead. */
  record Redirect(Position to, Optional<Message> message) implements Decision {}
}
