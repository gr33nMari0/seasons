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

  /**
   * Opens a menu with pages, starting on one of them (0-based), where a left click or shift click
   * on an item asks {@code taken} to withdraw it. The menu itself still moves nothing: every click
   * is refused and the client resynced, so only the core's transaction gives anything.
   */
  void openWithdraw(PlayerId viewer, PagedView view, int startPage, Withdrawal taken);

  /**
   * Opens a small menu of choices, such as a curator's actions, each an item with a label. A left
   * click on one tells {@code chosen} which; nothing in it moves, as in the other menus.
   */
  void openChoice(PlayerId viewer, Message title, List<Choice> choices, Chosen chosen);

  /** Closes whatever menu a player has open, so they can type in chat. */
  void close(PlayerId viewer);

  /**
   * Closes a Seasons menu a player has open (a view-only, paged, withdraw-only or choice menu),
   * leaving any other inventory alone; before Seasons stops itself, so no menu outlives the
   * listener that keeps it view-only (questions 283).
   */
  default void closeOwn(PlayerId viewer) {
    close(viewer);
  }

  /**
   * One choice.
   *
   * @param icon the item it shows as, such as {@code minecraft:ender_chest}
   * @param label its name
   */
  record Choice(String icon, Message label) {}

  /** A click on one of a menu's choices. */
  @FunctionalInterface
  interface Chosen {
    /**
     * Hears one choice.
     *
     * @param index which choice, in the order given (0-based)
     */
    void chosen(int index);
  }

  /** A click asking to withdraw the item in one slot of one page. */
  @FunctionalInterface
  interface Withdrawal {
    /**
     * Asks for one item.
     *
     * @param page the page shown (0-based)
     * @param slot the slot on that page (0-based, below {@link PagedView#PAGE_SIZE})
     */
    void clicked(int page, int slot);
  }
}
