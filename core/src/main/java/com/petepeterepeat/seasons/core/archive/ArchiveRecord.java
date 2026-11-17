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
import java.util.OptionalInt;

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
 * @param heldSlot the hotbar slot in the player's main hand, which the figure holds
 * @param imported for a cell {@code /hof import} converted, what it took and from where; such a
 *     record has an empty inventory and its hash covers the imported items
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
    String contentHash,
    OptionalInt heldSlot,
    Optional<ImportedCell> imported) {
  public ArchiveRecord {
    overflow = List.copyOf(overflow);
    statistics = Map.copyOf(statistics);
  }

  /** A record made by archiving a player, with no held slot known. */
  public ArchiveRecord(
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
    this(
        player,
        name,
        season,
        created,
        inventory,
        overflow,
        enderChestPolicy,
        advancements,
        experience,
        statistics,
        furthest,
        finalPosition,
        skin,
        contentHash,
        OptionalInt.empty(),
        Optional.empty());
  }

  /** The same record, remembering which hotbar slot was in the player's main hand. */
  public ArchiveRecord withHeldSlot(int slot) {
    return new ArchiveRecord(
        player,
        name,
        season,
        created,
        inventory,
        overflow,
        enderChestPolicy,
        advancements,
        experience,
        statistics,
        furthest,
        finalPosition,
        skin,
        contentHash,
        OptionalInt.of(slot),
        imported);
  }

  /**
   * The figure's equipment: head, chest, legs, feet, main hand, off hand. An imported figure's head
   * is left bare when it is the owner's player head, since the figure wears that skin.
   */
  public List<Optional<NeutralItem>> figureEquipment() {
    if (imported.isPresent()) {
      List<Optional<NeutralItem>> worn =
          new java.util.ArrayList<>(
              imported.get().stand().map(ImportedCell.Stand::equipment).orElse(List.of()));
      if (!worn.isEmpty() && worn.getFirst().map(ArchiveRecord::isPlayerHead).orElse(false)) {
        worn.set(0, Optional.empty());
      }
      return worn;
    }
    List<Optional<NeutralItem>> armour = inventory.armour();
    Optional<NeutralItem> mainHand =
        heldSlot.isPresent() && heldSlot.getAsInt() < inventory.storage().size()
            ? inventory.storage().get(heldSlot.getAsInt())
            : Optional.empty();
    return List.of(
        slot(armour, 3),
        slot(armour, 2),
        slot(armour, 1),
        slot(armour, 0),
        mainHand,
        inventory.offHand());
  }

  private static boolean isPlayerHead(NeutralItem item) {
    try {
      return "minecraft:player_head"
          .equals(com.petepeterepeat.seasons.core.store.nbt.Nbt.read(item.nbt()).get("id"));
    } catch (java.io.IOException e) {
      return false;
    }
  }

  private static Optional<NeutralItem> slot(List<Optional<NeutralItem>> slots, int index) {
    return index < slots.size() ? slots.get(index) : Optional.empty();
  }

  /** Whether the ender chest is cleared by archiving, and so part of the content hash. */
  public boolean clearsEnderChest() {
    return enderChestPolicy != EnderChestPolicy.KEEP;
  }

  /**
   * Every stack captured from the inventory, armour, off hand and overflow, or for an imported cell
   * every stack the import took.
   */
  public List<NeutralItem> carried() {
    return java.util.stream.Stream.of(
            inventory.storage().stream(),
            inventory.armour().stream(),
            java.util.stream.Stream.of(inventory.offHand()),
            overflow.stream(),
            imported.stream().flatMap(i -> i.items().stream()).map(Optional::of))
        .flatMap(s -> s)
        .flatMap(Optional::stream)
        .toList();
  }

  /** The ender chest's stacks. */
  public List<NeutralItem> enderChest() {
    return inventory.enderChest().stream().flatMap(Optional::stream).toList();
  }
}
