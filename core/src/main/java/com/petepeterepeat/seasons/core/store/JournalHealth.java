// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import java.util.Optional;

/**
 * The journal's state for {@code /season status}.
 *
 * @param damagedLines lines dropped at startup because they were cut short or failed their check
 * @param lastError the last write failure, if the latest write failed
 */
public record JournalHealth(
    int entries, long lastSequence, int damagedLines, Optional<String> lastError) {}
