// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

/**
 * A shared waypoint, published on the map, so its position is public.
 *
 * @param set the marker set, such as {@code hubs}
 */
public record WaypointView(String set, String name, String dimension, int x, int y, int z) {}
