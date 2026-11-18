// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.hall.Hall;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.OpenOperation;
import com.petepeterepeat.seasons.core.store.RecoveryHandler;
import com.petepeterepeat.seasons.core.store.RecoveryOutcome;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@code /season unarchive}: puts one archive back on its player, for mistakes only. The player
 * must be online, and their inventory empty unless forced; whatever they carried is then kept in a
 * displaced-items file in the archive format, never destroyed. Journaled with {@code
 * UNARCHIVE_STARTED} and {@code UNARCHIVE_DONE}, so a crash part-way is finished at their next
 * login.
 */
public final class Unarchiver {
  /** What {@link #unarchive} came to. */
  public sealed interface Outcome {
    /**
     * Restored.
     *
     * @param displacedItems items that were in the way, kept in {@code displacedFile}
     */
    record Restored(int displacedItems, Optional<Path> displacedFile) implements Outcome {}

    record Offline() implements Outcome {}

    record NoArchive() implements Outcome {}

    record NotEmpty() implements Outcome {}

    /** The archive came from {@code /hof import}; {@code /hof import undo} puts it back. */
    record Imported() implements Outcome {}

    record Busy() implements Outcome {}

    record Failed(String reason) implements Outcome {}
  }

  private final Platform platform;
  private final Clock clock;
  private final SerialWriter writer;
  private final Journal journal;
  private final ArchiveFiles archives;
  private final VaultFiles vaults;
  private final PlayerStore store;
  private final Hall hall;
  private final AuditLog audit;
  private final ArchiveHold hold;
  private final Executor serverThread;

  public Unarchiver(
      Platform platform,
      Clock clock,
      SerialWriter writer,
      Journal journal,
      ArchiveFiles archives,
      VaultFiles vaults,
      PlayerStore store,
      Hall hall,
      AuditLog audit,
      ArchiveHold hold) {
    this.platform = platform;
    this.clock = clock;
    this.writer = writer;
    this.journal = journal;
    this.archives = archives;
    this.vaults = vaults;
    this.store = store;
    this.hall = hall;
    this.audit = audit;
    this.hold = hold;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /**
   * Restores one archive to an online player.
   *
   * @param actor who asked, for the audit log
   */
  public CompletableFuture<Outcome> unarchive(
      PlayerRef player, int season, boolean force, String actor) {
    PlayerId id = player.id();
    return writer
        .submit(() -> archives.read(id, season))
        .thenComposeAsync(
            read -> {
              if (read.isEmpty()) {
                return done(new Outcome.NoArchive());
              }
              if (platform.players().find(id).isEmpty()) {
                return done(new Outcome.Offline());
              }
              ArchiveRecord archive = read.get();
              if (archive.imported().isPresent()) {
                // Its items came from a cell, not a player: restoring would give back nothing and
                // set the record aside, out of reach of /hof import undo.
                return done(new Outcome.Imported());
              }
              InventorySnapshot live = platform.inventories().snapshot(id);
              Plan plan = plan(archive, live);
              if (!plan.displaced().isEmpty() && plan.displacedFromPlayer() && !force) {
                return done(new Outcome.NotEmpty());
              }
              if (!hold.hold(id)) {
                return done(new Outcome.Busy());
              }
              int dataVersion = platform.inventories().dataVersion();
              Instant now = clock.instant();
              return writer
                  .submit(() ->
                      plan.displaced().isEmpty()
                          ? Optional.<Path>empty()
                          : Optional.of(
                              archives.writeBeside(
                                  displacedRecord(archive, live, plan, now),
                                  dataVersion,
                                  "displaced-" + now.toEpochMilli())))
                  .thenCompose(file ->
                      journal
                          .append(JournalKind.UNARCHIVE_STARTED, id, season, plan.restoredHash())
                          .thenApply(entry -> file))
                  .thenComposeAsync(
                      file -> {
                        restore(id, archive, plan);
                        return complete(archive, now).thenApply(done -> file);
                      },
                      serverThread)
                  .handleAsync(
                      (file, failure) -> {
                        hold.release(id);
                        if (failure != null) {
                          String reason = Archiver.reason(failure);
                          audit.record(actor, "unarchive", player.name(), "failed", reason);
                          return (Outcome) new Outcome.Failed(reason);
                        }
                        audit.record(
                            actor,
                            "unarchive",
                            player.name(),
                            "ok",
                            "Season "
                                + season
                                + "; displaced "
                                + plan.displacedCount()
                                + " items"
                                + file.map(f -> " into " + f.getFileName()).orElse(""));
                        return new Outcome.Restored(plan.displacedCount(), file);
                      },
                      serverThread);
            },
            serverThread);
  }

  /** The handler that finishes unarchives a crash left open. */
  public RecoveryHandler recovery() {
    return this::recover;
  }

  private CompletableFuture<RecoveryOutcome> recover(OpenOperation operation) {
    if (operation.player().isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("the journal names no player"));
    }
    PlayerId id = operation.player().get();
    int season = operation.season();
    if (platform.players().find(id).isEmpty()) {
      return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
    }
    String expected = operation.steps().getFirst().payloadHash();
    return writer
        .submit(() -> archives.read(id, season))
        .thenComposeAsync(
            read -> {
              if (read.isEmpty()) {
                // Moved aside already: only the record and the finish entry are left.
                return finishRecord(id, season).thenApply(done -> RecoveryOutcome.finished());
              }
              if (platform.players().find(id).isEmpty()) {
                return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
              }
              ArchiveRecord archive = read.get();
              InventorySnapshot live = platform.inventories().snapshot(id);
              boolean includeEnder = archive.clearsEnderChest();
              if (!ArchiveHash.of(live, includeEnder).equals(expected)) {
                if (!ArchiveHash.isEmpty(live, includeEnder)) {
                  return CompletableFuture.completedFuture(
                      RecoveryOutcome.failed("the live inventory is neither restored nor empty"));
                }
                restore(id, archive, plan(archive, live));
              }
              return complete(archive, clock.instant())
                  .thenApply(done -> RecoveryOutcome.finished());
            },
            serverThread);
  }

