// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

/** A player waiting to be archived into a season that has ended (since 1.3). */
public record PendingPlayer(PlayerName player, int season) {}
