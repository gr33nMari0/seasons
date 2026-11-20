// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.archive.ArchiveHash;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.OpenOperation;
import com.petepeterepeat.seasons.core.store.Operation;
import com.petepeterepeat.seasons.core.store.RecoveryHandler;
import com.petepeterepeat.seasons.core.store.RecoveryOutcome;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The museum (see Ender vault, museum and dragon egg in the spec): galleries of kind {@code museum}
 * whose cells are plinths with one or more trophy anchors, no owners and display-only forever.
 * {@code /museum add <anchor> [label]} takes the op's held item into the store and shows a copy of
 * it with a plaque line; {@code /museum remove <anchor>} gives it back. A trophy anchor tagged
 * {@code egg: <season>} is that season's egg plinth: once the season has ended, it shows a dragon
 * egg with the slayer's name, unless something was put there by hand (questions 187 to 192). In a
 * cell with several trophy anchors, such as an egg wall, what is put in by hand shows at the first,
 * and each egg plinth shows its own egg (question 198).
 *
 * <p>Both commands are one transaction each, journalled as the vault's withdrawals are: save the
 * op's file and journal {@code MUSEUM_STARTED} with their inventory's hash, change the store, move
 * the item, save the op's file, then journal {@code MUSEUM_DONE}. After a crash, the op's saved
 * inventory tells whether the item moved, and the store is set to match. Until then, and while one
 * runs, nothing else moves in or out of that plinth (question 197).
 */
public final class Museum {
  private static final Logger LOG = LoggerFactory.getLogger(Museum.class);

  /** How high above its block a trophy floats, and a plaque without a plaque anchor. */
  private static final double TROPHY_HEIGHT = 0.5;

  private static final double PLAQUE_OVER_TROPHY = 1.3;
  private static final double PLAQUE_HEIGHT = 1.2;

  private static final String ADD = "add";
  private static final String REMOVE = "remove";

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final HallService hall;
  private final MuseumFiles files;
  private final SerialWriter writer;
  private final Optional<Journal> journal;
  private final ArchiveHold hold;
  private final AuditLog audit;
  private final CrashPoints crash;
  private final Clock clock;
  private final Executor serverThread;
  private final AtomicLong counter = new AtomicLong();

  /** Plinths with an add or remove running in this session. */
  private final Set<String> moving = ConcurrentHashMap.newKeySet();

  private Supplier<Optional<SeasonState>> state = Optional::empty;
  private @Nullable Reconciler reconciler;

  public Museum(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      HallService hall,
      MuseumFiles files,
      SerialWriter writer,
      Optional<Journal> journal,
      ArchiveHold hold,
      AuditLog audit,
      CrashPoints crash,
      Clock clock) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.files = files;
    this.writer = writer;
    this.journal = journal;
    this.hold = hold;
    this.audit = audit;
    this.crash = crash;
    this.clock = clock;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** The season state, and what draws the plinths; before anything is drawn. */
  public void bind(Supplier<Optional<SeasonState>> seasonState, Reconciler drawing) {
    this.state = seasonState;
    this.reconciler = drawing;
  }

  /** {@code /museum add <anchor> [label]} and {@code /museum remove <anchor>}. */
  public CommandNode command() {
    return CommandNode.literal("museum")
        .permission(HallCommands.PERMISSION_ADMIN)
        .then(
            CommandNode.literal("add")
                .then(
                    CommandNode.argument("anchor", ArgumentType.id())
                        .executes(this::add)
                        .then(
                            CommandNode.argument("label", ArgumentType.greedy())
                                .executes(this::add))))
        .then(
            CommandNode.literal("remove")
                .then(CommandNode.argument("anchor", ArgumentType.id()).executes(this::remove)))
        .build();
  }

  /** Whether a museum has an egg plinth for a season, as the end's dry run asks. */
  public static boolean hasEggPlinth(java.util.Collection<Gallery> galleries, int season) {
    return galleries.stream()
        .filter(g -> g.kind() == Gallery.Kind.MUSEUM)
        .flatMap(g -> g.cells().stream())
        .flatMap(c -> c.anchors(AnchorType.TROPHY).stream())
        .anyMatch(a -> a.egg().equals(OptionalInt.of(season)));
  }

