// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/** Permission checks. Node defaults are declared in each adapter's packaging. */
public interface Permissions {
  boolean has(PlayerId player, String node);

  /** Whether a player is a server operator, which no permission node can make them. */
  default boolean isOperator(PlayerId player) {
    return false;
  }
}
