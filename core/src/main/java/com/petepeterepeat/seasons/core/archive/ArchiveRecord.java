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
import com.petepeterepeat.seasons.core.season.SeasonFirst;
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
 * @param statistics this season's difference of each General-tab statistic, and of the block counts
 *     under {@code seasons:} ids (see {@link com.petepeterepeat.seasons.core.port.Statistics})
 * @param heldSlot the hotbar slot in the player's main hand, which the figure holds
 * @param imported for a cell {@code /hof import} converted, what it took and from where; such a
 *     record has an empty inventory and its hash covers the imported items
 * @param honours the season firsts the player earned and the awards they won, copied from the
 *     season state when they are known; not part of the content hash
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
    Optional<ImportedCell> imported,
    Honours honours) {
  public ArchiveRecord {
    overflow = List.copyOf(overflow);
    statistics = Map.copyOf(statistics);
  }

  /**
   * What the player earned in the season besides their gear.
   *
   * @param firsts each season first's key and how it reads, such as {@code nether}
   * @param awards the ids of the awards they won, such as {@code most-deaths}
   */
  public record Honours(List<SeasonFirst> firsts, List<String> awards) {
    public static final Honours NONE = new Honours(List.of(), List.of());

    public Honours {
      firsts = List.copyOf(firsts);
      awards = List.copyOf(awards);
    }
  }

  /** A record with no honours copied in yet. */
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
      String contentHash,
      OptionalInt heldSlot,
      Optional<ImportedCell> imported) {
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
        heldSlot,
        imported,
        Honours.NONE);
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
        imported,
        honours);
  }

  /**
   * The same record with more overflow after what it has, such as items that turned up after the
   * clear. Overflow isn't in the content hash, which stays as it was.
   */
  public ArchiveRecord withMoreOverflow(List<NeutralItem> more) {
    List<Optional<NeutralItem>> next = new java.util.ArrayList<>(overflow);
    more.forEach(item -> next.add(Optional.of(item)));
    return new ArchiveRecord(
        player,
        name,
        season,
        created,
        inventory,
        next,
        enderChestPolicy,
        advancements,
        experience,
        statistics,
        furthest,
        finalPosition,
        skin,
        contentHash,
        heldSlot,
        imported,
        honours);
  }

  /** The same record with its honours. */
  public ArchiveRecord withHonours(Honours next) {
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
        heldSlot,
        imported,
        next);
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

  /** The item in one slot, if the slot exists and holds one. */
  public Optional<NeutralItem> item(ArchiveSlot at) {
    return switch (at.section()) {
      case ArchiveSlot.STORAGE -> slot(inventory.storage(), at.index());
      case ArchiveSlot.ARMOUR -> slot(inventory.armour(), at.index());
      case ArchiveSlot.OFFHAND -> at.index() == 0 ? inventory.offHand() : Optional.empty();
      case ArchiveSlot.OVERFLOW -> slot(overflow, at.index());
      case ArchiveSlot.ENDER -> slot(inventory.enderChest(), at.index());
      case ArchiveSlot.STAND ->
          imported
              .flatMap(ImportedCell::stand)
              .flatMap(stand -> slot(stand.equipment(), at.index()));
      case ArchiveSlot.CONTAINER ->
          imported
              .filter(i -> at.index() < i.containers().size())
              .flatMap(i -> slot(i.containers().get(at.index()).slots(), at.slot()));
      case ArchiveSlot.FRAME ->
          imported
              .filter(i -> at.index() < i.frames().size())
              .flatMap(i -> i.frames().get(at.index()).item());
      default -> Optional.empty();
    };
  }

  /**
   * This record with one slot changed, and its content hash worked out again over what it holds
   * now, as archiving or {@code /hof import} would have. Only for a slot the record has.
   *
   * @throws IllegalArgumentException if the record has no such slot
   */
  public ArchiveRecord with(ArchiveSlot at, Optional<NeutralItem> item) {
    InventorySnapshot inv = inventory;
    List<Optional<NeutralItem>> over = overflow;
    Optional<ImportedCell> cell = imported;
    switch (at.section()) {
      case ArchiveSlot.STORAGE ->
          inv =
              new InventorySnapshot(
                  set(inv.storage(), at.index(), item),
                  inv.armour(),
                  inv.offHand(),
                  inv.enderChest());
      case ArchiveSlot.ARMOUR ->
          inv =
              new InventorySnapshot(
                  inv.storage(),
                  set(inv.armour(), at.index(), item),
                  inv.offHand(),
                  inv.enderChest());
      case ArchiveSlot.OFFHAND -> {
        if (at.index() != 0) {
          throw new IllegalArgumentException("No such slot " + at.key());
        }
        inv = new InventorySnapshot(inv.storage(), inv.armour(), item, inv.enderChest());
      }
      case ArchiveSlot.OVERFLOW -> over = set(over, at.index(), item);
      case ArchiveSlot.ENDER ->
          inv =
              new InventorySnapshot(
                  inv.storage(),
                  inv.armour(),
                  inv.offHand(),
                  set(inv.enderChest(), at.index(), item));
      case ArchiveSlot.STAND, ArchiveSlot.CONTAINER, ArchiveSlot.FRAME ->
          cell = Optional.of(withImported(at, item));
      default -> throw new IllegalArgumentException("No such slot " + at.key());
    }
    String hash =
        cell.isPresent()
            ? ArchiveHash.ofImported(cell.get())
            : ArchiveHash.of(inv, clearsEnderChest());
    return new ArchiveRecord(
        player,
        name,
        season,
        created,
        inv,
        over,
        enderChestPolicy,
        advancements,
        experience,
        statistics,
        furthest,
        finalPosition,
        skin,
        hash,
        heldSlot,
        cell,
        honours);
  }

  private ImportedCell withImported(ArchiveSlot at, Optional<NeutralItem> item) {
    ImportedCell cell =
        imported.orElseThrow(() -> new IllegalArgumentException("No such slot " + at.key()));
    Optional<ImportedCell.Stand> stand = cell.stand();
    List<ImportedCell.Container> containers = cell.containers();
    List<ImportedCell.Frame> frames = cell.frames();
    switch (at.section()) {
      case ArchiveSlot.STAND -> {
        ImportedCell.Stand s =
            stand.orElseThrow(() -> new IllegalArgumentException("No such slot " + at.key()));
        stand =
            Optional.of(
                new ImportedCell.Stand(
                    s.at(), set(s.equipment(), at.index(), item), s.properties()));
      }
      case ArchiveSlot.CONTAINER -> {
        if (at.index() >= containers.size()) {
          throw new IllegalArgumentException("No such slot " + at.key());
        }
        ImportedCell.Container c = containers.get(at.index());
        List<ImportedCell.Container> next = new java.util.ArrayList<>(containers);
        next.set(
            at.index(),
            new ImportedCell.Container(c.block(), c.type(), set(c.slots(), at.slot(), item)));
        containers = next;
      }
      default -> {
        if (at.index() >= frames.size()) {
          throw new IllegalArgumentException("No such slot " + at.key());
        }
        ImportedCell.Frame f = frames.get(at.index());
        List<ImportedCell.Frame> next = new java.util.ArrayList<>(frames);
        next.set(at.index(), new ImportedCell.Frame(f.at(), item, f.properties()));
        frames = next;
      }
    }
    return new ImportedCell(cell.gallery(), cell.cell(), stand, containers, frames, cell.signs());
  }

  private static List<Optional<NeutralItem>> set(
      List<Optional<NeutralItem>> slots, int index, Optional<NeutralItem> item) {
    if (index >= slots.size()) {
      throw new IllegalArgumentException("No such slot " + index);
    }
    List<Optional<NeutralItem>> next = new java.util.ArrayList<>(slots);
    next.set(index, item);
    return next;
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
