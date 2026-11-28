// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How {@code /web check} sees its own request arrive (M10 plan, O2): it fetches the public-url's
 * well-known reply with a one-time {@code check} value, and the listener, seeing that value, notes
 * the address the request came from and whether it carried the client-ip header. That proves the
 * public address reaches this very dashboard, and shows what the proxy in front of it does.
 */
final class SelfCheck {
  /** What a self-check request looked like when it arrived. */
  record Arrival(InetAddress from, boolean header, boolean trusted) {}

  private final Map<String, CompletableFuture<Arrival>> waiting = new ConcurrentHashMap<>();

  /** A new one-time value and what will be seen when a request carrying it arrives. */
  CompletableFuture<Arrival> expect(String nonce) {
    CompletableFuture<Arrival> arrival = new CompletableFuture<>();
    waiting.put(nonce, arrival);
    var _ = arrival.whenComplete((a, e) -> waiting.remove(nonce));
    return arrival;
  }

  /** Called by the listener for every well-known request with a check value. */
  void arrived(String nonce, Arrival arrival) {
    CompletableFuture<Arrival> f = waiting.remove(nonce);
    if (f != null) {
      f.complete(arrival);
    }
  }
}
