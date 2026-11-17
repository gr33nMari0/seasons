// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Objects;
import java.util.UUID;

/** A player, by UUID. Records follow the UUID, never the name. */
public record PlayerId(UUID uuid) {
  public PlayerId {
    Objects.requireNonNull(uuid, "uuid");
  }

  @Override
  public String toString() {
    return uuid.toString();
  }
}