  /** At season end: draws the egg on the season's plinth, with the slayer's name. */
  public void seasonEnded(int season) {
    for (Gallery gallery : hall.galleries()) {
      if (gallery.kind() != Gallery.Kind.MUSEUM) {
        continue;
      }
      for (Cell cell : gallery.cells()) {
        if (cell.anchors(AnchorType.TROPHY).stream()
            .anyMatch(a -> a.egg().equals(OptionalInt.of(season)))) {
          redraw(new HallService.CellRef(gallery, cell));
        }
      }
    }
  }

  void add(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    if (source.player().isEmpty()) {
      source.reply(messages.get("museum.player-only"));
      return;
    }
    PlayerId op = source.player().get();
    String name = arguments.string("anchor");
    Optional<HallService.CellRef> found = plinth(name);
    if (found.isEmpty()) {
      source.reply(messages.get("museum.unknown", "anchor", name));
      return;
    }
    if (journal.isEmpty()) {
      source.reply(messages.get("museum.unavailable"));
      return;
    }
    HallService.CellRef ref = found.get();
    String key = ref.key();
    InventorySnapshot inventory = platform.inventories().snapshot(op);
    int slot = platform.players().heldSlot(op);
    Optional<NeutralItem> held =
        slot < inventory.storage().size() ? inventory.storage().get(slot) : Optional.empty();
    if (held.isEmpty()) {
      source.reply(messages.get("museum.no-item"));
      return;
    }
    if (platform.inventories().isArchived(held.get())) {
      source.reply(messages.get("museum.archived"));
      return;
    }
    NeutralItem item = held.get();
    Optional<String> label =
        arguments.optionalString("label").map(String::strip).filter(l -> !l.isEmpty());
    var _ =
        writer
            .submit(() -> files.item(key))
            .thenComposeAsync(
                there -> {
                  if (there.isPresent()) {
                    source.reply(
                        messages()
                            .get("museum.occupied", "anchor", key, "item", itemId(there.get())));
                    return CompletableFuture.completedFuture(null);
                  }
                  return putIn(source, op, ref, slot, item, label);
                },
                serverThread)
            .exceptionally(failure -> failed(source, "Adding to " + key, failure));
  }

  /** The add transaction, on the server thread with the op held throughout. */
  private CompletableFuture<Void> putIn(
      CommandSource source,
      PlayerId op,
      HallService.CellRef ref,
      int slot,
      NeutralItem item,
      Optional<String> label) {
    String key = ref.key();
    if (!claim(source, key)) {
      return CompletableFuture.completedFuture(null);
    }
    if (!hold.hold(op)) {
      moving.remove(key);
      source.reply(messages().get("museum.busy"));
      return CompletableFuture.completedFuture(null);
    }
    String name = name(op);
    int season = state.get().map(SeasonState::season).orElse(0);
    platform.players().saveData(op);
    String before = ArchiveHash.of(platform.inventories().snapshot(op), false);
    String id = clock.millis() + "-" + counter.incrementAndGet();
    MuseumFiles.Trophy trophy =
        new MuseumFiles.Trophy(key, label, season, name, clock.instant(), id);
    return journal
        .orElseThrow()
        .append(JournalKind.MUSEUM_STARTED, op, season, payload(id, ADD, key, before))
        .thenCompose(entry ->
            writer.submit(() -> {
              files.put(trophy, item);
              return Boolean.TRUE;
            }))
        .thenApplyAsync(stored -> take(op, slot, item), serverThread)
        .thenCompose(taken ->
            (taken
                    ? CompletableFuture.completedFuture(Boolean.TRUE)
                    : writer.submit(() -> {
                      files.delete(key);
                      return Boolean.FALSE;
                    }))
                .thenCompose(kept -> done(op, season, id).thenApply(d -> kept)))
        .whenCompleteAsync(
            (kept, failure) -> {
              hold.release(op);
              moving.remove(key);
              if (failure != null) {
                failed(source, "Adding to " + key, failure);
                return;
              }
              if (kept) {
                audit.record(name, "museum add", key, "ok", label.orElse("") + ": " + itemId(item));
                source.reply(messages().get("museum.added", "item", itemId(item), "anchor", key));
              } else {
                source.reply(messages().get("museum.changed"));
              }
              redraw(ref);
            },
            serverThread)
        .thenApply(kept -> null);
  }