  /** On the server thread: items, experience and advancements back on the player. */
  private void restore(PlayerId id, ArchiveRecord archive, Plan plan) {
    platform.inventories().restore(id, plan.restored());
    // Advancements first: some reward experience, which the archived total then replaces.
    platform.advancements().award(id, archive.advancements().awarded());
    platform.players().setExperience(id, archive.experience());
    platform.players().saveData(id);
  }

  /** The archive moved aside, the vault page withdrawn, the record updated, then the journal. */
  private CompletableFuture<Void> complete(ArchiveRecord archive, Instant at) {
    PlayerId id = archive.player();
    int season = archive.season();
    return writer
        .submit(() -> {
          archives.moveAside(id, season, "unarchived-" + at.toEpochMilli());
          vaults.remove(id, season);
          return Boolean.TRUE;
        })
        .thenComposeAsync(moved -> finishRecord(id, season), serverThread)
        .thenRunAsync(() -> hall.displays().remove(id, season), serverThread);
  }

  private CompletableFuture<Void> finishRecord(PlayerId id, int season) {
    PlayerRecord record = store.find(id).orElse(PlayerRecord.create(id, ""));
    return store
        .putNow(record.withUnarchived(season))
        .thenCompose(saved -> journal.append(JournalKind.UNARCHIVE_DONE, id, season, "restored"))
        .thenApply(entry -> null);
  }

  /**
   * What the player will carry, and what has to make way: their current items if the slots are
   * taken, and archived overflow that finds no free slot.
   */
  private static Plan plan(ArchiveRecord archive, InventorySnapshot live) {
    boolean restoresEnder = archive.enderChestPolicy() != EnderChestPolicy.KEEP;
    List<Optional<NeutralItem>> storage = new ArrayList<>(archive.inventory().storage());
    List<Optional<NeutralItem>> leftover = new ArrayList<>();
    for (Optional<NeutralItem> item : archive.overflow()) {
      if (item.isEmpty()) {
        continue;
      }
      int free = storage.indexOf(Optional.<NeutralItem>empty());
      if (free >= 0) {
        storage.set(free, item);
      } else {
        leftover.add(item);
      }
    }
    InventorySnapshot restored =
        new InventorySnapshot(
            storage,
            archive.inventory().armour(),
            archive.inventory().offHand(),
            restoresEnder ? archive.inventory().enderChest() : live.enderChest());
    List<NeutralItem> displaced = new ArrayList<>();
    boolean fromPlayer = !ArchiveHash.isEmpty(live, restoresEnder);
    if (fromPlayer) {
      live.storage().forEach(item -> item.ifPresent(displaced::add));
      live.armour().forEach(item -> item.ifPresent(displaced::add));
      live.offHand().ifPresent(displaced::add);
      if (restoresEnder) {
        live.enderChest().forEach(item -> item.ifPresent(displaced::add));
      }
    }
    leftover.forEach(item -> item.ifPresent(displaced::add));
    return new Plan(
        restored, ArchiveHash.of(restored, restoresEnder), displaced, fromPlayer, leftover);
  }

  /**
   * @param displacedFromPlayer whether the player's own items are in the way, which needs --force
   */
  private record Plan(
      InventorySnapshot restored,
      String restoredHash,
      List<NeutralItem> displaced,
      boolean displacedFromPlayer,
      List<Optional<NeutralItem>> leftover) {
    int displacedCount() {
      return displaced.stream().mapToInt(Unarchiver::count).sum();
    }
  }

  private ArchiveRecord displacedRecord(
      ArchiveRecord archive, InventorySnapshot live, Plan plan, Instant now) {
    boolean restoresEnder = archive.enderChestPolicy() != EnderChestPolicy.KEEP;
    InventorySnapshot fromPlayer =
        plan.displacedFromPlayer()
            ? new InventorySnapshot(
                live.storage(),
                live.armour(),
                live.offHand(),
                restoresEnder ? live.enderChest() : List.of())
            : new InventorySnapshot(List.of(), List.of(), Optional.empty(), List.of());
    return new ArchiveRecord(
        archive.player(),
        archive.name(),
        archive.season(),
        now,
        fromPlayer,
        plan.leftover(),
        archive.enderChestPolicy(),
        new AdvancementSnapshot(Map.of(), 0, 0),
        Experience.NONE,
        Map.of(),
        Optional.empty(),
        platform.players().position(archive.player()),
        Optional.empty(),
        "displaced");
  }

  /** How many items a stack holds, read from its vanilla {@code count}. */
  static int count(NeutralItem item) {
    try {
      Object count = com.petepeterepeat.seasons.core.store.nbt.Nbt.read(item.nbt()).get("count");
      return count instanceof Number n ? n.intValue() : 1;
    } catch (java.io.IOException e) {
      return 1;
    }
  }

  private static <T> CompletableFuture<T> done(T value) {
    return CompletableFuture.completedFuture(value);
  }
}
