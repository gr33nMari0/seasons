// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.player.FurthestBlock;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Skin;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code data/archives/s<n>/<uuid>.dat}: what one player carried, and had achieved, when a season
 * ended. It can never be withdrawn; only {@code /season unarchive} moves it aside.
 *
 * <p>The ender chest is always captured, whatever the policy, so a crash between sealing the vault
 * and clearing the chest can rebuild the vault page from this record. The policy says where it
 * went: the vault, the archive view, or nowhere ({@code keep}, so it was never cleared).
 *
 * @param name the player's name at archive time; plaques keep it if they rename
 * @param overflow cursor and crafting-grid items that had no room in the inventory
 * @param contentHash {@link ArchiveHash} of the slots archiving clears
 * @param statistics this season's difference of each General-tab statistic
 */
public record ArchiveRecord(
    PlayerId player,
    String name,
    int season,
    Instant created,
    InventorySnapshot inventory,
    List<Optional<NeutralItem>> overflow,
    EnderChestPolicy enderChestPolicy,
    AdvancementSnapshot advancements,
    Experience experience,
    Map<String, Long> statistics,
    Optional<FurthestBlock> furthest,
    Position finalPosition,
    Optional<Skin> skin,
    String contentHash) {
  public ArchiveRecord {
    overflow = List.copyOf(overflow);
    statistics = Map.copyOf(statistics);
  }

  /** Whether the ender chest is cleared by archiving, and so part of the content hash. */
  public boolean clearsEnderChest() {
    return enderChestPolicy != EnderChestPolicy.KEEP;
  }

  /** Every stack captured from the inventory, armour, off hand and overflow. */
  public List<NeutralItem> carried() {
    return java.util.stream.Stream.of(
            inventory.storage().stream(),
            inventory.armour().stream(),
            java.util.stream.Stream.of(inventory.offHand()),
            overflow.stream())
        .flatMap(s -> s)
        .flatMap(Optional::stream)
        .toList();
  }

  /** The ender chest's stacks. */
  public List<NeutralItem> enderChest() {
    return inventory.enderChest().stream().flatMap(Optional::stream).toList();
  }
}
