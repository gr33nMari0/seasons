// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.event;

/** Decides intents. Called on the thread that owns the player. */
@FunctionalInterface
public interface IntentHandler {
  Decision decide(Intent intent);
}
