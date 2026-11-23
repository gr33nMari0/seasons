// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

/**
 * A player's place on a leaderboard.
 *
 * @param hidden whether they hide themselves from leaderboards, so callers leave them out
 */
public record LeaderboardEntry(PlayerName player, long value, boolean hidden) {}
