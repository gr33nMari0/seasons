// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The visitor's address for per-visitor limits. Behind a tunnel every request comes from the
 * tunnel, so the address in {@code proxy.client-ip-header} is used, but only when the request comes
 * from an address in {@code proxy.trusted}; from anywhere else the header is ignored. IPv6 visitors
 * are counted by their /64. The address is never stored, logged or served.
 */
final class ClientIp {
  private final List<Range> trusted;
  private final String header;

  private record Range(InetAddress base, int bits) {
    boolean contains(InetAddress address) {
      byte[] a = address.getAddress();
      byte[] network = base.getAddress();
      if (a.length != network.length) {
        return false;
      }
      int full = bits / 8;
      for (int i = 0; i < full; i++) {
        if (a[i] != network[i]) {
          return false;
        }
      }
      int rest = bits % 8;
      if (rest == 0) {
        return true;
      }
      int mask = 0xff << (8 - rest) & 0xff;
      return (a[full] & mask) == (network[full] & mask);
    }
  }

  /**
   * The trusted proxies and their header.
   *
   * @param trusted addresses or CIDR ranges, such as {@code 127.0.0.1} or {@code 10.0.0.0/8}
   */
  ClientIp(List<String> trusted, String header) {
    List<Range> ranges = new ArrayList<>();
    for (String entry : trusted) {
      String t = entry.strip();
      int slash = t.indexOf('/');
      InetAddress address;
      try {
        address = InetAddress.ofLiteral(slash < 0 ? t : t.substring(0, slash));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("\"" + entry + "\" isn't an address or CIDR range", e);
      }
      int max = address.getAddress().length * 8;
      int bits = max;
      if (slash >= 0) {
        try {
          bits = Integer.parseInt(t.substring(slash + 1));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("\"" + entry + "\" has a bad prefix length", e);
        }
        if (bits < 0 || bits > max) {
          throw new IllegalArgumentException("\"" + entry + "\" has a bad prefix length");
        }
      }
      ranges.add(new Range(address, bits));
    }
    this.trusted = List.copyOf(ranges);
    this.header = header.strip();
  }

  /** The header believed from trusted proxies. */
  String header() {
    return header;
  }

  boolean trusts(InetAddress peer) {
    return trusted.stream().anyMatch(r -> r.contains(peer));
  }

  /**
   * The visitor's key for rate limits.
   *
   * @param headerValue the client-ip-header's value, if the request had one
   */
  String key(InetAddress peer, Optional<String> headerValue) {
    InetAddress visitor = peer;
    if (headerValue.isPresent() && !header.isEmpty() && trusts(peer)) {
      String v = headerValue.get().strip();
      if (!v.contains(",")) {
        try {
          visitor = InetAddress.ofLiteral(v);
        } catch (IllegalArgumentException e) {
          // Not an address: counted as the proxy.
        }
      }
    }
    return key(visitor);
  }

  static String key(InetAddress address) {
    if (address instanceof Inet6Address) {
      byte[] b = address.getAddress();
      StringBuilder out = new StringBuilder("v6:");
      for (int i = 0; i < 8; i++) {
        out.append(String.format("%02x", b[i]));
      }
      return out.append("/64").toString();
    }
    return "v4:" + address.getHostAddress();
  }
}
