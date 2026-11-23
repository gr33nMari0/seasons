// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.log.SeasonsLog;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The live {@link ConfigSnapshot}. A reload replaces it in one step, and switches the log's debug
 * areas to the ones it lists.
 */
public final class ConfigHolder implements Supplier<ConfigSnapshot> {
  private volatile ConfigSnapshot current;

  public ConfigHolder(ConfigSnapshot initial) {
    this.current = initial;
    SeasonsLog.debugging(Set.copyOf(initial.config().logging().debug()));
  }

  @Override
  public ConfigSnapshot get() {
    return current;
  }

  public void set(ConfigSnapshot next) {
    current = next;
    SeasonsLog.debugging(Set.copyOf(next.config().logging().debug()));
  }
}
