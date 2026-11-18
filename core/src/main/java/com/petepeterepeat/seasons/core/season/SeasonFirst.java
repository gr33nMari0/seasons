// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.season;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.time.Instant;

/**
 * One season first: the first player to reach something in a season (see Stats, season cards and
 * awards in the spec).
 *
 * @param key what was reached, such as {@code advancement:minecraft:story/mine_diamond}, {@code
 *     nether} or {@code item:minecraft:trident}
 * @param label what was reached, as the announcement and the season card name it
 * @param name the player's name then
 */
public record SeasonFirst(
    int season, String key, String label, PlayerId player, String name, Instant at) {}
