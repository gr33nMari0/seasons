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

  /**
   * Shows a form (M8): text, a choice and toggles to fill in, and buttons, as the game's dialog
   * screen. A button's click tells {@code answered} which button and what was filled in, on the
   * server thread. False if this platform or client can't show forms, when the caller falls back to
   * a choice menu.
   */
  default boolean openForm(PlayerId viewer, Form form, Answered answered) {
    return false;
  }

  /** A form: its title, lines of text, fields and buttons. */
  record Form(Message title, List<Message> body, List<Field> fields, List<Button> buttons) {
    public Form {
      body = List.copyOf(body);
      fields = List.copyOf(fields);
      buttons = List.copyOf(buttons);
    }
  }

  /** Something to fill in on a form. */
  sealed interface Field {
    String key();

    record Text(String key, Message label, String initial, int maxLength) implements Field {}

    record Choice(
        String key, Message label, List<String> options, List<Message> labels, String initial)
        implements Field {
      public Choice {
        options = List.copyOf(options);
        labels = List.copyOf(labels);
      }
    }

    record Toggle(String key, Message label, boolean initial) implements Field {}
  }

  /** A button on a form. */
  record Button(String key, Message label) {}

  /** A form's button clicked. */
  @FunctionalInterface
  interface Answered {
    /** Which button, and each field's value by key: text, a choice's id, or true or false. */
    void answered(String button, java.util.Map<String, String> values);
  }

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
