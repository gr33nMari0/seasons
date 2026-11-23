// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceCodesTest {
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T10:00:00Z"));
  private final DeviceCodes codes = new DeviceCodes(clock);
  private final DeviceCodes.Linked alex = new DeviceCodes.Linked(UUID.randomUUID(), "Alex", false);

  @Test
  void eightCharactersWithNoLookAlikes() {
    for (int i = 0; i < 200; i++) {
      DeviceCodes.Issued issued = codes.issue();
      assertThat(issued.code()).hasSize(8).matches("[23456789ABCDEFGHJKMNPQRSTVWXYZ]{8}");
      assertThat(issued.shown()).matches("[A-Z0-9]{4}-[A-Z0-9]{4}");
    }
    assertThat(DeviceCodes.ALPHABET).doesNotContain("0", "O", "1", "I", "L", "U");
  }

  @Test
  void onlyTheBrowserThatAskedCollectsTheSession() {
    DeviceCodes.Issued issued = codes.issue();
    assertThat(codes.poll(issued.deviceSecret())).isInstanceOf(DeviceCodes.Waiting.class);
    assertThat(
            codes.link(issued.shown().toLowerCase(java.util.Locale.ROOT).replace("-", " "), alex))
        .isTrue();
    assertThat(codes.poll(Tokens.secret())).isInstanceOf(DeviceCodes.Unknown.class);
    clock.advance(Duration.ofSeconds(2));
    assertThat(codes.poll(issued.deviceSecret())).isEqualTo(new DeviceCodes.Collected(alex));
    clock.advance(Duration.ofSeconds(2));
    assertThat(codes.poll(issued.deviceSecret()))
        .as("collected once")
        .isInstanceOf(DeviceCodes.Expired.class);
  }

  @Test
  void singleUse() {
    DeviceCodes.Issued issued = codes.issue();
    assertThat(codes.link(issued.code(), alex)).isTrue();
    assertThat(codes.link(issued.code(), new DeviceCodes.Linked(UUID.randomUUID(), "Sam", false)))
        .isFalse();
  }

  @Test
  void expiresAfterFiveMinutes() {
    DeviceCodes.Issued issued = codes.issue();
    clock.advance(Duration.ofMinutes(5));
    assertThat(codes.link(issued.code(), alex)).isFalse();
    assertThat(codes.poll(issued.deviceSecret())).isInstanceOf(DeviceCodes.Expired.class);
  }

  @Test
  void pollingTooFastIsRefused() {
    DeviceCodes.Issued issued = codes.issue();
    assertThat(codes.poll(issued.deviceSecret())).isInstanceOf(DeviceCodes.Waiting.class);
    assertThat(codes.poll(issued.deviceSecret())).isInstanceOf(DeviceCodes.TooSoon.class);
  }

  @Test
  void unknownCodesDontLink() {
    assertThat(codes.link("ZZZZ-ZZZZ", alex)).isFalse();
    assertThat(codes.link("", alex)).isFalse();
  }
}
