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

  /**
   * Several messages as one, each on its own line, such as a plaque with an award line. Each
   * message's placeholders are renamed apart, so their values can't meet.
   */
  public static Message lines(java.util.List<Message> messages) {
    StringBuilder template = new StringBuilder();
    Map<String, String> values = new java.util.HashMap<>();
    for (int i = 0; i < messages.size(); i++) {
      Message message = messages.get(i);
      String line = message.template();
      for (Map.Entry<String, String> placeholder : message.placeholders().entrySet()) {
        String renamed = "l" + i + "_" + placeholder.getKey();
        line = line.replace("<" + placeholder.getKey() + ">", "<" + renamed + ">");
        values.put(renamed, placeholder.getValue());
      }
      if (i > 0) {
        template.append("<newline>");
      }
      template.append(line);
    }
    return new Message(template.toString(), values);
  }
}
