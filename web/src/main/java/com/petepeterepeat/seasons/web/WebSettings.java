// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * {@code plugins/SeasonsWeb/web.yml}, as written: checked by {@link WebConfig} before anything
 * listens. Keys are lower-case with dashes, such as {@code public-url}.
 *
 * @param bind the address and port to listen on, such as {@code 127.0.0.1:8019}
 * @param publicUrl the https address players use, such as {@code https://seasons.example.com};
 *     empty while only localhost is bound
 * @param name the server's name on the connect page; empty for the server's MOTD
 * @param gameAddresses the addresses players join the game with, such as {@code play.example.com},
 *     listed in {@code /.well-known/seasons.json} so the public connect page can confirm it found
 *     the right dashboard (questions 336)
 * @param open whether visitors who aren't logged in see the public widgets ({@code public})
 * @param visibility each widget's or field's visibility: public, players, admins or hidden
 * @param screens second-screen layouts by name, shown at {@code /screen/<name>}; {@code
 *     second-screen} is the one at {@code /screen}
 * @param help the dashboard's Help page: this server's own links and rules beside the bundled
 *     players' guides (M9)
 * @param previews rich link previews: the tags and pictures shared links unfurl with (M9)
 */
@ConfigSerializable
record WebSettings(
    int schemaVersion,
    boolean enabled,
    String bind,
    String publicUrl,
    String name,
    List<String> gameAddresses,
    @Setting("public") boolean open,
    Proxy proxy,
    List<Link> links,
    Map<String, String> visibility,
    Sessions sessions,
    RateLimits rateLimits,
    Stream stream,
    Cache cache,
    Map<String, Screen> screens,
    Help help,
    Previews previews) {
  WebSettings {
    gameAddresses = List.copyOf(gameAddresses);
    links = List.copyOf(links);
    visibility = Map.copyOf(visibility);
    screens = Map.copyOf(screens);
  }

  /**
   * The tunnel or reverse proxy in front.
   *
   * @param trusted addresses or CIDR ranges whose client-ip-header is believed
   * @param clientIpHeader the header naming the visitor's address, such as {@code CF-Connecting-IP}
   */
  @ConfigSerializable
  record Proxy(List<String> trusted, String clientIpHeader) {
    Proxy {
      trusted = List.copyOf(trusted);
    }
  }

  /**
   * A link on the dashboard.
   *
   * @param embed {@code spotify} or {@code frame} to show it inside the dashboard; empty for a link
   */
  @ConfigSerializable
  record Link(String label, String url, @Nullable String embed) {}

  /** How long a login lasts. */
  @ConfigSerializable
  record Sessions(Duration lifetime) {}

  /** Requests allowed per minute. */
  @ConfigSerializable
  record RateLimits(
      int requestsPerMinutePerIp,
      int requestsPerMinutePerAccount,
      int deviceCodesPerMinutePerIp,
      int wrongCodesPerMinutePerAccount) {}

  /**
   * The event stream.
   *
   * @param queue events kept for a client that falls behind; the oldest go first
   */
  @ConfigSerializable
  record Stream(int maxPerIp, int maxPerAccount, int maxTotal, int queue) {}

  /**
   * A second screen: a landscape layout of widgets, in order.
   *
   * @param layout {@code landscape}, the only layout
   * @param widgets widget names, such as {@code season-clock} or {@code event-feed}
   */
  @ConfigSerializable
  record Screen(String layout, List<String> widgets) {
    Screen {
      widgets = List.copyOf(widgets);
    }
  }

  /** How long public responses may be cached by a proxy. */
  @ConfigSerializable
  record Cache(Duration publicMaxAge) {}

  /**
   * The Help page's own part: links, and a Markdown file of rules in {@code plugins/SeasonsWeb/}.
   *
   * @param rules a file name such as {@code rules.md}; empty for none
   */
  @ConfigSerializable
  record Help(List<Link> links, String rules) {
    Help {
      links = List.copyOf(links);
    }
  }

  /**
   * Rich link previews.
   *
   * @param enabled whether pages carry preview tags and their pictures are drawn
   * @param skins whether players' heads are drawn from their skins, fetched once from Mojang's
   *     texture server; false draws a plain figure
   */
  @ConfigSerializable
  record Previews(boolean enabled, boolean skins) {}
}
