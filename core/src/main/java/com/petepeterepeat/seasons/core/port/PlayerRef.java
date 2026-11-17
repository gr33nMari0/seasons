// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/** An online player: their id and current name. */
public record PlayerRef(PlayerId id, String name) {}
