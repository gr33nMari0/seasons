// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.vault;

import com.petepeterepeat.seasons.core.archive.ArchiveHash;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PagedView;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.OpenOperation;
import com.petepeterepeat.seasons.core.store.RecoveryHandler;
import com.petepeterepeat.seasons.core.store.RecoveryOutcome;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ender vault: {@code /vault} shows a player's sealed ender chests, one page per season with
 * the newest first, then the Returned page. Season pages are read-only until Homecoming and
 * withdraw-only after it, for everything; the Returned page is withdraw-only at once. Between
 * seasons every season page is read-only again, so nothing taken then is carried into the next.
 *
 * <p>Each click that withdraws is one transaction, queued per player: save the player's file and
 * journal {@code VAULT_WITHDRAW} with their inventory's hash, take the item out of the vault file,
 * give it, save the player's file, then journal {@code VAULT_WITHDRAW_DONE}. If the give fails (a
 * full inventory, or they left), the item goes back into the vault. The menu itself never moves
 * anything.
 */
public final class Vault {
  /** Permission for {@code /vault}. */
  public static final String PERMISSION = "seasons.vault";

  private static final Logger LOG = LoggerFactory.getLogger(Vault.class);

  /** What a player may do with their season pages right now. */
  public enum Access {
    /** Before Homecoming: look, don't take. */
    SEALED,
    /** Between seasons, or before Seasons has a season: look, don't take. */
    BETWEEN,
    /** After Homecoming while the season is played: take anything. */
    OPEN
  }

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final Supplier<Optional<SeasonState>> state;
  private final SerialWriter writer;
  private final Journal journal;
  private final VaultFiles vaults;
  private final ArchiveHold hold;
  private final AuditLog audit;
  private final CrashPoints crash;
  private final Clock clock;
  private final Executor serverThread;
  private final Map<PlayerId, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();
  private final AtomicLong counter = new AtomicLong();