  /** Takes the held stack, if it is still the one put in the store, and saves the op's file. */
  private boolean take(PlayerId op, int slot, NeutralItem item) {
    crash.reached(CrashPoint.MUSEUM_STORED, op);
    if (platform.players().find(op).isEmpty()) {
      return false;
    }
    InventorySnapshot now = platform.inventories().snapshot(op);
    if (slot >= now.storage().size() || !now.storage().get(slot).equals(Optional.of(item))) {
      return false;
    }
    List<Optional<NeutralItem>> storage = new ArrayList<>(now.storage());
    storage.set(slot, Optional.empty());
    platform
        .inventories()
        .restore(op, new InventorySnapshot(storage, now.armour(), now.offHand(), now.enderChest()));
    platform.players().saveData(op);
    crash.reached(CrashPoint.MUSEUM_TAKEN, op);
    return true;
  }

  void remove(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    if (source.player().isEmpty()) {
      source.reply(messages.get("museum.player-only"));
      return;
    }
    PlayerId op = source.player().get();
    String name = arguments.string("anchor");
    Optional<HallService.CellRef> found = plinth(name);
    if (found.isEmpty()) {
      source.reply(messages.get("museum.unknown", "anchor", name));
      return;
    }
    if (journal.isEmpty()) {
      source.reply(messages.get("museum.unavailable"));
      return;
    }
    HallService.CellRef ref = found.get();
    String key = ref.key();
    var _ =
        writer
            .submit(() -> files.item(key))
            .thenComposeAsync(
                there -> {
                  if (there.isEmpty()) {
                    source.reply(messages().get("museum.empty", "anchor", key));
                    return CompletableFuture.completedFuture(null);
                  }
                  return takeOut(source, op, ref);
                },
                serverThread)
            .exceptionally(failure -> failed(source, "Removing from " + key, failure));
  }

  /** The remove transaction, on the server thread with the op held throughout. */
  private CompletableFuture<Void> takeOut(
      CommandSource source, PlayerId op, HallService.CellRef ref) {
    String key = ref.key();
    if (!claim(source, key)) {
      return CompletableFuture.completedFuture(null);
    }
    if (!hold.hold(op)) {
      moving.remove(key);
      source.reply(messages().get("museum.busy"));
      return CompletableFuture.completedFuture(null);
    }
    String name = name(op);
    int season = state.get().map(SeasonState::season).orElse(0);
    platform.players().saveData(op);
    String before = ArchiveHash.of(platform.inventories().snapshot(op), false);
    String id = clock.millis() + "-" + counter.incrementAndGet();
    return journal
        .orElseThrow()
        .append(JournalKind.MUSEUM_STARTED, op, season, payload(id, REMOVE, key, before))
        .thenCompose(entry -> writer.submit(() -> files.takeOut(key, id)))
        .thenComposeAsync(
            taken -> {
              boolean given =
                  taken.isPresent()
                      && platform.players().find(op).isPresent()
                      && platform.inventories().give(op, taken.get());
              if (given) {
                platform.players().saveData(op);
              }
              return writer
                  .submit(() -> {
                    if (given) {
                      files.forget(id);
                    } else {
                      files.putBack(id);
                    }
                    return new Removal(given, taken);
                  })
                  .thenCompose(removal -> done(op, season, id).thenApply(d -> removal));
            },
            serverThread)
        .whenCompleteAsync(
            (removal, failure) -> {
              hold.release(op);
              moving.remove(key);
              if (failure != null) {
                failed(source, "Removing from " + key, failure);
                return;
              }
              String what = removal.item().map(Museum::itemId).orElse("?");
              if (removal.given()) {
                audit.record(name, "museum remove", key, "ok", what);
                source.reply(messages().get("museum.removed", "item", what, "anchor", key));
              } else {
                audit.record(name, "museum remove", key, "refused", "no room for " + what);
                source.reply(messages().get("museum.full", "item", what, "anchor", key));
              }
              redraw(ref);
            },
            serverThread)
        .thenApply(removal -> null);
  }

  /**
   * Claims a plinth for one add or remove, on the server thread. Refused while another runs on it,
   * or while the journal shows one a crash left open: until recovery settles that one, the store
   * may hold an item the op still has, or none while the op hasn't got it back.
   */
  private boolean claim(CommandSource source, String key) {
    if (unsettled(key) || !moving.add(key)) {
      source.reply(messages().get("museum.unsettled", "anchor", key));
      return false;
    }
    return true;
  }

