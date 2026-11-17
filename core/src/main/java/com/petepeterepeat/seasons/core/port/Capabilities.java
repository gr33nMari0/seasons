// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.EnumSet;
import java.util.Set;

/** The capabilities one adapter declares. */
public record Capabilities(Set<Capability> supported) {
  public Capabilities {
    supported = supported.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(supported));
  }

  public boolean has(Capability capability) {
    return supported.contains(capability);
  }

  /**
   * The capabilities this adapter lacks that Seasons would use, for {@code /season status}. Region
   * threading is a property of the platform, not a gap.
   */
  public Set<Capability> gaps() {
    EnumSet<Capability> gaps = EnumSet.allOf(Capability.class);
    gaps.remove(Capability.REGION_THREADING);
    gaps.removeAll(supported);
    return gaps;
  }
}
