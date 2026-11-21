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
 *
 * <p>An imported cell shows its stand's gear and its item frames' items on the first page, if it
 * had any, then one page per container in the container's own slot order. A double chest's sixth
 * row doesn't fit beside the page buttons, so it follows on a page of its own.
 */
public final class ArchivePages {
  private static final int ROW = 9;
  private static final int HOTBAR = 9;
  private static final int MAIN_INVENTORY = 36;

  private ArchivePages() {}

  public static List<List<Optional<NeutralItem>>> pages(ArchiveRecord archive) {
    return slots(archive).stream()
        .map(page -> page.stream().map(at -> at.flatMap(archive::item)).toList())
        .toList();
  }

  /**
   * Where each item shown on each page sits in the record, in the same layout as {@link #pages}:
   * what a click on a page's slot points at, for the curator view.
   */
  public static List<List<Optional<ArchiveSlot>>> slots(ArchiveRecord archive) {
    if (archive.imported().isPresent()) {
      return importedSlots(archive.imported().get());
    }
    List<List<Optional<ArchiveSlot>>> pages = new ArrayList<>();
    pages.add(firstPage(archive));
    List<Optional<ArchiveSlot>> overflow = new ArrayList<>();
    for (int i = 0; i < archive.overflow().size(); i++) {
      if (archive.overflow().get(i).isPresent()) {
        overflow.add(Optional.of(ArchiveSlot.of(ArchiveSlot.OVERFLOW, i)));
      }
    }
    for (int from = 0; from < overflow.size(); from += PagedView.PAGE_SIZE) {
      pages.add(overflow.subList(from, Math.min(overflow.size(), from + PagedView.PAGE_SIZE)));
    }
    if (archive.enderChestPolicy() == EnderChestPolicy.ARCHIVE
        && archive.inventory().enderChest().stream().anyMatch(Optional::isPresent)) {
      List<Optional<ArchiveSlot>> ender = new ArrayList<>();
      for (int i = 0;
          i < Math.min(archive.inventory().enderChest().size(), PagedView.PAGE_SIZE);
          i++) {
        ender.add(Optional.of(ArchiveSlot.of(ArchiveSlot.ENDER, i)));
      }
      pages.add(ender);
    }
    return pages;
  }

  /** For each of an imported cell's containers, the page (0-based) its items start on. */
  public static List<Integer> containerPages(ImportedCell imported) {
    List<Integer> starts = new ArrayList<>();
    int page = hasGearPage(imported) ? 1 : 0;
    for (ImportedCell.Container container : imported.containers()) {
      starts.add(page);
      page += pagesFor(container.slots().size());
    }
    return starts;
  }

  private static List<List<Optional<ArchiveSlot>>> importedSlots(ImportedCell imported) {
    List<List<Optional<ArchiveSlot>>> pages = new ArrayList<>();
    if (hasGearPage(imported)) {
      List<Optional<ArchiveSlot>> gear = emptyPage();
      int worn = imported.stand().map(s -> s.equipment().size()).orElse(0);
      for (int i = 0; i < worn; i++) {
        gear.set(i, Optional.of(ArchiveSlot.of(ArchiveSlot.STAND, i)));
      }
      for (int i = 0; i < Math.min(imported.frames().size(), PagedView.PAGE_SIZE - ROW); i++) {
        gear.set(ROW + i, Optional.of(ArchiveSlot.of(ArchiveSlot.FRAME, i)));
      }
      pages.add(gear);
    }
    for (int c = 0; c < imported.containers().size(); c++) {
      int size = imported.containers().get(c).slots().size();
      for (int page = 0; page < pagesFor(size); page++) {
        int from = page * PagedView.PAGE_SIZE;
        List<Optional<ArchiveSlot>> slots = new ArrayList<>();
        for (int i = from; i < Math.min(size, from + PagedView.PAGE_SIZE); i++) {
          slots.add(Optional.of(new ArchiveSlot(ArchiveSlot.CONTAINER, c, i)));
        }
        pages.add(slots);
      }
    }
    if (pages.isEmpty()) {
      pages.add(List.of());
    }
    return pages;
  }

  private static List<Optional<ArchiveSlot>> emptyPage() {
    return new ArrayList<>(Collections.nCopies(PagedView.PAGE_SIZE, Optional.<ArchiveSlot>empty()));
  }

  private static boolean hasGearPage(ImportedCell imported) {
    return imported.stand().isPresent() || !imported.frames().isEmpty();
  }

  private static int pagesFor(int slots) {
    return Math.max(1, (slots + PagedView.PAGE_SIZE - 1) / PagedView.PAGE_SIZE);
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

  private static List<Optional<ArchiveSlot>> firstPage(ArchiveRecord archive) {
    List<Optional<ArchiveSlot>> page = emptyPage();
    int armour = archive.inventory().armour().size();
    // Stored from boots to helmet, as the game keeps them; shown from the helmet down.
    for (int i = 0; i < Math.min(4, armour); i++) {
      page.set(i, Optional.of(ArchiveSlot.of(ArchiveSlot.ARMOUR, armour - 1 - i)));
    }
    page.set(4, Optional.of(ArchiveSlot.of(ArchiveSlot.OFFHAND, 0)));
    int storage = archive.inventory().storage().size();
    for (int slot = HOTBAR; slot < Math.min(MAIN_INVENTORY, storage); slot++) {
      page.set(ROW + slot - HOTBAR, Optional.of(ArchiveSlot.of(ArchiveSlot.STORAGE, slot)));
    }
    for (int slot = 0; slot < Math.min(HOTBAR, storage); slot++) {
      page.set(4 * ROW + slot, Optional.of(ArchiveSlot.of(ArchiveSlot.STORAGE, slot)));
    }
    return page;
  }
}
