// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.message.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Renders core messages as Adventure components. */
final class PaperText {
  private final MiniMessage miniMessage = MiniMessage.miniMessage();

  /** Placeholders are inserted unparsed, so player text is always a plain literal. */
  Component render(Message message) {
    TagResolver.Builder resolvers = TagResolver.builder();
    message
        .placeholders()
        .forEach((name, value) -> resolvers.resolver(Placeholder.unparsed(name, value)));
    return miniMessage.deserialize(message.template(), resolvers.build());
  }
}
