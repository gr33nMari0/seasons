// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.message;

import java.util.Map;

/**
 * Player-facing text: a MiniMessage template from messages.yml and the values for its placeholders.
 * Adapters insert placeholder values as plain literal text, never as formatting, so player names
 * and other player text can't inject formatting or click events.
 *
 * @param template a MiniMessage string
 * @param placeholders values by placeholder name, such as {@code version} for {@code <version>}
 */
public record Message(String template, Map<String, String> placeholders) {
  public Message {
    placeholders = Map.copyOf(placeholders);
  }

  public static Message of(String template) {
    return new Message(template, Map.of());
  }
}
