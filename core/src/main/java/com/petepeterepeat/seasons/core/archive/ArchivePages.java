// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PagedView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Lays an archive out as the pages of its archive view. Pure.
 *
 * <p>Page 1 is the player as they stood: armour from helmet to boots and the off hand on the top
 * row, the main inventory on the next three, the hotbar on the fifth. Overflow from the cursor and
 * crafting grid follows, then the ender chest when the policy archived it.
 */
public final class ArchivePages {
  private static final int ROW = 9;
  private static final int HOTBAR = 9;
  private static final int MAIN_INVENTORY = 36;

  private ArchivePages() {}

  public static List<List<Optional<NeutralItem>>> pages(ArchiveRecord archive) {
    List<List<Optional<NeutralItem>>> pages = new ArrayList<>();
    pages.add(firstPage(archive));
    List<Optional<NeutralItem>> overflow =
        archive.overflow().stream().filter(Optional::isPresent).toList();
    for (int from = 0; from < overflow.size(); from += PagedView.PAGE_SIZE) {
      pages.add(overflow.subList(from, Math.min(overflow.size(), from + PagedView.PAGE_SIZE)));
    }
    if (archive.enderChestPolicy() == EnderChestPolicy.ARCHIVE
        && archive.inventory().enderChest().stream().anyMatch(Optional::isPresent)) {
      List<Optional<NeutralItem>> ender = archive.inventory().enderChest();
      pages.add(ender.subList(0, Math.min(ender.size(), PagedView.PAGE_SIZE)));
    }
    return pages;
  }

  /** The view, with titles and buttons from messages.yml. */
  public static PagedView view(ArchiveRecord archive, Messages messages) {
    List<List<Optional<NeutralItem>>> pages = pages(archive);
    List<com.petepeterepeat.seasons.core.message.Message> labels = new ArrayList<>();
    for (int i = 0; i < pages.size(); i++) {
      labels.add(
          messages.get(
              "view.page",
              "page",
              Integer.toString(i + 1),
              "pages",
              Integer.toString(pages.size())));
    }
    return new PagedView(
        messages.get(
            "view.title", "name", archive.name(), "season", Integer.toString(archive.season())),
        pages,
        messages.get("view.previous"),
        messages.get("view.next"),
        labels);
  }

  private static List<Optional<NeutralItem>> firstPage(ArchiveRecord archive) {
    List<Optional<NeutralItem>> page =
        new ArrayList<>(Collections.nCopies(PagedView.PAGE_SIZE, Optional.<NeutralItem>empty()));
    List<Optional<NeutralItem>> armour = new ArrayList<>(archive.inventory().armour());
    // Stored from boots to helmet, as the game keeps them; shown from the helmet down.
    Collections.reverse(armour);
    for (int i = 0; i < Math.min(4, armour.size()); i++) {
      page.set(i, armour.get(i));
    }
    page.set(4, archive.inventory().offHand());
    List<Optional<NeutralItem>> storage = archive.inventory().storage();
    for (int slot = HOTBAR; slot < Math.min(MAIN_INVENTORY, storage.size()); slot++) {
      page.set(ROW + slot - HOTBAR, storage.get(slot));
    }
    for (int slot = 0; slot < Math.min(HOTBAR, storage.size()); slot++) {
      page.set(4 * ROW + slot, storage.get(slot));
    }
    return page;
  }
}
