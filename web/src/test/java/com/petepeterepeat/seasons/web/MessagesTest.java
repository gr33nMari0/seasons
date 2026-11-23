// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
  @TempDir Path dir;

  @Test
  void everyLineByItsDottedKey() throws Exception {
    SettingsFile.writeDefault(dir, SettingsFile.MESSAGES);
    Messages messages = Messages.load(dir);
    assertThat(messages.get("dashboard-off"))
        .contains("The Seasons dashboard is off on this server.");
    assertThat(messages.get("link.linked")).contains("<player>");
  }
}