  /** Whether the journal shows an add or remove on a plinth that hasn't finished. */
  private boolean unsettled(String key) {
    return journal.isPresent()
        && JournalRecovery.openOperations(journal.get().entries()).stream()
            .filter(open -> open.operation() == Operation.MUSEUM)
            .map(open -> open.steps().getFirst().payloadHash().split("/", 4))
            .anyMatch(payload -> payload.length == 4 && payload[2].equals(key));
  }

  /** What a removal did: whether the item was given, and the item. */
  private record Removal(boolean given, Optional<NeutralItem> item) {}

  private CompletableFuture<Void> done(PlayerId op, int season, String id) {
    return journal
        .orElseThrow()
        .append(JournalKind.MUSEUM_DONE, op, season, id)
        .thenApply(entry -> null);
  }

  /** The handler that settles an add or remove a crash left open. */
  public RecoveryHandler recovery() {
    return this::recover;
  }

  /**
   * An add or remove left open: the op's saved inventory, compared with its hash from before, says
   * whether the item moved, and the store is set to match.
   */
  private CompletableFuture<RecoveryOutcome> recover(OpenOperation operation) {
    if (operation.player().isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("the journal names no player"));
    }
    PlayerId op = operation.player().get();
    if (platform.players().find(op).isEmpty()) {
      return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
    }
    String[] payload = operation.steps().getFirst().payloadHash().split("/", 4);
    if (payload.length < 4) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("a museum entry with a damaged payload"));
    }
    String id = payload[0];
    String kind = payload[1];
    String key = payload[2];
    String before = payload[3];
    boolean unmoved = ArchiveHash.of(platform.inventories().snapshot(op), false).equals(before);
    return writer
        .submit(() -> {
          if (kind.equals(ADD)) {
            Optional<MuseumFiles.Trophy> trophy = files.trophy(key);
            if (trophy.isPresent() && trophy.get().id().equals(id) && unmoved) {
              files.delete(key);
              return "the item stayed with the op, so it is out of the store";
            }
            return "settled";
          }
          if (files.takenOut(id)) {
            if (unmoved) {
              files.putBack(id);
              return "the item wasn't given, so it is back on its plinth";
            }
            files.forget(id);
          }
          return "settled";
        })
        .thenCompose(what -> {
          LOG.info("Recovered a museum {} at {}: {}", kind, key, what);
          audit.record(AuditLog.SEASONS, "museum " + kind, key, "recovered", what);
          return done(op, operation.season(), id);
        })
        .thenApplyAsync(
            done -> {
              if (hall.resolve(key) instanceof HallService.Resolved.Found found) {
                redraw(found.ref());
              }
              return RecoveryOutcome.finished();
            },
            serverThread);
  }

  /**
   * What a museum plinth shows: what was put in by hand, with its plaque, over its first trophy
   * anchor; and on each egg plinth whose season has ended, a dragon egg with the slayer's name,
   * unless the hand-placed item stands there. Completes on the server thread.
   */
  CompletableFuture<List<HallRenderer.Planned>> plan(HallService.CellRef ref) {
    if (ref.cell().anchors(AnchorType.TROPHY).isEmpty()) {
      return CompletableFuture.completedFuture(List.of());
    }
    String key = ref.key();
    return writer
        .submit(() -> new Stored(files.trophy(key), files.item(key)))
        .thenApplyAsync(stored -> plan(ref, stored), serverThread);
  }

  private record Stored(Optional<MuseumFiles.Trophy> trophy, Optional<NeutralItem> item) {}

  private List<HallRenderer.Planned> plan(HallService.CellRef ref, Stored stored) {
    Messages messages = messages();
    List<Anchor> trophies = ref.cell().anchors(AnchorType.TROPHY);
    boolean byHand = stored.item().isPresent() && stored.trophy().isPresent();
    List<HallRenderer.Planned> planned = new ArrayList<>();
    for (int i = 0; i < trophies.size(); i++) {
      Anchor trophy = trophies.get(i);
      if (i == 0 && byHand) {
        MuseumFiles.Trophy t = stored.trophy().get();
        String season = Integer.toString(t.season());
        Message plaque =
            t.label().isPresent()
                ? messages.get(
                    "museum.plaque",
                    "label",
                    t.label().get(),
                    "season",
                    season,
                    "credit",
                    t.credit())
                : messages.get("museum.plaque-unlabelled", "season", season, "credit", t.credit());
        show(ref, trophy, true, stored.item().get(), plaque, planned);
      } else if (trophy.egg().isPresent()) {
        Optional<SeasonFirst> slain = slayer(trophy.egg().getAsInt());
        if (slain.isPresent()) {
          Message plaque =
              messages.get(
                  "museum.egg-plaque",
                  "season",
                  Integer.toString(slain.get().season()),
                  "name",
                  slain.get().name());
          show(ref, trophy, i == 0, platform.dragonFight().eggItem(), plaque, planned);
        }
      }
    }
    return planned;
  }

  /**
   * One trophy and its plaque: over the cell's plaque anchor for the first trophy anchor, if the
   * cell has one, and otherwise over the trophy itself.
   */
  private static void show(
      HallService.CellRef ref,
      Anchor trophy,
      boolean first,
      NeutralItem item,
      Message plaque,
      List<HallRenderer.Planned> planned) {
    String gallery = ref.gallery().id();
    String cell = ref.cell().id();
    planned.add(
        new HallRenderer.Planned.Item(
            above(trophy.at(), TROPHY_HEIGHT),
            item,
            new DisplayTag(gallery, cell, 0, DisplayTag.TROPHY).toString()));
    Optional<Anchor> plaqueAnchor = first ? ref.cell().anchor(AnchorType.PLAQUE) : Optional.empty();
    Position at =
        plaqueAnchor
            .map(p -> above(p.at(), PLAQUE_HEIGHT))
            .orElseGet(() -> above(trophy.at(), PLAQUE_OVER_TROPHY));
    planned.add(
        new HallRenderer.Planned.Text(
            at, plaque, new DisplayTag(gallery, cell, 0, DisplayTag.PLAQUE).toString()));
  }

  /** The slayer of a season that has ended, if it had one. */
  private Optional<SeasonFirst> slayer(int season) {
    Optional<SeasonState> current = state.get();
    if (current.isEmpty()) {
      return Optional.empty();
    }
    SeasonState s = current.get();
    boolean ended =
        s.season() > season
            || (s.season() == season
                && (s.phase() instanceof Phase.Archiving
                    || s.phase() instanceof Phase.Intermission));
    if (!ended) {
      return Optional.empty();
    }
    return s.firsts(season).stream().filter(f -> f.key().equals(FirstsTracker.DRAGON)).findFirst();
  }

  /** A museum cell with a trophy anchor, by {@code gallery:cell}. */
  private Optional<HallService.CellRef> plinth(String name) {
    if (hall.resolve(name) instanceof HallService.Resolved.Found found
        && found.ref().gallery().kind() == Gallery.Kind.MUSEUM
        && found.ref().cell().anchor(AnchorType.TROPHY).isPresent()) {
      return Optional.of(found.ref());
    }
    return Optional.empty();
  }

  private void redraw(HallService.CellRef ref) {
    if (reconciler != null) {
      var _ = reconciler.reconcile(ref);
    }
  }

  private @Nullable Void failed(CommandSource source, String what, Throwable failure) {
    LOG.error("{} failed", what, failure);
    source.reply(messages().get("museum.failed", "error", String.valueOf(failure.getMessage())));
    return null;
  }

  private String name(PlayerId player) {
    return platform.players().find(player).map(PlayerRef::name).orElse(player.toString());
  }

  private static String payload(String id, String kind, String key, String before) {
    return id + "/" + kind + "/" + key + "/" + before;
  }

  private static Position above(Position block, double height) {
    return Position.of(
        block.dimension(), block.blockX() + 0.5, block.blockY() + height, block.blockZ() + 0.5);
  }

  /** The item's id, such as {@code minecraft:heavy_core}, from its vanilla data. */
  static String itemId(NeutralItem item) {
    try {
      return Nbt.read(item.nbt()).get("id") instanceof String id ? id : "?";
    } catch (IOException e) {
      return "?";
    }
  }

  private Messages messages() {
    return config.get().messages();
  }
}
