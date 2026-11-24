// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Finding a dashboard from a game address (questions 336). */
class DiscoveryTest {
  private static final Optional<URI> URL = Optional.of(URI.create("https://seasons.example.com/"));

  @Test
  void gameAddressesAreNormalisedAndIpAddressesRefused() {
    assertThat(Discovery.normalise(" Play.Example.com. ")).contains("play.example.com");
    assertThat(Discovery.normalise("play.example.com:25565")).contains("play.example.com");
    assertThat(Discovery.normalise("play.example.com:25566")).contains("play.example.com:25566");
    assertThat(Discovery.normalise("203.0.113.7")).isEmpty();
    assertThat(Discovery.normalise("localhost")).isEmpty();
    assertThat(Discovery.normalise("play.example.com:99999")).isEmpty();
    assertThat(Discovery.normalise("https://play.example.com")).isEmpty();
  }

  @Test
  void theRecordIsNamedAfterTheHostAndNamesTheDashboard() {
    assertThat(Discovery.recordName("play.example.com:25566"))
        .isEqualTo("_seasons.play.example.com");
    assertThat(Discovery.recordValue(URL.get()))
        .isEqualTo("v=seasons1; url=https://seasons.example.com");
    assertThat(Discovery.dashboard("\"v=seasons1; url=https://seasons.example.com\""))
        .contains("https://seasons.example.com");
    assertThat(Discovery.dashboard("v=spf1 include:example.com ~all")).isEmpty();
    assertThat(Discovery.dashboard("v=seasons1")).isEmpty();
  }

  @Test
  void recordsNeedAPublicUrlAndAddresses() {
    assertThat(Discovery.records(List.of("play.example.com"), Optional.empty()))
        .extracting(Line::key)
        .containsExactly("discovery.no-url");
    assertThat(Discovery.records(List.of(), URL))
        .extracting(Line::key)
        .containsExactly("discovery.no-addresses");
    List<Line> lines =
        Discovery.records(
            List.of("play.example.com", "play.example.com:25566", "mc.example.com"), URL);
    assertThat(lines)
        .extracting(Line::key)
        .containsExactly(
            "discovery.header", "discovery.record", "discovery.record", "discovery.then-check");
    assertThat(lines.get(1).values())
        .containsEntry("name", "_seasons.play.example.com.")
        .containsEntry("value", "v=seasons1; url=https://seasons.example.com");
  }

  @Test
  void theCheckSaysWhatItFoundForEachAddress() {
    Map<String, List<String>> dns =
        Map.of(
            "_seasons.ok.example.com", List.of("v=seasons1; url=https://seasons.example.com"),
            "_seasons.other.example.com", List.of("v=seasons1; url=https://elsewhere.example.net"),
            "_seasons.unlisted.example.com",
                List.of("v=seasons1; url=https://seasons.example.com/"));
    Discovery.Resolver resolver =
        name ->
            name.equals("_seasons.broken.example.com")
                ? CompletableFuture.failedFuture(new IllegalStateException("no DNS"))
                : CompletableFuture.completedFuture(dns.getOrDefault(name, List.of()));
    Discovery.Asker asker =
        origin -> CompletableFuture.completedFuture(Optional.of(List.of("ok.example.com")));
    List<Line> lines =
        Discovery.check(
                List.of(
                    "ok.example.com",
                    "none.example.com",
                    "other.example.com",
                    "unlisted.example.com",
                    "broken.example.com"),
                URL,
                resolver,
                asker)
            .join();
    assertThat(lines)
        .extracting(Line::key)
        .containsExactly(
            "discovery.ok",
            "discovery.missing",
            "discovery.elsewhere",
            "discovery.not-listed",
            "discovery.lookup-failed");
  }

  @Test
  void aDashboardThatDoesNotAnswerIsSaidSo() {
    List<Line> lines =
        Discovery.check(
                List.of("play.example.com"),
                URL,
                name ->
                    CompletableFuture.completedFuture(
                        List.of("v=seasons1; url=https://seasons.example.com")),
                origin -> CompletableFuture.completedFuture(Optional.empty()))
            .join();
    assertThat(lines).extracting(Line::key).containsExactly("discovery.unreachable");
  }
}
