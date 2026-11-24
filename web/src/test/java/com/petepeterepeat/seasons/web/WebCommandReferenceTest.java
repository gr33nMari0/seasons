// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The public docs' /web reference is generated from the command tree (M9). When the first test
 * fails, run {@code ./gradlew commandReference} and commit the result with the docs page that
 * explains the change (AGENTS.md).
 */
class WebCommandReferenceTest {
  @TempDir Path dir;

  @Test
  void theReferenceIsTheGeneratedOne() throws Exception {
    Path committed = Path.of("..", "site", "src", "data", "commands", "web.json");
    assertThat(Files.readString(committed))
        .as("site/src/data/commands/web.json out of date: run ./gradlew commandReference")
        .isEqualTo(WebCommandReference.json(dir));
  }

  @Test
  void everyCommandHasHelpAndItsUsages() throws Exception {
    var entries = WebCommandReference.entries(dir);
    assertThat(entries).isNotEmpty();
    assertThat(entries)
        .allSatisfy(e -> assertThat(e.description()).doesNotStartWith("help.").isNotBlank());
    assertThat(entries)
        .anySatisfy(e -> assertThat(e.usages()).containsExactly("/web logout", "/web logout <device>"));
  }
}
