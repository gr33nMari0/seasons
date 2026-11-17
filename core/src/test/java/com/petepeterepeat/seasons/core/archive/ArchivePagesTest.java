// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.FakeItems;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PagedView;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArchivePagesTest {
  private static ArchiveRecord archive(
      EnderChestPolicy policy, List<Optional<NeutralItem>> overflow) {
    return new ArchiveRecord(
        new PlayerId(UUID.randomUUID()),
        "Viewer",
        2,
        Instant.EPOCH,
        FakeItems.gear(),
        overflow,
        policy,
        new AdvancementSnapshot(Map.of(), 0, 0),
        Experience.NONE,
        Map.of(),
        Optional.empty(),
        Position.of("minecraft:overworld", 0, 0, 0),
        Optional.empty(),
        "hash");
  }

  @Test
  void pageOneIsThePlayerAsTheyStood() {
    List<Optional<NeutralItem>> page =
        ArchivePages.pages(archive(EnderChestPolicy.VAULT, List.of())).getFirst();

    assertThat(page).hasSize(PagedView.PAGE_SIZE);
    assertThat(page.get(0)).contains(FakeItems.item("minecraft:iron_helmet", 1));
    assertThat(page.get(4)).contains(FakeItems.item("minecraft:shield", 1));
    assertThat(page.get(9 + 13 - 9)).contains(FakeItems.item("minecraft:torch", 32));
    assertThat(page.get(36)).contains(FakeItems.item("minecraft:diamond_sword", 1));
    assertThat(page.get(37)).contains(FakeItems.item("minecraft:dirt", 64));
  }

  @Test
  void overflowAndAnArchivedEnderChestGetPagesOfTheirOwn() {
    Optional<NeutralItem> stone = Optional.of(FakeItems.item("minecraft:stone", 1));

    assertThat(ArchivePages.pages(archive(EnderChestPolicy.VAULT, List.of()))).hasSize(1);
    assertThat(ArchivePages.pages(archive(EnderChestPolicy.ARCHIVE, List.of()))).hasSize(2);
    assertThat(ArchivePages.pages(archive(EnderChestPolicy.ARCHIVE, List.of(stone))))
        .hasSize(3)
        .element(1)
        .isEqualTo(List.of(stone));
  }
}
