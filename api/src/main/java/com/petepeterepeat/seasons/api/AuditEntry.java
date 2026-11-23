// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.time.Instant;

/**
 * One entry of Seasons' audit log, without its details, which can hold positions (since 1.3).
 *
 * @param actor who did it: a player's name, the console, or {@code Seasons}
 * @param action such as {@code archive} or {@code zone-add}
 * @param target what it was done to, usually a player's or a zone's name
 * @param result such as {@code ok} or {@code failed}
 */
public record AuditEntry(Instant time, String actor, String action, String target, String result) {}
