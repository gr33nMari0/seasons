// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.time.Duration;

/**
 * Where an online player is and what they're doing, for Seasons API's snapshot of who's online.
 * Read on the server thread.
 *
 * @param biome the biome they stand in, such as {@code minecraft:plains}
 * @param idle how long since they last did anything
 * @param vanished whether a vanish plugin hides them, so the snapshot leaves them out
 */
public record Presence(Position at, String biome, Duration idle, boolean vanished) {}
