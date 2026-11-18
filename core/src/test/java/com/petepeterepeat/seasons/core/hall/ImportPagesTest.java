// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.FakeItems;
import com.petepeterepeat.seasons.core.archive.ArchiveHash;
import com.petepeterepeat.seasons.core.archive.ArchivePages;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ImportedCell;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** How an imported cell becomes pages, and what the import compares. */
class ImportPagesTest {
  private static final String OVERWORLD = "minecraft:overworld";

  private static List<Optional<NeutralItem>> chest(int size, String id, int count) {
    List<Optional<NeutralItem>> slots = new ArrayList<>(FakeItems.empty(size));
    slots.set(0, Optional.of(FakeItems.item(id, count)));
    slots.set(size - 1, Optional.of(FakeItems.item("minecraft:shulker_box", 1)));
    return slots;
  }

  static ImportedCell cell() {
    List<Optional<NeutralItem>> gear = new ArrayList<>(FakeItems.empty(6));
    gear.set(0, Optional.of(FakeItems.item("minecraft:player_head", 1)));
    gear.set(1, Optional.of(FakeItems.item("minecraft:iron_chestplate", 1)));
    return new ImportedCell(
        "season-1",
        "owner",
        Optional.of(new ImportedCell.Stand(Position.of(OVERWORLD, 5, -60, 4), gear, Map.of())),
        List.of(
            new ImportedCell.Container(
                Position.of(OVERWORLD, 2, -60, 7),
                "minecraft:chest",
                chest(27, "minecraft:diamond", 1)),
            new ImportedCell.Container(
                Position.of(OVERWORLD, 5, -60, 7),
                "minecraft:chest",
                chest(54, "minecraft:dirt", 64))),
        List.of(
            new ImportedCell.Frame(
                Position.of(OVERWORLD, 1, -59, 7),
                Optional.of(FakeItems.item("minecraft:emerald", 1)),
                Map.of())),
        List.of("Season 1"));
  }

  static ArchiveRecord record(ImportedCell imported) {
    return new ArchiveRecord(
        new PlayerId(UUID.randomUUID()),
        "Owner",
        1,
        Instant.EPOCH,
        FakeItems.EMPTY,
        List.of(),
        EnderChestPolicy.KEEP,
        new AdvancementSnapshot(Map.of(), 0, 0),
        Experience.NONE,
        Map.of(),
        Optional.empty(),
        Position.of(OVERWORLD, 5, -60, 4),
        Optional.empty(),
        ArchiveHash.ofImported(imported),
        OptionalInt.empty(),
        Optional.of(imported));
  }

  @Test
  void theGearPageComesFirstThenOnePagePerChestInSlotOrder() {
    List<List<Optional<NeutralItem>>> pages = ArchivePages.pages(record(cell()));

    // Gear and frame, the single chest, then the double chest over two pages.
    assertThat(pages).hasSize(4);
    assertThat(pages.get(0).get(1)).contains(FakeItems.item("minecraft:iron_chestplate", 1));
    assertThat(pages.get(0).get(9)).contains(FakeItems.item("minecraft:emerald", 1));
    assertThat(pages.get(1).get(0)).contains(FakeItems.item("minecraft:diamond", 1));
    assertThat(pages.get(1).get(26)).contains(FakeItems.item("minecraft:shulker_box", 1));
    assertThat(pages.get(3).get(8)).contains(FakeItems.item("minecraft:shulker_box", 1));
    assertThat(ArchivePages.containerPages(cell())).containsExactly(1, 2);
  }

  @Test
  void theRecordCountsEveryStackOnceAndTheFigureLeavesTheHeadOff() {
    ArchiveRecord record = record(cell());

    assertThat(record.carried()).hasSize(2 + 2 + 2 + 1);
    assertThat(record.figureEquipment().get(1))
        .contains(FakeItems.item("minecraft:iron_chestplate", 1));
  }

  @Test
  void theHashCoversWhereEachItemWas() {
    ImportedCell moved =
        new ImportedCell(
            "season-1",
            "owner",
            cell().stand(),
            List.of(cell().containers().get(1), cell().containers().get(0)),
            cell().frames(),
            cell().signs());

    assertThat(ArchiveHash.ofImported(moved)).isNotEqualTo(ArchiveHash.ofImported(cell()));
    assertThat(
            HallImporter.same(
                cell().containers().getFirst().slots(), chest(27, "minecraft:diamond", 1)))
        .isTrue();
    assertThat(
            HallImporter.same(
                cell().containers().getFirst().slots(), chest(27, "minecraft:diamond", 2)))
        .isFalse();
  }
}
