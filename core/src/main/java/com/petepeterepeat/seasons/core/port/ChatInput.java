// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/**
 * What a player types in chat while Seasons is asking them something, such as a curator's reason.
 * The adapter offers every chat line before the game sends it anywhere; a line Seasons takes is
 * never shown to anyone, nor passed on by a chat bridge.
 */
@FunctionalInterface
public interface ChatInput {
  ChatInput NONE = (player, text) -> false;

  /**
   * Offers one line. Called on whatever thread the platform delivers chat on, so it must be
   * thread-safe and hand any work to the server thread.
   *
   * @param text the line as plain text
   * @return whether Seasons took it, so it goes no further
   */
  boolean typed(PlayerId player, String text);
}
