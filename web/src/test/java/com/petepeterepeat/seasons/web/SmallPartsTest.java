// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmallPartsTest {
  @Test
  void apiVersions() {
    assertThat(ApiVersions.supported("1.2.0")).isTrue();
    assertThat(ApiVersions.supported("1.9.3")).isTrue();
    assertThat(ApiVersions.supported("1.1.0")).isFalse();
    assertThat(ApiVersions.supported("2.0.0")).isFalse();
    assertThat(ApiVersions.supported("garbage")).isFalse();
  }

  @Test
  void deviceLabels() {
    assertThat(
            Devices.label(
                "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"))
        .isEqualTo("Safari on iPad");
    assertThat(
            Devices.label(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"))
        .isEqualTo("Firefox on Windows");
    assertThat(Devices.label("")).isEqualTo("Unknown device");
  }

  @Test
  void durations() {
    assertThat(DurationText.parse("30d")).isEqualTo(Duration.ofDays(30));
    assertThat(DurationText.parse("5s")).isEqualTo(Duration.ofSeconds(5));
    assertThat(DurationText.format(Duration.ofDays(30))).isEqualTo("30d");
  }

  @Test
  void ago() {
    Instant now = Instant.parse("2026-11-23T10:00:00Z");
    assertThat(WebCommands.ago(now, now)).isEqualTo("just now");
    assertThat(WebCommands.ago(now, now.minusSeconds(600))).isEqualTo("10 min ago");
  }

  @Test
  void theSecurityPolicy() {
    String csp =
        WebServer.securityPolicy(List.of("https://map.example.com", "https://open.spotify.com"));
    assertThat(csp)
        .contains("default-src 'self'")
        .contains("script-src 'self'")
        .contains("frame-src https://map.example.com https://open.spotify.com")
        .contains("frame-ancestors 'none'")
        .contains("img-src 'self' https://textures.minecraft.net");
    assertThat(WebServer.securityPolicy(List.of())).contains("frame-src 'none'");
  }

  @Test
  void cookies() {
    String line = Routes.cookie(Routes.SESSION_COOKIE, "abc", 2_592_000);
    assertThat(line)
        .startsWith("__Host-seasons_session=abc;")
        .contains("Path=/", "Max-Age=2592000", "HttpOnly", "Secure", "SameSite=Strict")
        .doesNotContain("Domain");
  }
}
