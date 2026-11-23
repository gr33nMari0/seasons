// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.time.Instant;
import java.util.Optional;

/**
 * One season on record (since 1.3). Seasons keeps no dates for a season once the next has begun, so
 * its span is when the first and last of its players were seen in it.
 *
 * @param current whether it is the season on record now
 * @param firstSeen when the first of its players was first seen in it
 * @param lastSeen when the last of its players was last seen in it
 * @param players how many players played it
 * @param firsts how many season firsts it had
 * @param gallery whether a gallery holds its cells
 */
public record SeasonSummary(
    int season,
    boolean current,
    Optional<Instant> firstSeen,
    Optional<Instant> lastSeen,
    int players,
    int firsts,
    boolean gallery) {}
