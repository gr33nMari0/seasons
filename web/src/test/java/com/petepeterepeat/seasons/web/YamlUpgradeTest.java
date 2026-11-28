// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** /web upgrade: what web.yml lacks, added with its comments, the admin's lines kept (O3). */
class YamlUpgradeTest {
  private static final String OLD =
      """
      # Our dashboard. Ask Alex before changing anything.
      schema-version: 1
      enabled: true
      bind: 127.0.0.1:8019
      name: Example SMP   # shown on the connect page
      proxy:
        trusted: [127.0.0.1]

      sessions:
        lifetime: 14d
      """;

  private static String bundled() throws Exception {
    try (InputStream in = SettingsFile.class.getResourceAsStream("web.yml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static ConfigurationNode node(String text) throws Exception {
    return YamlConfigurationLoader.builder().buildAndLoadString(text);
  }

  @Test
  void addsWhatIsMissingAndKeepsEveryLine() throws Exception {
    String bundled = bundled();
    List<String> missing = SettingsAudit.missing(node(OLD), node(bundled));
    assertThat(missing)
        .contains("public-url", "game-addresses", "proxy.client-ip-header", "previews", "help")
        .doesNotContain("proxy", "proxy.trusted", "sessions", "name");

    YamlUpgrade.Result r = YamlUpgrade.add(OLD, bundled, missing);
    assertThat(r.byHand()).isEmpty();
    assertThat(r.added()).containsExactlyElementsOf(missing);
    for (String line : OLD.strip().lines().toList()) {
      assertThat(r.text()).contains(line);
    }
    assertThat(r.text())
        .contains("proxy:\n  trusted: [127.0.0.1]\n  client-ip-header: CF-Connecting-IP\n")
        .contains("previews:\n  enabled: true\n  skins: true")
        .contains("# Rich previews when a dashboard link is shared")
        .contains("public-url: \"[fill me]\"")
        .contains("#  - { label: Discord");
    // Nothing missing afterwards, and the file still reads.
    assertThat(SettingsAudit.missing(node(r.text()), node(bundled))).isEmpty();
    assertThat(SettingsAudit.placeholders(node(r.text()))).containsExactly("public-url");
    assertThat(WebConfig.publicUrl("[fill me]")).as("a placeholder is unset").isEmpty();
  }

  @Test
  void aSectionOnOneLineIsLeftForTheAdmin() throws Exception {
    String text = "schema-version: 1\nproxy: {trusted: [127.0.0.1]}\n";
    YamlUpgrade.Result r = YamlUpgrade.add(text, bundled(), List.of("proxy.client-ip-header"));
    assertThat(r.added()).isEmpty();
    assertThat(r.byHand()).singleElement().asString().contains("one line");
    assertThat(r.text()).isEqualTo(text);
  }

  @Test
  void aDeeperIndentIsFollowed() throws Exception {
    String text = "schema-version: 1\nproxy:\n    trusted: []\n";
    YamlUpgrade.Result r = YamlUpgrade.add(text, bundled(), List.of("proxy.client-ip-header"));
    assertThat(r.text()).isEqualTo(
        "schema-version: 1\nproxy:\n    trusted: []\n    client-ip-header: CF-Connecting-IP\n");
  }

  @Test
  void unknownSettingsAndTheOneMeant() throws Exception {
    ConfigurationNode written =
        node("pubic-url: x\nproxy:\n  trustd: []\nscreens:\n  mine:\n    widgets: []\n");
    assertThat(SettingsAudit.unknown(written, node(bundled())))
        .containsExactly(
            new SettingsAudit.Unknown("pubic-url", java.util.Optional.of("public-url")),
            new SettingsAudit.Unknown("proxy.trustd", java.util.Optional.of("proxy.trusted")));
  }
}
