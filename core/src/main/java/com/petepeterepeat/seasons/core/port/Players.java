// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.Collection;
import java.util.Optional;

/** Online players, profiles and skins, game mode, health, XP, effects, messages and titles. */
public interface Players {
  Collection<PlayerRef> online();

  Optional<PlayerRef> find(PlayerId id);

  /** Sends a message. Placeholders are always inserted as literal text. */
  void sendMessage(PlayerId id, Message message);

  /** Sends a message to every online player and the console. */
  void broadcast(Message message);

  /**
   * Shows a boss bar to every online player.
   *
   * @param progress from 0 (empty) to 1 (full)
   */
  ProgressBar showProgressBar(Message title, float progress);
}
