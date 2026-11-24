// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The public docs' command and permission references are generated from the code (M9). When a test
 * here fails, run {@code ./gradlew commandReference permissionReference} and commit the result,
 * with the docs page that explains the change (AGENTS.md).
 */
class SiteReferenceTest {
  private static final Path DATA = Path.of("..", "site", "src", "data");
  private static final String REGENERATE =
      "out of date: run ./gradlew commandReference permissionReference";

  @Test
  void theCommandReferenceIsTheGeneratedOne() throws IOException {
    assertThat(Files.readString(DATA.resolve("commands/seasons.json")))
        .as("site/src/data/commands/seasons.json " + REGENERATE)
        .isEqualTo(SiteReference.commands());
  }

  @Test
  void thePermissionReferenceIsTheGeneratedOne() throws IOException {
    assertThat(Files.readString(DATA.resolve("permissions.json")))
        .as("site/src/data/permissions.json " + REGENERATE)
        .isEqualTo(SiteReference.permissions(Path.of("..")));
  }

  @Test
  void everyCommandHasItsWordsUsageAndHelp() throws IOException {
    String json = SiteReference.commands();
    assertThat(json)
        .contains("{\"words\": [\"hof\", \"link\", \"go\"]")
        .contains("\"/season unlock <target: homecoming, or a zone id>\"")
        .doesNotContain("\"description\": \"\"")
        .contains("\"permissions\": [\"seasons.admin.season\"]");
  }

  @Test
  void helpTextLosesItsTags() {
    assertThat(SiteReference.plain("<gray>Go <white>there</white>.</gray>")).isEqualTo("Go there.");
    assertThat(SiteReference.string("a \"b\"\n")).isEqualTo("\"a \\\"b\\\"\\n\"");
  }
}
