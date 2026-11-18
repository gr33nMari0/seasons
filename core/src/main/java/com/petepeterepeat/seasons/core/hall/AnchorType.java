// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import java.util.Locale;
import java.util.Optional;

/** What Seasons does at an anchor (see Anchors in the spec). */
public enum AnchorType {
  /** The figure, wearing the archived gear. Exactly one per cell. */
  FIGURE(1),
  /** An existing block that opens the archive view. Any number. */
  ARCHIVE(Integer.MAX_VALUE),
  /** The plaque: the name, the season and the advancement count. At most one. */
  PLAQUE(1),
  /** An item display for an award or an imported item frame. Any number. */
  TROPHY(Integer.MAX_VALUE),
  /** Where visitors and the owner land. At most one. */
  ENTRY(1),
  /** One corner of the gate plane, for access mode {@code gate}. Two at most. */
  GATE(2);

  private final int max;

  AnchorType(int max) {
    this.max = max;
  }

  /** How many a cell may have. */
  public int max() {
    return max;
  }

  /** The name in files and commands, such as {@code figure}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Optional<AnchorType> of(String id) {
    for (AnchorType type : values()) {
      if (type.id().equals(id.toLowerCase(Locale.ROOT))) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }
}
