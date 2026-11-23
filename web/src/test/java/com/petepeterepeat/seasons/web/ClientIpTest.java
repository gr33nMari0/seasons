// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClientIpTest {
  private final ClientIp behindTunnel =
      new ClientIp(List.of("127.0.0.1", "10.0.0.0/8"), "CF-Connecting-IP");
  private final InetAddress localhost = InetAddress.ofLiteral("127.0.0.1");

  @Test
  void theHeaderFromATrustedProxyNamesTheVisitor() {
    assertThat(behindTunnel.key(localhost, Optional.of("203.0.113.7"))).isEqualTo("v4:203.0.113.7");
    assertThat(behindTunnel.key(InetAddress.ofLiteral("10.1.2.3"), Optional.of("198.51.100.1")))
        .isEqualTo("v4:198.51.100.1");
  }

  @Test
  void fromAnywhereElseTheHeaderIsIgnored() {
    InetAddress stranger = InetAddress.ofLiteral("192.0.2.9");
    assertThat(behindTunnel.key(stranger, Optional.of("203.0.113.7"))).isEqualTo("v4:192.0.2.9");
    ClientIp nobody = new ClientIp(List.of(), "CF-Connecting-IP");
    assertThat(nobody.key(localhost, Optional.of("203.0.113.7"))).isEqualTo("v4:127.0.0.1");
  }

  @Test
  void aHeaderThatIsntOneAddressCountsAsTheProxy() {
    assertThat(behindTunnel.key(localhost, Optional.of("203.0.113.7, 10.0.0.1")))
        .isEqualTo("v4:127.0.0.1");
    assertThat(behindTunnel.key(localhost, Optional.of("example.com"))).isEqualTo("v4:127.0.0.1");
    assertThat(behindTunnel.key(localhost, Optional.empty())).isEqualTo("v4:127.0.0.1");
  }

  @Test
  void ipv6VisitorsCountByTheirSlash64() {
    assertThat(behindTunnel.key(localhost, Optional.of("2001:db8:1:2:3:4:5:6")))
        .isEqualTo(behindTunnel.key(localhost, Optional.of("2001:db8:1:2:ffff::1")));
  }

  @Test
  void badTrustedEntriesAreRefused() {
    assertThatThrownBy(() -> new ClientIp(List.of("cloudflare"), "X"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClientIp(List.of("10.0.0.0/33"), "X"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
