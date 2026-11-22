// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The config reference is generated from the code: each setting's {@code @Comment} is the one
 * description of it, written into the bundled {@code config.yml} and {@code secrets.yml} and into
 * {@code docs/config.md}. When a test here fails, run {@code ./gradlew :core:configReference}.
 */
class ConfigReferenceTest {
  private static final String REGENERATE = "out of date: run ./gradlew :core:configReference";

  private static String bundled(String name) throws IOException {
    try (InputStream in =
        ConfigReferenceTest.class.getResourceAsStream("/com/petepeterepeat/seasons/core/" + name)) {
      assertThat(in).as(name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void everySettingHasAComment() {
    assertThat(ConfigReference.undocumented()).isEmpty();
  }

  @Test
  void theBundledConfigIsTheGeneratedOne() throws IOException {
    assertThat(bundled("config.yml"))
        .as("config.yml " + REGENERATE)
        .isEqualTo(ConfigReference.configYaml());
  }

  @Test
  void theBundledSecretsTemplateIsTheGeneratedOne() throws IOException {
    assertThat(bundled("secrets.template.yml"))
        .as("secrets.template.yml " + REGENERATE)
        .isEqualTo(ConfigReference.secretsYaml());
  }

  @Test
  void theReferenceInDocsIsTheGeneratedOne() throws IOException {
    Path docs = Path.of("..", "docs", "config.md");
    assertThat(Files.readString(docs))
        .as("docs/config.md " + REGENERATE)
        .isEqualTo(ConfigReference.markdown());
  }

  @Test
  void theGeneratedConfigHoldsEveryDefault() throws IOException {
    assertThat(ConfigLoader.parse(ConfigReference.configYaml()))
        .isEqualTo(ConfigLoader.defaults().config());
  }

  @Test
  void theReferenceListsEachSettingWithItsDefaultAndChoices() {
    String markdown = ConfigReference.markdown();
    assertThat(markdown)
        .contains("| `schedule.autoEnd` | `false` |")
        .contains("| `resets.xp` | `reset` |")
        .contains("One of `reset`, `keep`.")
        .contains("| `hof.entry.bedDistance` | `3` |")
        .contains("| `awards.most-deaths` | `{ measure: deaths, item: skeleton_skull }` |")
        .contains("| `discordWebhook` | `\"\"` |");
  }
}
