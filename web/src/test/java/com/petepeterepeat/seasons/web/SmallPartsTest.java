// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.api.SeasonsApi;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmallPartsTest {
  @TempDir Path dir;

  private Messages messages() throws Exception {
    return Messages.load(dir.resolve("no-folder"));
  }

  /** A Seasons whose API, from before 1.2, has no apiVersion(). */
  private static SeasonsApi seasonsWithApi(Optional<String> apiVersion) {
    return (SeasonsApi)
        Proxy.newProxyInstance(
            SeasonsApi.class.getClassLoader(),
            new Class<?>[] {SeasonsApi.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "version" -> "1.1.0";
                  case "apiVersion" ->
                      apiVersion.orElseThrow(() ->
                          new NoSuchMethodError(
                              "'java.lang.String com.petepeterepeat.seasons.api.SeasonsApi.apiVersion()'"));
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  @Test
  void theApiVersionOfASeasonsFromBeforeItHadOne() {
    assertThat(ApiVersions.of(seasonsWithApi(Optional.empty()))).isEmpty();
    assertThat(ApiVersions.of(seasonsWithApi(Optional.of("1.2.0")))).contains("1.2.0");
  }

  @Test
  void apiVersions() {
    assertThat(ApiVersions.supported("1.3.0")).isTrue();
    assertThat(ApiVersions.supported("1.9.3")).isTrue();
    assertThat(ApiVersions.supported("1.2.0")).as("no museum, history or admin lists").isFalse();
    assertThat(ApiVersions.supported("1.1.0")).isFalse();
    assertThat(ApiVersions.supported("2.0.0")).isFalse();
    assertThat(ApiVersions.supported("garbage")).isFalse();
  }

  @Test
  void deviceLabels() throws Exception {
    Messages m = messages();
    assertThat(
            Devices.label(
                m,
                "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"))
        .isEqualTo("Safari on iPad");
    assertThat(
            Devices.label(
                m,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"))
        .isEqualTo("Firefox on Windows");
    assertThat(Devices.label(m, "")).isEqualTo("Unknown device");
    assertThat(Devices.label(m, "Mozilla/5.0 (X11; Linux x86_64)")).isEqualTo("Linux");
  }

  @Test
  void deviceLabelsFollowMessagesYml() throws Exception {
    java.nio.file.Files.writeString(
        dir.resolve("messages.yml"),
        "sessions:\n  device: \"<browser> sur <system>\"\n  unknown-device: \"Appareil inconnu\"\n");
    Messages m = Messages.load(dir);
    assertThat(
            Devices.label(
                m, "Mozilla/5.0 (Windows NT 10.0; rv:140.0) Gecko/20100101 Firefox/140.0"))
        .isEqualTo("Firefox sur Windows");
    assertThat(Devices.label(m, "")).isEqualTo("Appareil inconnu");
  }

  @Test
  void durations() {
    assertThat(DurationText.parse("30d")).isEqualTo(Duration.ofDays(30));
    assertThat(DurationText.parse("5s")).isEqualTo(Duration.ofSeconds(5));
    assertThat(DurationText.format(Duration.ofDays(30))).isEqualTo("30d");
  }

  @Test
  void ago() throws Exception {
    Messages m = messages();
    Instant now = Instant.parse("2026-11-23T10:00:00Z");
    assertThat(WebCommands.ago(m, now, now)).isEqualTo("just now");
    assertThat(WebCommands.ago(m, now, now.minusSeconds(600))).isEqualTo("10 min ago");
    assertThat(WebCommands.ago(m, now, now.minusSeconds(7200))).isEqualTo("2 h ago");
    assertThat(WebCommands.ago(m, now, now.minus(Duration.ofDays(3)))).isEqualTo("3 d ago");
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
