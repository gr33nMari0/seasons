// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
  @TempDir Path dir;

  @Test
  void writesTheDefaultsOnFirstLoad() throws Exception {
    Messages messages = Messages.load(dir);

    assertThat(dir.resolve("messages.yml")).exists();
    assertThat(messages.get("status.header", "version", "1.0.0", "platform", "Paper"))
        .isEqualTo(
            new Message(
                Messages.defaults().get("status.header").template(),
                java.util.Map.of("version", "1.0.0", "platform", "Paper")));
  }

  @Test
  void theServerCopyOverridesAndDefaultsFillTheRest() throws Exception {
    Files.writeString(dir.resolve("messages.yml"), "status:\n  header: \"Hello <version>\"\n");

    Messages messages = Messages.load(dir);

    assertThat(messages.get("status.header").template()).isEqualTo("Hello <version>");
    assertThat(messages.get("status.no-season").template())
        .isEqualTo(Messages.defaults().get("status.no-season").template());
  }

  @Test
  void aMissingKeyNamesItself() {
    Message message = Messages.defaults().get("no.such.key");

    assertThat(message.placeholders()).containsEntry("key", "no.such.key");
  }
}