  public Vault(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      Supplier<Optional<SeasonState>> state,
      SerialWriter writer,
      Journal journal,
      VaultFiles vaults,
      ArchiveHold hold,
      AuditLog audit,
      CrashPoints crash,
      Clock clock) {
    this.platform = platform;
    this.config = config;
    this.state = state;
    this.writer = writer;
    this.journal = journal;
    this.vaults = vaults;
    this.hold = hold;
    this.audit = audit;
    this.crash = crash;
    this.clock = clock;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** What season pages allow in a season state. Pure. */
  public static Access access(Optional<SeasonState> state) {
    if (state.isEmpty()) {
      return Access.BETWEEN;
    }
    return switch (state.get().phase()) {
      case Phase.Active active -> active.stage() == Stage.OPEN ? Access.OPEN : Access.SEALED;
      case Phase.Countdown countdown ->
          countdown.stageBefore() == Stage.OPEN ? Access.OPEN : Access.SEALED;
      case Phase.Archiving archiving -> Access.BETWEEN;
      case Phase.Intermission intermission -> Access.BETWEEN;
    };
  }

  /** {@code /vault}. */
  public CommandNode command() {
    return CommandNode.literal("vault").permission(PERMISSION).executes(this::open).build();
  }

  void open(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("vault.player-only"));
      return;
    }
    var _ = open(source.player().get(), 0, true);
  }

  /**
   * Opens a player's vault on one page (0-based). With {@code tell}, as for {@code /vault}, they
   * hear what they may do, or that the vault is empty and no menu opens; without it, as after a
   * withdrawal, the menu opens again even with nothing left in it. Completes once that is done.
   */
  public CompletableFuture<Void> open(PlayerId viewer, int startPage, boolean tell) {
    return writer
        .submit(() -> vaults.read(viewer))
        .thenAcceptAsync(
            contents -> {
              if (platform.players().find(viewer).isEmpty()) {
                return;
              }
              Messages messages = messages();
              if (contents.isEmpty() && tell) {
                platform.players().sendMessage(viewer, messages.get("vault.empty"));
                return;
              }
              Access access = access(state.get());
              if (tell) {
                platform
                    .players()
                    .sendMessage(
                        viewer,
                        messages.get(
                            switch (access) {
                              case SEALED -> "vault.sealed";
                              case BETWEEN -> "vault.between";
                              case OPEN -> "vault.open";
                            }));
              }
              Layout layout = layout(contents, messages);
              platform
                  .menus()
                  .openWithdraw(
                      viewer,
                      layout.view(),
                      startPage,
                      (page, slot) -> clicked(viewer, layout, page, slot));
            },
            serverThread)
        .exceptionally(failure -> {
          LOG.error("Can't open the vault of {}", viewer, failure);
          return null;
        });
  }

  /**
   * Hands an item back to a player on their Returned page, which they can take from at once, such
   * as a dragon egg they had no room for or a curator's return.
   */
  public CompletableFuture<Void> returnItem(PlayerId player, NeutralItem item, String why) {
    return returnItem(player, item, why, Optional.empty());
  }

  /**
   * As {@link #returnItem(PlayerId, NeutralItem, String)}, under a reference that {@link
   * VaultFiles#delivered} finds afterwards, so a crash can tell whether it arrived.
   */
  public CompletableFuture<Void> returnItem(
      PlayerId player, NeutralItem item, String why, Optional<String> ref) {
    return writer
        .submit(() -> {
          // With a reference, a second call adds nothing.
          vaults.addReturned(player, item, clock.instant(), ref);
          return Boolean.TRUE;
        })
        .thenRun(() ->
            audit.record(
                AuditLog.SEASONS,
                "vault-return",
                player.toString(),
                "ok",
                describe(item) + ": " + why));
  }

  /**
   * Gives a player the item with a reference on their Returned page, as a dragon egg is handed
   * over: the same transaction as a click, queued with their clicks, but no menu opens and the
   * messages are the caller's. Completes with whether it was given; if not, it stays on the page.
   *
   * @param took said once it is given
   * @param kept said if it stays on the page: a full inventory, or a moment it can't move
   */
  public CompletableFuture<Boolean> deliver(PlayerId player, String ref, String took, String kept) {
    return writer
        .submit(() -> slotOf(vaults, player, ref))
        .thenCompose(slot -> {
          if (slot.isEmpty()) {
            return CompletableFuture.completedFuture(false);
          }
          CompletableFuture<Boolean> given = new CompletableFuture<>();
          queue(
              player,
              () ->
                  withdraw(
                          player,
                          VaultFiles.RETURNED,
                          slot.getAsInt(),
                          new Notice(OptionalInt.empty(), took, kept, kept))
                      .whenComplete((result, failure) ->
                          given.complete(failure == null && Boolean.TRUE.equals(result))));
          return given;
        });
  }

  /** The Returned slot the item with this reference went to, while it is still there. */
  private static OptionalInt slotOf(VaultFiles vaults, PlayerId player, String ref)
      throws IOException {
    VaultFiles.Contents contents = vaults.read(player);
    OptionalInt slot = vaults.returnedSlot(player, ref);
    if (slot.isEmpty()
        || slot.getAsInt() >= contents.returned().size()
        || contents.returned().get(slot.getAsInt()).isEmpty()) {
      return OptionalInt.empty();
    }
    return slot;
  }

  /**
   * What a withdrawal says and does after: the menu page to open again, if any, and the messages
   * for an item given, kept for a full inventory, or kept because the player is busy.
   */
  private record Notice(OptionalInt reopen, String took, String full, String busy) {
    static Notice menu(int shownPage) {
      return new Notice(OptionalInt.of(shownPage), "vault.took", "vault.full", "vault.busy");
    }
  }

  /** The handler that settles withdrawals a crash left open. */
  public RecoveryHandler recovery() {
    return this::recover;
  }

  /** Where each page of the menu comes from: a vault page and the first of its slots shown. */
  record Layout(PagedView view, List<Integer> pages, List<Integer> offsets) {}

  static Layout layout(VaultFiles.Contents contents, Messages messages) {
    List<List<Optional<NeutralItem>>> shown = new ArrayList<>();
    List<Integer> pages = new ArrayList<>();
    List<Integer> offsets = new ArrayList<>();
    List<String> names = new ArrayList<>();
    contents
        .pages()
        .forEach((season, items) -> {
          String name = messages.text("vault.page-season", "season", Integer.toString(season));
          addPages(shown, pages, offsets, names, season, items, name);
        });
    // The Returned page shows while it holds something, and as the only page once all is taken.
    if (contents.returned().stream().anyMatch(Optional::isPresent) || shown.isEmpty()) {
      addPages(
          shown,
          pages,
          offsets,
          names,
          VaultFiles.RETURNED,
          contents.returned(),
          messages.text("vault.page-returned"));
    }
    List<Message> labels = new ArrayList<>();
    for (int i = 0; i < shown.size(); i++) {
      labels.add(
          messages.get(
              "vault.page",
              "name",
              names.get(i),
              "page",
              Integer.toString(i + 1),
              "pages",
              Integer.toString(shown.size())));
    }
    return new Layout(
        new PagedView(
            messages.get("vault.title"),
            shown,
            messages.get("view.previous"),
            messages.get("view.next"),
            labels),
        pages,
        offsets);
  }

  private static void addPages(
      List<List<Optional<NeutralItem>>> shown,
      List<Integer> pages,
      List<Integer> offsets,
      List<String> names,
      int page,
      List<Optional<NeutralItem>> items,
      String name) {
    int count = Math.max(1, (items.size() + PagedView.PAGE_SIZE - 1) / PagedView.PAGE_SIZE);
    for (int i = 0; i < count; i++) {
      int from = i * PagedView.PAGE_SIZE;
      shown.add(
          from >= items.size()
              ? List.of()
              : items.subList(from, Math.min(items.size(), from + PagedView.PAGE_SIZE)));
      pages.add(page);
      offsets.add(from);
      names.add(name);
    }
  }

  private void clicked(PlayerId player, Layout layout, int shownPage, int slot) {
    if (shownPage < 0 || shownPage >= layout.pages().size()) {
      return;
    }
    int page = layout.pages().get(shownPage);
    int vaultSlot = layout.offsets().get(shownPage) + slot;
    Access access = access(state.get());
    if (page != VaultFiles.RETURNED && access != Access.OPEN) {
      platform
          .players()
          .sendMessage(
              player,
              messages().get(access == Access.SEALED ? "vault.locked" : "vault.locked-between"));
      return;
    }
    queue(player, () -> withdraw(player, page, vaultSlot, Notice.menu(shownPage)));
  }

  /** One transaction at a time per player, in the order asked for. */
  private void queue(PlayerId player, Supplier<CompletableFuture<Boolean>> transaction) {
    queues.compute(
        player,
        (who, tail) ->
            (tail == null ? CompletableFuture.<Void>completedFuture(null) : tail)
                .thenCompose(done -> transaction.get())
                .<Void>thenApply(given -> null)
                .exceptionally(failure -> {
                  LOG.error("A withdrawal from the vault of {} failed", player, failure);
                  return null;
                }));
  }

  /**
   * One withdrawal, run on the server thread with the player held throughout. Completes with
   * whether the item was given.
   */
  private CompletableFuture<Boolean> withdraw(PlayerId player, int page, int slot, Notice notice) {
    CompletableFuture<Boolean> done = new CompletableFuture<>();
    serverThread.execute(() -> {
      if (platform.players().find(player).isEmpty()) {
        done.complete(false);
        return;
      }
      if (page != VaultFiles.RETURNED && access(state.get()) != Access.OPEN) {
        platform.players().sendMessage(player, messages().get("vault.locked"));
        done.complete(false);
        return;
      }
      if (!hold.hold(player)) {
        platform.players().sendMessage(player, messages().get(notice.busy()));
        done.complete(false);
        return;
      }
      platform.players().saveData(player);
      String before = ArchiveHash.of(platform.inventories().snapshot(player), false);
      String id = clock.millis() + "-" + counter.incrementAndGet();
      AtomicBoolean given = new AtomicBoolean();
      var _ =
          journal
              .append(JournalKind.VAULT_WITHDRAW, player, page, id + "/" + before)
              .thenCompose(entry -> {
                crash.reached(CrashPoint.VAULT_WITHDRAW_STARTED, player);
                return writer.submit(
                    () -> vaults.withdraw(player, page, slot, id, clock.instant()));
              })
              .thenComposeAsync(
                  taken -> {
                    crash.reached(CrashPoint.VAULT_WITHDRAW_PERSISTED, player);
                    return taken.isEmpty()
                        ? finish(player, page, id)
                        : give(player, page, id, taken.get(), given, notice);
                  },
                  serverThread)
              .handle((result, failure) -> failure)
              .thenCompose(failure ->
                  failure == null || given.get()
                      ? CompletableFuture.completedFuture(failure)
                      : settleFailed(player, page, id).thenApply(settled -> failure))
              .whenCompleteAsync(
                  (failure, unsettled) -> {
                    hold.release(player);
                    if (failure != null) {
                      LOG.error("Withdrawing from the vault of {} stopped", player, failure);
                      platform.players().sendMessage(player, messages().get("vault.failed"));
                    }
                    if (notice.reopen().isPresent()
                        && platform.players().find(player).isPresent()) {
                      var _ = open(player, notice.reopen().getAsInt(), false);
                    }
                    done.complete(given.get());
                  },
                  serverThread);
    });
    return done;
  }

  /**
   * A withdrawal that stopped before its item was given, while the server runs on: the item goes
   * back in the vault now, if it left, and the withdrawal is closed. Left to recovery, it would be
   * judged by an inventory the player has changed since, and taken as given.
   */
  private CompletableFuture<Void> settleFailed(PlayerId player, int page, String id) {
    return writer
        .submit(() -> vaults.rollBack(player, id))
        .thenCompose(back -> finish(player, page, id))
        .exceptionally(failure -> {
          LOG.error(
              "Can't put back withdrawal {} from the vault of {}; recovery will try",
              id,
              player,
              failure);
          return null;
        });
  }

  /** On the server thread: gives the item, or puts it back if it won't go. */
  private CompletableFuture<Void> give(
      PlayerId player, int page, String id, NeutralItem item, AtomicBoolean given, Notice notice) {
    given.set(
        platform.players().find(player).isPresent() && platform.inventories().give(player, item));
    if (given.get()) {
      platform.players().saveData(player);
      crash.reached(CrashPoint.VAULT_WITHDRAW_GIVEN, player);
      return finish(player, page, id)
          .thenRunAsync(
              () -> {
                audit.record(
                    name(player),
                    "vault-withdraw",
                    name(player),
                    "ok",
                    page(page) + ": " + describe(item));
                platform
                    .players()
                    .sendMessage(
                        player,
                        messages()
                            .get(
                                notice.took(),
                                "count",
                                Integer.toString(count(item)),
                                "item",
                                itemId(item)));
              },
              serverThread);
    }
    return writer
        .submit(() -> vaults.rollBack(player, id))
        .thenCompose(back -> finish(player, page, id))
        .thenRunAsync(
            () -> {
              audit.record(
                  name(player),
                  "vault-withdraw",
                  name(player),
                  "refused",
                  page(page) + ": no room for " + describe(item));
              platform.players().sendMessage(player, messages().get(notice.full()));
            },
            serverThread);
  }

  private CompletableFuture<Void> finish(PlayerId player, int page, String id) {
    return journal
        .append(JournalKind.VAULT_WITHDRAW_DONE, player, page, id)
        .thenApply(entry -> null);
  }

  /**
   * A withdrawal left open: if the item never left the vault file, or the player's saved inventory
   * is still what it was before, the item is in the vault (put back if needed); otherwise it was
   * given and saved.
   */
  private CompletableFuture<RecoveryOutcome> recover(OpenOperation operation) {
    if (operation.player().isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("the journal names no player"));
    }
    PlayerId player = operation.player().get();
    if (platform.players().find(player).isEmpty()) {
      return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
    }
    String[] payload = operation.steps().getFirst().payloadHash().split("/", 2);
    String id = payload[0];
    String before = payload.length > 1 ? payload[1] : "";
    int page = operation.season();
    String live = ArchiveHash.of(platform.inventories().snapshot(player), false);
    return writer
        .submit(() -> {
          if (vaults.withdrawn(player, id) && live.equals(before)) {
            vaults.rollBack(player, id);
            return "rolled back";
          }
          return "settled";
        })
        .thenCompose(what -> {
          LOG.info("Recovered a vault withdrawal of {}: {}", player, what);
          audit.record(
              AuditLog.SEASONS,
              "vault-withdraw",
              name(player),
              "recovered",
              page(page) + ": " + what);
          return finish(player, page, id);
        })
        .thenApply(done -> RecoveryOutcome.finished());
  }

  private String name(PlayerId player) {
    return platform.players().find(player).map(PlayerRef::name).orElse(player.toString());
  }

  private static String page(int page) {
    return page == VaultFiles.RETURNED ? "Returned page" : "Season " + page + " page";
  }

  private static String describe(NeutralItem item) {
    return count(item) + " × " + itemId(item);
  }

  /** The item's id, such as {@code minecraft:emerald}, from its vanilla data. */
  static String itemId(NeutralItem item) {
    try {
      return Nbt.read(item.nbt()).get("id") instanceof String id ? id : "?";
    } catch (IOException e) {
      return "?";
    }
  }

  static int count(NeutralItem item) {
    try {
      return Nbt.read(item.nbt()).get("count") instanceof Number n ? n.intValue() : 1;
    } catch (IOException e) {
      return 1;
    }
  }

  private Messages messages() {
    return config.get().messages();
  }
}
