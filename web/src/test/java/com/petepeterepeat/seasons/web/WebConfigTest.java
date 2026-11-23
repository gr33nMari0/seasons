// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebConfigTest {
  private static WebSettings settings(String bind, String publicUrl, List<WebSettings.Link> links) {
    WebSettings d = SettingsFile.defaults();
    return new WebSettings(
        1,
        true,
        bind,
        publicUrl,
        "",
        true,
        d.proxy(),
        links,
        Map.of(),
        d.sessions(),
        d.rateLimits(),
        d.stream(),
        d.cache());
  }

  private static WebConfig.Result check(String bind, String publicUrl) {
    return WebConfig.check(settings(bind, publicUrl, List.of()));
  }

  @Test
  void offByDefaultOnPort8019() {
    WebSettings d = SettingsFile.defaults();
    assertThat(d.enabled()).isFalse();
    assertThat(d.bind()).isEqualTo("127.0.0.1:8019");
    assertThat(d.open()).isTrue();
    assertThat(d.sessions().lifetime()).hasDays(30);
    assertThat(WebConfig.check(d)).isInstanceOf(WebConfig.Off.class);
  }

  @Test
  void localhostAlwaysOnAnyPortTheOwnerChooses() {
    assertThat(check("127.0.0.1:8019", "")).isInstanceOf(WebConfig.Ready.class);
    WebConfig.Ready ready = (WebConfig.Ready) check("127.0.0.1:8124", "");
    assertThat(ready.config().bind().getPort()).isEqualTo(8124);
    assertThat(check("localhost:9000", "")).isInstanceOf(WebConfig.Ready.class);
    assertThat(check("[::1]:9000", "")).isInstanceOf(WebConfig.Ready.class);
  }

  @Test
  void beyondLocalhostOnlyWithAnHttpsPublicUrl() {
    assertThat(check("0.0.0.0:8019", ""))
        .isInstanceOfSatisfying(
            WebConfig.Off.class, off -> assertThat(off.reason()).contains("public-url"));
    assertThat(check("192.168.1.5:8019", "")).isInstanceOf(WebConfig.Off.class);
    assertThat(check("0.0.0.0:8019", "http://seasons.example.com"))
        .isInstanceOfSatisfying(
            WebConfig.Off.class, off -> assertThat(off.reason()).contains("https"));
    assertThat(check("0.0.0.0:8019", "https://seasons.example.com/"))
        .isInstanceOf(WebConfig.Ready.class);
  }

  @Test
  void aPublicUrlIsJustHttpsAndAHost() {
    assertThat(WebConfig.publicUrl("https://seasons.example.com/"))
        .map(Object::toString)
        .contains("https://seasons.example.com");
    for (String bad :
        List.of(
            "seasons.example.com",
            "https://seasons.example.com/dash",
            "https://a.b/?x=1",
            "ftp://a.b")) {
      assertThatThrownBy(() -> WebConfig.publicUrl(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void badAddressesAreRefusedWithoutLookingUpNames() {
    for (String bad :
        List.of("8019", "example.com:8019", "127.0.0.1:0", "127.0.0.1:70000", "127.0.0.1:x")) {
      assertThatThrownBy(() -> WebConfig.bind(bad)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void linksMustBeHttpsAndFramesComeOnlyFromThem() {
    WebConfig.Result result =
        WebConfig.check(
            settings(
                "127.0.0.1:8019",
                "",
                List.of(
                    new WebSettings.Link(
                        "Playlist", "https://open.spotify.com/playlist/x", "spotify"),
                    new WebSettings.Link("Map", "https://map.example.com:8443/", "frame"),
                    new WebSettings.Link("Discord", "https://discord.gg/x", null),
                    new WebSettings.Link("Old map", "http://map.example.com:25572", null),
                    new WebSettings.Link("Fake", "https://evil.example.com", "spotify"))));
    WebConfig config = ((WebConfig.Ready) result).config();
    assertThat(config.links())
        .extracting(WebSettings.Link::label)
        .containsExactly("Playlist", "Map", "Discord");
    assertThat(config.frameOrigins())
        .containsExactly("https://map.example.com:8443", "https://open.spotify.com");
    assertThat(result.warnings()).anyMatch(w -> w.contains("Old map") && w.contains("https"));
    assertThat(result.warnings()).anyMatch(w -> w.contains("Fake"));
  }
}
