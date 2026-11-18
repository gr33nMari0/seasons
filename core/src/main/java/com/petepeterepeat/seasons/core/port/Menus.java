// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.List;
import java.util.Optional;

/**
 * View-only and withdraw-only menus, with every click type refused. Every item shown is a throwaway
 * copy tagged as archived, so the tripwire catches one that ever escapes.
 */
public interface Menus {
  /** Opens a view-only menu built from throwaway copies of the items. */
  void openViewOnly(PlayerId viewer, Message title, List<Optional<NeutralItem>> items);

  /** Opens a view-only menu with pages, starting on the first. */
  default void openPaged(PlayerId viewer, PagedView view) {
    openPaged(viewer, view, 0);
  }

  /** Opens a view-only menu with pages, starting on one of them (0-based). */
  void openPaged(PlayerId viewer, PagedView view, int startPage);
}
