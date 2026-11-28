// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import java.util.List;
import java.util.Optional;

/**
 * A page of Seasons' audit log (since 1.5).
 *
 * @param entries oldest first, without their details, which can hold positions
 * @param next a cursor for the page of older entries, if there are any; pass it as {@link
 *     AuditQuery#before()}. Its form is Seasons' own and may change
 */
public record AuditPage(List<AuditEntry> entries, Optional<String> next) {
  public AuditPage {
    entries = List.copyOf(entries);
  }
}
