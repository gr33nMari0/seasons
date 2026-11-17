// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.util.function.Supplier;

/** The live {@link ConfigSnapshot}. A reload replaces it in one step. */
public final class ConfigHolder implements Supplier<ConfigSnapshot> {
  private volatile ConfigSnapshot current;

  public ConfigHolder(ConfigSnapshot initial) {
    this.current = initial;
  }

  @Override
  public ConfigSnapshot get() {
    return current;
  }

  public void set(ConfigSnapshot next) {
    current = next;
  }
}
