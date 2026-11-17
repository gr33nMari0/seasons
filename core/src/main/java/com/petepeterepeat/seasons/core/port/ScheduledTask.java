// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/** A repeating or delayed task that can be stopped. */
@FunctionalInterface
public interface ScheduledTask {
  void cancel();
}
