// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.List;
import java.util.Optional;

/**
 * A view-only menu with pages, such as the archive view. Each page holds up to {@link #PAGE_SIZE}
 * items; the adapter adds the buttons that move between pages.
 *
 * @param pageLabels one label per page, such as "Page 1 of 2"
 */
public record PagedView(
    Message title,
    List<List<Optional<NeutralItem>>> pages,
    Message previous,
    Message next,
    List<Message> pageLabels) {
  /** Items per page: five rows of a six-row chest, the sixth row being the buttons. */
  public static final int PAGE_SIZE = 45;

  public PagedView {
    pages = pages.stream().map(List::copyOf).toList();
    pageLabels = List.copyOf(pageLabels);
    if (pages.isEmpty() || pages.size() != pageLabels.size()) {
      throw new IllegalArgumentException("A paged view has at least one page, each with a label");
    }
    if (pages.stream().anyMatch(page -> page.size() > PAGE_SIZE)) {
      throw new IllegalArgumentException("A page holds at most " + PAGE_SIZE + " items");
    }
  }
}
