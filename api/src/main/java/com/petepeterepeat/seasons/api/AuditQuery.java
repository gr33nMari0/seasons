// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Which entries of Seasons' audit log to read, newest first in pages (since 1.5).
 *
 * @param count how many, at most; Seasons reads at most 500
 * @param before a page's {@link AuditPage#next()} cursor: only entries older than that page
 * @param kind only entries whose action is this or starts with it, ignoring case, such as {@code
 *     zone} for {@code zone-lock} and {@code zone-unlock}
 * @param player only entries whose actor or target is this name or UUID, ignoring case
 */
public record AuditQuery(
    int count, Optional<String> before, Optional<String> kind, Optional<String> player) {
  public AuditQuery {
    Objects.requireNonNull(before);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(player);
  }

  /** The last {@code count} entries, of any kind. */
  public static AuditQuery last(int count) {
    return new AuditQuery(count, Optional.empty(), Optional.empty(), Optional.empty());
  }
}
