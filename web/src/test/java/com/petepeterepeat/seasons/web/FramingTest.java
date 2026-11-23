// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FramingTest {
  private static final String MAP = "https://map.example.com";
  private static final Optional<String> DASHBOARD = Optional.of("https://seasons.example.com");

  private static Framing.State csp(String policy) {
    return Framing.decide(List.of(policy), Optional.empty(), MAP, DASHBOARD);
  }

  private static Framing.State xfo(String value) {
    return Framing.decide(List.of(), Optional.of(value), MAP, DASHBOARD);
  }

  @Test
  void frameAncestorsNamingTheDashboardAllowIt() {
    assertThat(csp("frame-ancestors https://seasons.example.com")).isEqualTo(Framing.State.ALLOWED);
    assertThat(csp("default-src 'self'; frame-ancestors 'self' https://*.example.com"))
        .isEqualTo(Framing.State.ALLOWED);
    assertThat(csp("frame-ancestors *")).isEqualTo(Framing.State.ALLOWED);
    assertThat(csp("frame-ancestors https:")).isEqualTo(Framing.State.ALLOWED);
  }

  @Test
  void frameAncestorsNamingAnotherSiteOrNoneBlockIt() {
    assertThat(csp("frame-ancestors https://other.example")).isEqualTo(Framing.State.BLOCKED);
    assertThat(csp("frame-ancestors 'none'")).isEqualTo(Framing.State.BLOCKED);
    assertThat(csp("frame-ancestors 'self'")).isEqualTo(Framing.State.BLOCKED);
    assertThat(csp("frame-ancestors https://seasons.example.com:8443"))
        .as("another port")
        .isEqualTo(Framing.State.BLOCKED);
    assertThat(csp("frame-ancestors http://seasons.example.com"))
        .as("another scheme")
        .isEqualTo(Framing.State.BLOCKED);
  }

  @Test
  void frameAncestorsWinsOverXFrameOptions() {
    assertThat(
            Framing.decide(
                List.of("frame-ancestors https://seasons.example.com"),
                Optional.of("DENY"),
                MAP,
                DASHBOARD))
        .isEqualTo(Framing.State.ALLOWED);
  }

  @Test
  void xFrameOptions() {
    assertThat(xfo("DENY")).isEqualTo(Framing.State.BLOCKED);
    assertThat(xfo("sameorigin")).isEqualTo(Framing.State.BLOCKED);
    assertThat(
            Framing.decide(
                List.of(),
                Optional.of("SAMEORIGIN"),
                "https://seasons.example.com/map/",
                DASHBOARD))
        .as("BlueMap at /map on the dashboard's own address")
        .isEqualTo(Framing.State.ALLOWED);
    assertThat(xfo("ALLOW-FROM https://seasons.example.com"))
        .as("obsolete, ignored by browsers")
        .isEqualTo(Framing.State.ALLOWED);
  }

  @Test
  void noHeadersAllowIt() {
    assertThat(Framing.decide(List.of("default-src 'self'"), Optional.empty(), MAP, DASHBOARD))
        .isEqualTo(Framing.State.ALLOWED);
  }

  @Test
  void withoutAPublicUrlOnlyAnUnrestrictedMapIsKnownToFrame() {
    assertThat(
            Framing.decide(
                List.of("frame-ancestors https://seasons.example.com"),
                Optional.empty(),
                MAP,
                Optional.empty()))
        .isEqualTo(Framing.State.UNKNOWN);
    assertThat(Framing.decide(List.of(), Optional.empty(), MAP, Optional.empty()))
        .isEqualTo(Framing.State.ALLOWED);
  }
}
