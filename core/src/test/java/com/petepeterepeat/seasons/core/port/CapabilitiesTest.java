// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilitiesTest {
  @Test
  void gapsAreWhatIsMissingExceptRegionThreading() {
    Capabilities caps = new Capabilities(EnumSet.of(Capability.MANNEQUINS));

    assertThat(caps.has(Capability.MANNEQUINS)).isTrue();
    assertThat(caps.gaps())
        .containsExactlyInAnyOrder(
            Capability.PER_PLAYER_WORLD_BORDER, Capability.FULL_PROTECTION_COVERAGE);
  }

  @Test
  void noCapabilitiesMeansEveryGap() {
    assertThat(new Capabilities(Set.of()).gaps()).hasSize(Capability.values().length - 1);
  }
}
