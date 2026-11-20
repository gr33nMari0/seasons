// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.AdvancementPolicy;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.ClearMode;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.KeepMode;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.ResetMode;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.Resets;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.guard.Immunity;
import com.petepeterepeat.seasons.core.hall.Hall;
import com.petepeterepeat.seasons.core.player.FurthestBlock;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ResetPlan;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.season.RolloverArchiver;
import com.petepeterepeat.seasons.core.season.SeasonAwards;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.OpenOperation;
import com.petepeterepeat.seasons.core.store.RecoveryHandler;
import com.petepeterepeat.seasons.core.store.RecoveryOutcome;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Archives one player into one season, exactly once (see Archiving a player in the spec):
 *
 * <ol>
 *   <li>Hold the player, return cursor and crafting-grid items to the inventory and close the menu,
 *       save their file, copy everything. Nothing leaves the player before step 4.
 *   <li>Write the archive file and fsync it, then journal {@code ARCHIVE_STARTED} with its hash.
 *   <li>Seal the vault page, then journal {@code VAULT_SEALED}.
 *   <li>Check the live inventory, cursor and grid still match, clear them, reset the player, save
 *       their file.
 *   <li>Mark them archived in their record, then journal {@code ARCHIVE_DONE}.
 *   <li>Draw their displays, then send them on (their cell, or the new spawn).
 * </ol>
 *
 * <p>The server thread copies and clears; the writer thread writes, so the server thread never
 * waits on disk. A crash anywhere is finished by {@link #recovery()} when the player next logs in.
 */
public final class Archiver implements RolloverArchiver {
  private static final Logger LOG = LoggerFactory.getLogger(Archiver.class);

  /** What one call to {@link #archive} came to. */
  public sealed interface Outcome {
    /** Archived now. */
    record Archived(ArchiveRecord record) implements Outcome {}

    /** Already archived into that season; nothing changed. */
    record AlreadyArchived() implements Outcome {}

    /** The player isn't online; they catch up at their next login. */
    record Offline() implements Outcome {}

    /** The player is being archived or unarchived right now. */
    record Busy() implements Outcome {}

    /** Stopped part-way; the journal keeps it open for recovery. */
    record Failed(String reason) implements Outcome {}
  }

  /** Where a player goes once archived. */
  public enum Destination {
    /** Their cell for that season, if they have one; otherwise they stay where they are. */
    CELL,
    /** Nowhere: the caller moves them, as login catch-up after launch does. */
    STAY
  }

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final Clock clock;
  private final SerialWriter writer;
  private final Journal journal;
  private final ArchiveFiles archives;
  private final VaultFiles vaults;
  private final PlayerStore store;
  private final Hall hall;
  private final AuditLog audit;
  private final Announcer announcer;
  private final ArchiveHold hold;
  private final Immunity immunity;
  private final CrashPoints crash;
  private final Executor serverThread;
  private final Set<Integer> noCellNoticeSent = new HashSet<>();
  private final JournalRecovery recovery;
  private Supplier<Optional<SeasonState>> seasonState = Optional::empty;

  public Archiver(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      Clock clock,
      SerialWriter writer,
      Journal journal,
      JournalRecovery recovery,
      ArchiveFiles archives,
      VaultFiles vaults,
      PlayerStore store,
      Hall hall,
      AuditLog audit,
      Announcer announcer,
      ArchiveHold hold,
      Immunity immunity,
      CrashPoints crash) {
    this.platform = platform;
    this.config = config;
    this.clock = clock;
    this.writer = writer;
    this.journal = journal;
    this.archives = archives;
    this.vaults = vaults;
    this.store = store;
    this.hall = hall;
    this.audit = audit;
    this.announcer = announcer;
    this.hold = hold;
    this.immunity = immunity;
    this.crash = crash;
    this.recovery = recovery;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** The season state, whose firsts and awards each archive copies. */
  public void bind(Supplier<Optional<SeasonState>> state) {
    this.seasonState = state;
  }

  @Override
  public CompletableFuture<Void> lockGalleries(int season) {
    return hall.galleries().lockAll(season);
  }

  /** At season end, for every online player: archive, then send them to their cell. */
  @Override
  public CompletableFuture<Void> archive(PlayerId player, int season) {
    return archive(player, season, AuditLog.SEASONS)
        .thenCompose(outcome ->
            outcome instanceof Outcome.Archived
                ? followUp(player, Destination.CELL)
                : CompletableFuture.completedFuture(null));
  }

  /**
   * Archives an online player into a season. Never fails: problems come back as {@link
   * Outcome.Failed}, after admins have been told.
   *
   * @param actor who asked, for the audit log
   */
  public CompletableFuture<Outcome> archive(PlayerId player, int season, String actor) {
    Optional<PlayerRef> online = platform.players().find(player);
    if (online.isEmpty()) {
      return done(new Outcome.Offline());
    }
    PlayerRecord record = store.getOrCreate(player, online.get().name());
    if (record.archivedThrough() >= season) {
      return done(new Outcome.AlreadyArchived());
    }
    if (!recovery.open(player).isEmpty()) {
      // A crash left this player part-archived: finish that instead of starting again.
      return recovery
          .retry(player)
          .thenApply(finished -> (Outcome) archivedOutcome(player, season));
    }
    if (!hold.hold(player)) {
      return done(new Outcome.Busy());
    }
    Capture capture;
    try {
      capture = capture(online.get(), record, season);
    } catch (RuntimeException e) {
      hold.release(player);
      return done(failed(online.get().name(), season, "couldn't copy their inventory: " + e, e));
    }
    ArchiveRecord archive = capture.record();
    String name = online.get().name();
    return writer
        .submit(() -> {
          archives.write(archive, capture.dataVersion());
          crash.reached(CrashPoint.FILE_WRITTEN, player);
          return Boolean.TRUE;
        })
        .thenCompose(written ->
            journal.append(JournalKind.ARCHIVE_STARTED, player, season, archive.contentHash()))
        .thenCompose(started -> {
          crash.reached(CrashPoint.ARCHIVE_STARTED, player);
          return sealVault(archive, capture.dataVersion());
        })
        .thenComposeAsync(sealed -> finish(archive, false), serverThread)
        .handleAsync(
            (outcome, failure) -> {
              hold.release(player);
              if (failure != null) {
                return failed(name, season, reason(failure), failure);
              }
              audit.record(actor, "archive", name, "ok", summary(archive));
              platform
                  .players()
                  .sendMessage(
                      player,
                      config
                          .get()
                          .messages()
                          .get("archive.done", "season", Integer.toString(season)));
              return outcome;
            },
            serverThread);
  }

  /**
   * Steps 6 and 7 for a player whose archive is done: draw any displays still pending, then send
   * them to their cell if one is waiting. Harmless to call again.
   */
  public CompletableFuture<Void> followUp(PlayerId player, Destination destination) {
    Optional<PlayerRecord> found = store.find(player);
    if (found.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    PlayerRecord record = found.get();
    CompletableFuture<Void> displays = CompletableFuture.completedFuture(null);
    for (int season : record.pendingDisplays()) {
      displays =
          displays
              .thenCompose(done -> hall.displays().render(player, season))
              .thenRunAsync(
                  () ->
                      store.find(player).ifPresent(r -> store.put(r.withDisplaysRendered(season))),
                  serverThread);
    }
    return displays
        .exceptionally(e -> {
          LOG.warn(
              "Displays for {} weren't drawn; they are retried at their next login", player, e);
          return null;
        })
        .thenComposeAsync(
            done ->
                destination == Destination.CELL
                    ? sendToCell(player)
                    : CompletableFuture.completedFuture(null),
            serverThread);
  }

  /** The handler that finishes archives a crash left open. */
  public RecoveryHandler recovery() {
    return this::recover;
  }

  /** Whether anyone is being archived or unarchived right now. */
  public Set<PlayerId> busy() {
    return hold.held();
  }

  private CompletableFuture<Void> sendToCell(PlayerId player) {
    Optional<PlayerRecord> found = store.find(player);
    if (found.isEmpty() || found.get().pendingDestination().isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    int season = found.get().pendingDestination().getAsInt();
    Optional<Position> cell = hall.cells().cell(player, season);
    if (cell.isEmpty()) {
      if (noCellNoticeSent.add(season)) {
        announcer.admins(
            SeasonService.ADMIN_PERMISSION, "archive.no-cells", "season", Integer.toString(season));
      }
      store.find(player).ifPresent(r -> store.put(r.withDestinationReached()));
      return CompletableFuture.completedFuture(null);
    }
    return platform
        .players()
        .teleportSafely(player, cell.get())
        .thenAcceptAsync(
            moved -> {
              if (moved) {
                immunity.grant(player, config.get().config().hof().arrivalImmunity());
                store.find(player).ifPresent(r -> store.put(r.withDestinationReached()));
              }
            },
            serverThread);
  }

  /** Recovers one open archive. See Crash recovery in the spec. */
  private CompletableFuture<RecoveryOutcome> recover(OpenOperation operation) {
    Optional<PlayerId> who = operation.player();
    if (who.isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("the journal names no player"));
    }
    PlayerId player = who.get();
    int season = operation.season();
    if (platform.players().find(player).isEmpty()) {
      return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
    }
    JournalKind last = operation.lastStep().kind();
    return writer
        .submit(() -> archives.read(player, season))
        .thenComposeAsync(
            read -> {
              if (read.isEmpty()) {
                return CompletableFuture.completedFuture(
                    RecoveryOutcome.failed(
                        "the archive file for Season " + season + " is missing"));
              }
              ArchiveRecord archive = read.get();
              if (platform.players().find(player).isEmpty()) {
                return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
              }
              InventorySnapshot live = platform.inventories().snapshot(player);
              boolean matches =
                  ArchiveHash.of(live, archive.clearsEnderChest()).equals(archive.contentHash());
              boolean empty = ArchiveHash.isEmpty(live, archive.clearsEnderChest());
              if (!matches && !empty) {
                announcer.admins(
                    SeasonService.ADMIN_PERMISSION,
                    "archive.recovery-mismatch",
                    "name",
                    archive.name(),
                    "season",
                    Integer.toString(season));
                return CompletableFuture.completedFuture(
                    RecoveryOutcome.failed(
                        "the live inventory neither matches the archive nor is empty"));
              }
              if (!hold.hold(player)) {
                return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
              }
              int dataVersion = platform.inventories().dataVersion();
              CompletableFuture<Void> sealed =
                  last == JournalKind.ARCHIVE_STARTED
                      ? sealVault(archive, dataVersion)
                      : CompletableFuture.completedFuture(null);
              return sealed
                  .thenComposeAsync(done -> finish(archive, true), serverThread)
                  .handleAsync(
                      (outcome, failure) -> {
                        hold.release(player);
                        if (failure != null) {
                          return RecoveryOutcome.failed(reason(failure));
                        }
                        audit.record(
                            AuditLog.SEASONS,
                            "recovery",
                            archive.name(),
                            "ok",
                            "finished archiving into Season " + season + " after " + last);
                        return RecoveryOutcome.finished();
                      },
                      serverThread);
            },
            serverThread);
  }

  /** Step 3: the vault page (when the policy seals one), then {@code VAULT_SEALED}. */
  private CompletableFuture<Void> sealVault(ArchiveRecord archive, int dataVersion) {
    PlayerId player = archive.player();
    return writer
        .submit(() -> {
          if (archive.enderChestPolicy() == EnderChestPolicy.VAULT) {
            vaults.seal(
                player,
                archive.season(),
                archive.inventory().enderChest(),
                archive.contentHash(),
                archive.created(),
                dataVersion);
          }
          return Boolean.TRUE;
        })
        .thenCompose(written ->
            journal.append(
                JournalKind.VAULT_SEALED, player, archive.season(), archive.contentHash()))
        .thenAccept(entry -> crash.reached(CrashPoint.VAULT_SEALED, player));
  }

  /**
   * Steps 4 and 5, on the server thread: check, clear, reset, save the player's file, then mark
   * them archived and journal {@code ARCHIVE_DONE}.
   *
   * @param allowEmpty whether an already-cleared player counts as matching, as after a crash
   */
  private CompletableFuture<Outcome> finish(ArchiveRecord archive, boolean allowEmpty) {
    PlayerId player = archive.player();
    if (platform.players().find(player).isEmpty()) {
      throw new IllegalStateException(archive.name() + " left before their inventory was cleared");
    }
    InventorySnapshot live = platform.inventories().snapshot(player);
    boolean matches =
        ArchiveHash.of(live, archive.clearsEnderChest()).equals(archive.contentHash());
    if (!matches && !(allowEmpty && ArchiveHash.isEmpty(live, archive.clearsEnderChest()))) {
      throw new IllegalStateException(
          archive.name() + "'s inventory changed after it was copied, so it wasn't cleared");
    }
    // The cursor and grid aren't in the hash, since the game doesn't save them; while the player
    // is online they must still hold exactly the overflow. After a crash they hold whatever the
    // game kept, and clearing takes that.
    if (!allowEmpty && !looseMatches(player, archive)) {
      throw new IllegalStateException(
          archive.name()
              + "'s cursor or crafting grid changed after it was copied, so it wasn't"
              + " cleared");
    }
    clearAndReset(player, archive.clearsEnderChest());
    platform.players().saveData(player);
    crash.reached(CrashPoint.RESET, player);
    PlayerRecord record = store.getOrCreate(player, archive.name()).withArchived(archive.season());
    return store
        .putNow(record)
        .thenCompose(saved ->
            journal.append(
                JournalKind.ARCHIVE_DONE, player, archive.season(), archive.contentHash()))
        .thenApply(entry -> {
          crash.reached(CrashPoint.ARCHIVE_DONE, player);
          return (Outcome) new Outcome.Archived(archive);
        });
  }

  private boolean looseMatches(PlayerId player, ArchiveRecord archive) {
    List<Optional<NeutralItem>> loose =
        platform.inventories().loose(player).stream().map(Optional::of).toList();
    return ArchiveHash.ofLoose(loose).equals(ArchiveHash.ofLoose(archive.overflow()));
  }

  private void clearAndReset(PlayerId player, boolean enderChest) {
    Resets resets = config.get().config().resets();
    platform.inventories().clear(player, enderChest);
    platform
        .players()
        .reset(
            player,
            new ResetPlan(
                resets.xp() == ResetMode.RESET,
                resets.effects() == ClearMode.CLEAR,
                resets.respawnPoint() == ClearMode.CLEAR));
    if (resets.advancements() == AdvancementPolicy.ARCHIVE_AND_RESET) {
      platform.advancements().revokeAll(player, resets.recipes() == KeepMode.RESET);
    }
    if (resets.recipes() == KeepMode.RESET) {
      platform.advancements().forgetRecipes(player);
    }
  }

  /** Step 1, on the server thread. */
  private Capture capture(PlayerRef player, PlayerRecord record, int season) {
    PlayerId id = player.id();
    Resets resets = config.get().config().resets();
    List<NeutralItem> overflow = platform.inventories().settle(id);
    platform.players().saveData(id);
    InventorySnapshot inventory = platform.inventories().snapshot(id);
    boolean recipesReset = resets.recipes() == KeepMode.RESET;
    AdvancementSnapshot advancements = platform.advancements().snapshot(id, recipesReset);
    Map<String, Long> end = new HashMap<>(platform.statistics().general(id));
    end.putAll(platform.statistics().counts(id));
    Map<String, Long> baseline = record.baseline(season).orElse(Map.of());
    Map<String, Long> difference = new HashMap<>();
    end.forEach((stat, value) -> difference.put(stat, value - baseline.getOrDefault(stat, 0L)));
    FurthestBlock furthest = record.furthest(season);
    EnderChestPolicy policy = resets.enderChest();
    ArchiveRecord archive =
        new ArchiveRecord(
            id,
            player.name(),
            season,
            clock.instant(),
            inventory,
            overflow.stream().map(Optional::of).toList(),
            policy,
            advancements,
            platform.players().experience(id),
            difference,
            furthest.equals(FurthestBlock.NONE) ? Optional.empty() : Optional.of(furthest),
            platform.players().position(id),
            platform.players().skin(id),
            ArchiveHash.of(inventory, policy != EnderChestPolicy.KEEP));
    return new Capture(
        archive.withHonours(honours(id, season)), platform.inventories().dataVersion());
  }

  private record Capture(ArchiveRecord record, int dataVersion) {}

  /** The player's firsts in the season, and the awards they won if the season has them yet. */
  private ArchiveRecord.Honours honours(PlayerId player, int season) {
    Optional<SeasonState> state = seasonState.get();
    return new ArchiveRecord.Honours(
        state.map(s -> s.firsts(season)).orElse(List.of()).stream()
            .filter(first -> first.player().equals(player))
            .toList(),
        state.flatMap(s -> s.awards(season)).map(a -> a.wonBy(player)).orElse(List.of()).stream()
            .map(SeasonAwards.Award::id)
            .toList());
  }

  private Outcome archivedOutcome(PlayerId player, int season) {
    return store.find(player).filter(r -> r.archivedThrough() >= season).isPresent()
        ? new Outcome.AlreadyArchived()
        : new Outcome.Failed("an earlier archive is still waiting for recovery");
  }

  private Outcome failed(String name, int season, String reason, Throwable failure) {
    LOG.error("Archiving {} into Season {} stopped: {}", name, season, reason, failure);
    announcer.admins(
        SeasonService.ADMIN_PERMISSION,
        "archive.failed",
        "name",
        name,
        "season",
        Integer.toString(season),
        "reason",
        reason);
    audit.record(AuditLog.SEASONS, "archive", name, "failed", reason);
    return new Outcome.Failed(reason);
  }

  private static String summary(ArchiveRecord archive) {
    return "Season "
        + archive.season()
        + ": "
        + archive.carried().size()
        + " stacks carried, "
        + archive.enderChest().size()
        + " in the ender chest ("
        + archive.enderChestPolicy().name().toLowerCase(java.util.Locale.ROOT)
        + "), "
        + archive.advancements().completed()
        + "/"
        + archive.advancements().visible()
        + " advancements";
  }

  /** A failure's message, without the wrappers futures add. */
  static String reason(Throwable failure) {
    Throwable cause = rootCause(failure);
    return cause.getMessage() != null ? cause.getMessage() : cause.toString();
  }

  static Throwable rootCause(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException
            || cause instanceof java.util.concurrent.ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static <T> CompletableFuture<T> done(T value) {
    return CompletableFuture.completedFuture(value);
  }
}
