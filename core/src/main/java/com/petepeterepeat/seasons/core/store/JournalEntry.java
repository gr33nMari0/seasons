// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import com.petepeterepeat.seasons.core.port.PlayerId;
import java.time.Instant;
import java.util.Optional;

/**
 * One line of {@code data/journal.log}.
 *
 * @param sequence increasing by one per entry, kept across compactions
 * @param payloadHash a hash of what the step wrote or expects, such as an archive's content hash
 */
public record JournalEntry(
    long sequence,
    Instant time,
    JournalKind kind,
    Optional<PlayerId> player,
    int season,
    String payloadHash) {}
