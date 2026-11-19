// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import com.petepeterepeat.seasons.core.port.Position;

/**
 * A shared waypoint, such as the hub or a megaproject: a server-wide place on the map, where
 * personal ones stay in players' own map mods.
 *
 * @param name as the admin wrote it, plain text, never formatting
 */
public record Waypoint(String name, Position at) {}
