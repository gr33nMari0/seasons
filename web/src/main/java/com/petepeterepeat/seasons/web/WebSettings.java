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
 * @param open whether visitors who aren't logged in see the public widgets ({@code public})
 * @param visibility each widget's or field's visibility: public, players, admins or hidden
 * @param screens second-screen layouts by name, shown at {@code /screen/<name>}; {@code
 *     second-screen} is the one at {@code /screen}
 */
@ConfigSerializable
record WebSettings(
    int schemaVersion,
    boolean enabled,
    String bind,
    String publicUrl,
    String name,
    @Setting("public") boolean open,
    Proxy proxy,
    List<Link> links,
    Map<String, String> visibility,
    Sessions sessions,
    RateLimits rateLimits,
    Stream stream,
    Cache cache,
    Map<String, Screen> screens) {
  WebSettings {
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
}
