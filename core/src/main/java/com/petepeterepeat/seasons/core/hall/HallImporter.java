// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveHash;
import com.petepeterepeat.seasons.core.archive.ArchivePages;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ImportedCell;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.Imports;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.Skin;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.OpenOperation;
import com.petepeterepeat.seasons.core.store.RecoveryHandler;
import com.petepeterepeat.seasons.core.store.RecoveryOutcome;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /hof import}: turns Season 1's hand-built cells into archive records, cell by cell, and
 * {@code /hof import undo} puts them back. See Importing Season 1's cells in the spec.
 *
 * <p>Each cell's import is journalled like an archive. The record is written and read back first,
 * and the cell is emptied only once it is checked, so a crash at any point leaves every item in the
 * record, in the cell, or both; recovery then empties whatever still matches the record. No item is
 * ever dropped or copied into the world.
 */
public final class HallImporter {
  private static final Logger LOG = LoggerFactory.getLogger(HallImporter.class);

  /** How long recovery waits after startup, so the world is ticking before it scans. */
  private static final Duration RECOVERY_DELAY = Duration.ofSeconds(1);

  private final Platform platform;
  private final SerialWriter writer;
  private final Journal journal;
  private final ArchiveFiles archives;
  private final PlayerStore players;
  private final HallService hall;
  private final HallRenderer renderer;
  private final AuditLog audit;
  private final CrashPoints crash;
  private final Clock clock;
  private final Path reports;
  private final Executor serverThread;
  private final AtomicBoolean busy = new AtomicBoolean();

  /**
   * @param reports where {@code import-<gallery>.txt} is written
   */
  public HallImporter(
      Platform platform,
      SerialWriter writer,
      Journal journal,
      ArchiveFiles archives,
      PlayerStore players,
      HallService hall,
      HallRenderer renderer,
      AuditLog audit,
      CrashPoints crash,
      Clock clock,
      Path reports) {
    this.platform = platform;
    this.writer = writer;
    this.journal = journal;
    this.archives = archives;
    this.players = players;
    this.hall = hall;
    this.renderer = renderer;
    this.audit = audit;
    this.crash = crash;
    this.clock = clock;
    this.reports = reports;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** What one cell's scan found. */
  record Plan(Cell cell, Imports.Scan scan, ImportedCell imported, Owner owner) {}

  /** Who a cell belongs to, or why that isn't clear. */
  sealed interface Owner {
    /**
     * The owner is clear.
     *
     * @param how where the owner came from, for the report
     */
    record Found(PlayerId id, String name, Optional<Skin> skin, String how) implements Owner {}

    record Flagged(String reason) implements Owner {}

    /** The cell was imported already. */
    record Done() implements Owner {}
  }

  /** What a run came to. */
  public sealed interface Result {
    record DryRun(int ready, int flagged, String report) implements Result {}

    record Imported(int imported, int skipped) implements Result {}

    record Stopped(String cell, String reason) implements Result {}

    record Undone(int cells) implements Result {}

    record UndoStopped(String cell, String reason) implements Result {}

    record Busy() implements Result {}
  }

  /** A step that can't go on, with the reason for the admin. */
  static final class Stop extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Stop(String reason) {
      super(reason, null, false, false);
    }
  }

  /** The cell changed between the scan and the import, which then changed nothing. */
  static final class Unchanged extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unchanged(String reason) {
      super(reason, null, false, false);
    }
  }

  /** The report's file name for a gallery. */
  public static String reportName(String gallery) {
    return "import-" + gallery + ".txt";
  }

  /**
   * Scans a gallery's cells (or one of them) and imports every cell whose owner is clear, or with
   * {@code dryRun} only writes the report.
   */
  public CompletableFuture<Result> run(
      Gallery gallery, Optional<String> only, boolean dryRun, String actor) {
    if (!busy.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(new Result.Busy());
    }
    List<Cell> cells =
        gallery.cells().stream()
            .filter(c -> only.map(c.id()::equals).orElse(true))
            .filter(c -> c.bounds().isPresent())
            .toList();
    List<Plan> plans = Collections.synchronizedList(new ArrayList<>());
    CompletableFuture<Void> scanned = CompletableFuture.completedFuture(null);
    for (Cell cell : cells) {
      scanned =
          scanned
              .thenComposeAsync(done -> plan(gallery, cell), serverThread)
              .thenAccept(plans::add);
    }
    return scanned
        .thenComposeAsync(
            done ->
                dryRun
                    ? report(gallery, plans, true)
                        .thenApply(name -> (Result) dryRunResult(plans, name))
                    : importAll(gallery, List.copyOf(plans), actor)
                        .thenCompose(
                            result -> report(gallery, plans, false).thenApply(name -> result)),
            serverThread)
        .whenComplete((result, failure) -> busy.set(false));
  }

  private static Result dryRunResult(List<Plan> plans, String report) {
    int ready = 0;
    int flagged = 0;
    for (Plan plan : plans) {
      switch (plan.owner()) {
        case Owner.Found found -> ready++;
        case Owner.Flagged flag -> flagged++;
        case Owner.Done done -> {}
      }
    }
    return new Result.DryRun(ready, flagged, report);
  }

  private CompletableFuture<Result> importAll(Gallery gallery, List<Plan> plans, String actor) {
    int[] counts = new int[2];
    CompletableFuture<Optional<Result>> chain = CompletableFuture.completedFuture(Optional.empty());
    for (Plan plan : plans) {
      chain =
          chain.thenComposeAsync(
              stopped -> {
                if (stopped.isPresent()) {
                  return CompletableFuture.completedFuture(stopped);
                }
                return switch (plan.owner()) {
                  case Owner.Flagged flag -> {
                    counts[1]++;
                    yield CompletableFuture.completedFuture(Optional.<Result>empty());
                  }
                  case Owner.Done done ->
                      CompletableFuture.completedFuture(Optional.<Result>empty());
                  case Owner.Found found ->
                      importCell(gallery, plan, found, actor)
                          .handle((ok, failure) -> {
                            if (failure == null) {
                              counts[0]++;
                              return Optional.<Result>empty();
                            }
                            LOG.error("Importing cell {} stopped", plan.cell().id(), failure);
                            return Optional.<Result>of(
                                new Result.Stopped(plan.cell().id(), reason(failure)));
                          });
                };
              },
              serverThread);
    }
    return chain.thenApply(
        stopped -> stopped.orElseGet(() -> new Result.Imported(counts[0], counts[1])));
  }

  /** Scans one cell and decides its owner. On the server thread. */
  CompletableFuture<Plan> plan(Gallery gallery, Cell cell) {
    Bounds bounds = cell.bounds().orElseThrow();
    return platform
        .imports()
        .scan(bounds.min(), bounds.max())
        .thenComposeAsync(
            scan -> {
              ImportedCell imported = imported(gallery, cell, scan);
              Owner owner = owner(gallery, cell, scan);
              if (!(owner instanceof Owner.Found found)) {
                return CompletableFuture.completedFuture(new Plan(cell, scan, imported, owner));
              }
              return writer
                  .submit(() -> archives.exists(found.id(), gallery.season()))
                  .thenApply(exists ->
                      new Plan(
                          cell,
                          scan,
                          imported,
                          exists
                              ? new Owner.Flagged(
                                  found.name()
                                      + " already has a Season "
                                      + gallery.season()
                                      + " record")
                              : owner));
            },
            serverThread);
  }

  /** What an import of the scanned cell takes, in page order. */
  static ImportedCell imported(Gallery gallery, Cell cell, Imports.Scan scan) {
    Optional<ImportedCell.Stand> stand =
        scan.stands().stream()
            .findFirst()
            .map(s -> new ImportedCell.Stand(s.at(), s.equipment(), s.properties()));
    List<ImportedCell.Container> containers =
        scan.containers().stream()
            .sorted(
                Comparator.comparingInt((Imports.Container c) -> c.block().blockY())
                    .thenComparingInt(c -> c.block().blockZ())
                    .thenComparingInt(c -> c.block().blockX()))
            .map(c -> new ImportedCell.Container(c.block(), c.type(), c.slots()))
            .toList();
    List<ImportedCell.Frame> frames =
        scan.frames().stream()
            .map(f -> new ImportedCell.Frame(f.at(), f.item(), f.properties()))
            .toList();
    return new ImportedCell(gallery.id(), cell.id(), stand, containers, frames, scan.signs());
  }

  /** The owner an admin set, else the one the stand's player head names. */
  Owner owner(Gallery gallery, Cell cell, Imports.Scan scan) {
    if (cell.imported()) {
      return new Owner.Done();
    }
    Optional<Skin> headSkin =
        scan.stands().stream()
            .flatMap(s -> s.heads().stream())
            .findFirst()
            .flatMap(Imports.Head::skin);
    Optional<PlayerId> assigned = cell.owner(gallery.season());
    if (assigned.isPresent()) {
      String name = knownName(assigned.get()).orElse(assigned.get().toString());
      return new Owner.Found(assigned.get(), name, headSkin, "set by an admin");
    }
    if (scan.stands().isEmpty()) {
      return new Owner.Flagged("the cell has no armour stand");
    }
    if (scan.stands().size() > 1) {
      return new Owner.Flagged("the cell has " + scan.stands().size() + " armour stands");
    }
    List<Imports.Head> heads = scan.stands().getFirst().heads();
    if (heads.isEmpty()) {
      return new Owner.Flagged("the stand has no player head");
    }
    if (heads.stream().map(Imports.Head::owner).distinct().count() > 1) {
      return new Owner.Flagged("the stand has player heads of more than one player");
    }
    Imports.Head head = heads.getFirst();
    if (head.owner().isEmpty()) {
      return new Owner.Flagged("the player head names no player");
    }
    PlayerId id = head.owner().get();
    Optional<String> known = knownName(id);
    String name = known.or(head::name).orElse(id.toString());
    if (known.isEmpty()) {
      return new Owner.Flagged(
          "the player head is " + name + "'s (" + id + "), who has never played here");
    }
    return new Owner.Found(id, name, head.skin(), "from the player head on the stand");
  }

  private Optional<String> knownName(PlayerId id) {
    return players.find(id).map(r -> r.lastName()).or(() -> platform.players().knownName(id));
  }

  /** Imports one cell whose owner is found. Completes once the figure is drawn. */
  private CompletableFuture<Void> importCell(
      Gallery gallery, Plan plan, Owner.Found owner, String actor) {
    int season = gallery.season();
    ArchiveRecord record = record(owner, season, plan.imported());
    int dataVersion = platform.inventories().dataVersion();
    String hash = record.contentHash();
    Optional<HallService.CellRef> ref = cellRef(plan.imported());
    if (ref.isEmpty()) {
      return CompletableFuture.failedFuture(new Stop("the cell no longer exists"));
    }
    // The gallery the import will save must keep the rules, or it wouldn't load again.
    List<GalleryRules.Problem> problems =
        GalleryRules.check(tagged(ref.get(), owner.id(), season, plan.imported()));
    if (!problems.isEmpty()) {
      return CompletableFuture.failedFuture(
          new Stop("the gallery would break its rules: " + problems.getFirst().message()));
    }
    return writer
        .submit(() -> {
          if (archives.exists(owner.id(), season)) {
            throw new Stop(owner.name() + " already has a Season " + season + " record");
          }
          archives.write(record, dataVersion);
          return Boolean.TRUE;
        })
        .thenCompose(
            written -> journal.append(JournalKind.IMPORT_STARTED, owner.id(), season, hash))
        .thenCompose(started -> {
          crash.reached(CrashPoint.IMPORT_STARTED, owner.id());
          return writer.submit(() -> archives.read(owner.id(), season));
        })
        .thenComposeAsync(
            read -> {
              String before = summary(plan.imported());
              String after =
                  read.flatMap(ArchiveRecord::imported).map(HallImporter::summary).orElse("none");
              boolean same =
                  read.flatMap(ArchiveRecord::imported)
                      .map(i -> ArchiveHash.ofImported(i).equals(hash))
                      .orElse(false);
              audit.record(
                  actor,
                  "import",
                  owner.name(),
                  same ? "ok" : "mismatch",
                  "cell "
                      + gallery.id()
                      + ":"
                      + plan.cell().id()
                      + "; before "
                      + before
                      + "; after "
                      + after);
              if (!same) {
                return setAside(owner.id(), season, "import-mismatch", hash)
                    .thenApply(done -> {
                      throw new Stop(
                          "the record read back doesn't match the cell ("
                              + after
                              + " for "
                              + before
                              + ")");
                    });
              }
              return finish(read.orElseThrow(), false)
                  .exceptionallyCompose(failure -> {
                    if (!(unwrap(failure) instanceof Unchanged changed)) {
                      return CompletableFuture.failedFuture(failure);
                    }
                    // Nothing in the cell was touched, so the record goes aside and the cell
                    // keeps every item.
                    audit.record(
                        actor,
                        "import",
                        owner.name(),
                        "stopped",
                        "cell "
                            + gallery.id()
                            + ":"
                            + plan.cell().id()
                            + "; "
                            + changed.getMessage());
                    return setAside(owner.id(), season, "import-changed", hash)
                        .thenApply(done -> {
                          throw changed;
                        });
                  });
            },
            serverThread);
  }

  /** Moves a record aside and closes its import in the journal. */
  private CompletableFuture<Void> setAside(PlayerId owner, int season, String suffix, String hash) {
    return writer
        .submit(() -> archives.moveAside(owner, season, suffix + "-" + clock.millis()))
        .thenCompose(aside -> journal.append(JournalKind.IMPORT_DONE, owner, season, hash))
        .thenApply(done -> null);
  }

  /** The record an imported cell becomes. */
  ArchiveRecord record(Owner.Found owner, int season, ImportedCell imported) {
    InventorySnapshot empty =
        new InventorySnapshot(List.of(), List.of(), Optional.empty(), List.of());
    Position at =
        imported
            .stand()
            .map(ImportedCell.Stand::at)
            .or(() -> imported.containers().stream().findFirst().map(ImportedCell.Container::block))
            .or(() -> cellRef(imported).flatMap(r -> r.cell().bounds()).map(Bounds::min))
            .orElseThrow(() -> new Stop("the cell has nothing to import"));
    return new ArchiveRecord(
        owner.id(),
        owner.name(),
        season,
        clock.instant(),
        empty,
        List.of(),
        EnderChestPolicy.KEEP,
        new AdvancementSnapshot(Map.of(), 0, 0),
        Experience.NONE,
        Map.of(),
        Optional.empty(),
        at,
        owner.skin(),
        ArchiveHash.ofImported(imported),
        OptionalInt.empty(),
        Optional.of(imported));
  }

  /**
   * Empties what the record took from the cell, tags the cell's anchors and owner, closes the
   * journal entry and draws the figure. On the server thread.
   *
   * @param recovering whether a crash left this import open: then sources already empty or gone
   *     were emptied before the crash and are skipped. A fresh import instead stops with {@link
   *     Unchanged}, changing nothing, if any source no longer holds exactly what the record does.
   */
  private CompletableFuture<Void> finish(ArchiveRecord record, boolean recovering) {
    ImportedCell imported = record.imported().orElseThrow();
    Optional<HallService.CellRef> ref = cellRef(imported);
    if (ref.isEmpty()) {
      return CompletableFuture.failedFuture(
          new Stop("cell " + imported.gallery() + ":" + imported.cell() + " no longer exists"));
    }
    Bounds bounds = ref.get().cell().bounds().orElseThrow();
    PlayerId owner = record.player();
    int season = record.season();
    return platform
        .imports()
        .scan(bounds.min(), bounds.max())
        .thenComposeAsync(
            live -> {
              clear(imported, live, recovering);
              crash.reached(CrashPoint.IMPORT_CLEARED, owner);
              platform.imports().saveWorld(bounds.dimension());
              tag(ref.get(), owner, season, imported);
              return journal.append(JournalKind.IMPORT_DONE, owner, season, record.contentHash());
            },
            serverThread)
        .thenComposeAsync(
            done ->
                renderer
                    .render(owner, season)
                    .exceptionally(e -> {
                      // The import is done; /hof reconcile draws the figure again.
                      LOG.error("Drawing the imported figure of {} failed", owner, e);
                      return null;
                    }),
            serverThread);
  }

  /**
   * Checks every source against the record, then empties them. Changes nothing if a source holds
   * something else or is gone, or, on a fresh import, if it was emptied since the scan: its items
   * may be in someone's hands by now, so the record mustn't hold them too.
   */
  private void clear(ImportedCell imported, Imports.Scan live, boolean recovering) {
    List<Step> steps = new ArrayList<>();
    for (ImportedCell.Container container : imported.containers()) {
      String what = "the " + type(container.type()) + " at " + place(container.block());
      Optional<Imports.Container> found =
          live.containers().stream()
              .filter(c -> sameBlock(c.block(), container.block()))
              .findFirst();
      if (found.isEmpty()) {
        throw changed(what + " is gone", recovering);
      }
      if (same(found.get().slots(), container.slots())) {
        if (!isEmpty(container.slots())) {
          steps.add(new Step(what, () -> platform.imports().emptyContainer(container.block())));
        }
      } else if (recovering && isEmpty(found.get().slots())) {
        // Emptied before the crash.
      } else if (!recovering && isEmpty(found.get().slots())) {
        throw new Unchanged(what + " was emptied after the scan");
      } else {
        throw changed(what + " no longer holds what the record does", recovering);
      }
    }
    if (imported.stand().isPresent()) {
      ImportedCell.Stand stand = imported.stand().get();
      String what = "the armour stand at " + place(stand.at());
      Optional<Imports.Stand> found =
          live.stands().stream().filter(s -> sameBlock(s.at(), stand.at())).findFirst();
      if (found.isEmpty()) {
        if (!recovering) {
          throw new Unchanged(what + " was removed after the scan");
        }
      } else if (!same(found.get().equipment(), stand.equipment())) {
        throw changed(what + " no longer wears what the record does", recovering);
      } else {
        UUID id = found.get().id();
        steps.add(new Step(what, () -> platform.imports().remove(id)));
      }
    }
    for (ImportedCell.Frame frame : imported.frames()) {
      String what = "the item frame at " + place(frame.at());
      Optional<Imports.Frame> found =
          live.frames().stream().filter(f -> sameBlock(f.at(), frame.at())).findFirst();
      if (found.isEmpty()) {
        if (!recovering) {
          throw new Unchanged(what + " was removed after the scan");
        }
      } else if (!same(List.of(found.get().item()), List.of(frame.item()))) {
        throw changed(what + " no longer holds what the record does", recovering);
      } else {
        UUID id = found.get().id();
        steps.add(new Step(what, () -> platform.imports().remove(id)));
      }
    }
    for (Step step : steps) {
      if (!step.run().getAsBoolean()) {
        // The journal entry stays open, so the next start finishes the cell.
        throw new Stop(
            step.what() + " couldn't be emptied; the import finishes at the next restart");
      }
    }
  }

  /**
   * A source that doesn't match the record. A fresh import has changed nothing yet, so it sets the
   * record aside; recovery stops and leaves the cell to an admin.
   */
  private static RuntimeException changed(String reason, boolean recovering) {
    return recovering ? new Stop(reason) : new Unchanged(reason);
  }

  /** One change {@link #clear} makes, and whether it worked. */
  private record Step(String what, BooleanSupplier run) {}

  /** Gives the cell its owner and the anchors the import found, and saves the gallery. */
  private void tag(HallService.CellRef ref, PlayerId owner, int season, ImportedCell imported) {
    var _ = hall.save(tagged(ref, owner, season, imported));
  }

  /** The gallery with the cell tagged as imported: its owner and the anchors the import found. */
  private Gallery tagged(
      HallService.CellRef ref, PlayerId owner, int season, ImportedCell imported) {
    Gallery gallery = hall.gallery(ref.gallery().id()).orElse(ref.gallery());
    Cell cell = gallery.cell(ref.cell().id()).orElse(ref.cell());
    for (AnchorType type : List.of(AnchorType.FIGURE, AnchorType.ARCHIVE, AnchorType.TROPHY)) {
      cell = cell.withoutAnchors(type);
    }
    if (imported.stand().isPresent()) {
      cell = cell.withAnchor(Anchor.of(AnchorType.FIGURE, block(imported.stand().get().at())));
    }
    List<Integer> pages = ArchivePages.containerPages(imported);
    for (int i = 0; i < imported.containers().size(); i++) {
      cell =
          cell.withAnchor(
              new Anchor(
                  AnchorType.ARCHIVE,
                  block(imported.containers().get(i).block()),
                  OptionalInt.of(pages.get(i)),
                  OptionalInt.empty()));
    }
    for (ImportedCell.Frame frame : imported.frames()) {
      cell = cell.withAnchor(Anchor.of(AnchorType.TROPHY, block(frame.at())));
    }
    cell = cell.withOwner(season, Optional.of(owner)).withImported(true);
    return gallery.withCell(cell);
  }

  private Optional<HallService.CellRef> cellRef(ImportedCell imported) {
    return hall.gallery(imported.gallery())
        .flatMap(g ->
            g.cell(imported.cell())
                .filter(c -> c.bounds().isPresent())
                .map(c -> new HallService.CellRef(g, c)));
  }

  /** Finishes imports a crash left open, once the server is ticking. */
  public RecoveryHandler recovery() {
    return this::recover;
  }

  private CompletableFuture<RecoveryOutcome> recover(OpenOperation operation) {
    if (operation.player().isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("the journal names no player"));
    }
    PlayerId owner = operation.player().get();
    int season = operation.season();
    CompletableFuture<Void> later = new CompletableFuture<>();
    platform.scheduler().runGlobalLater(RECOVERY_DELAY, () -> later.complete(null));
    return later
        .thenCompose(ready -> writer.submit(() -> archives.read(owner, season)))
        .thenComposeAsync(
            read -> {
              if (read.isEmpty() || read.get().imported().isEmpty()) {
                return CompletableFuture.completedFuture(
                    RecoveryOutcome.failed("the import's record isn't there"));
              }
              return finish(read.get(), true).thenApply(done -> RecoveryOutcome.finished());
            },
            serverThread)
        .exceptionally(e -> {
          LOG.error("Recovering the import for {} failed", owner, e);
          return RecoveryOutcome.failed(reason(e));
        });
  }

  /** An interrupted undo is left to an admin: the cell may hold part of what the record does. */
  public RecoveryHandler undoRecovery() {
    return operation ->
        CompletableFuture.completedFuture(
            RecoveryOutcome.failed(
                "an import undo was interrupted; compare the cell with its record and run /hof"
                    + " import undo again"));
  }

  // Undo

  /** Puts back every item of a gallery's imported cells (or one of them). */
  public CompletableFuture<Result> undo(Gallery gallery, Optional<String> only, String actor) {
    if (!busy.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(new Result.Busy());
    }
    List<Cell> cells =
        gallery.cells().stream()
            .filter(c -> only.map(c.id()::equals).orElse(true))
            .filter(Cell::imported)
            .toList();
    int[] undone = new int[1];
    CompletableFuture<Optional<Result>> chain = CompletableFuture.completedFuture(Optional.empty());
    for (Cell cell : cells) {
      chain =
          chain.thenComposeAsync(
              stopped ->
                  stopped.isPresent()
                      ? CompletableFuture.completedFuture(stopped)
                      : undoCell(gallery, cell, actor)
                          .handle((ok, failure) -> {
                            if (failure == null) {
                              undone[0]++;
                              return Optional.<Result>empty();
                            }
                            LOG.error("Undoing the import of {} stopped", cell.id(), failure);
                            return Optional.<Result>of(
                                new Result.UndoStopped(cell.id(), reason(failure)));
                          }),
              serverThread);
    }
    return chain
        .thenApply(stopped -> stopped.orElseGet(() -> new Result.Undone(undone[0])))
        .whenComplete((result, failure) -> busy.set(false));
  }

  private CompletableFuture<Void> undoCell(Gallery gallery, Cell cell, String actor) {
    int season = gallery.season();
    Optional<PlayerId> owner = cell.owner(season);
    if (owner.isEmpty()) {
      return CompletableFuture.failedFuture(new Stop("it has no owner"));
    }
    Bounds bounds = cell.bounds().orElseThrow();
    return writer
        .submit(() -> archives.read(owner.get(), season))
        .thenCompose(read -> {
          if (read.isEmpty() || read.get().imported().isEmpty()) {
            throw new Stop("its owner has no imported record");
          }
          return journal
              .append(
                  JournalKind.IMPORT_UNDO_STARTED, owner.get(), season, read.get().contentHash())
              .thenApply(started -> read.get());
        })
        .thenComposeAsync(
            record ->
                platform
                    .imports()
                    .scan(bounds.min(), bounds.max())
                    .thenApplyAsync(
                        live -> {
                          ArchiveRecord putBack = putBack(gallery, cell, record, live);
                          // On disk before the record goes aside, so no item is ever in neither.
                          platform.imports().saveWorld(bounds.dimension());
                          return putBack;
                        },
                        serverThread),
            serverThread)
        .thenCompose(record ->
            writer
                .submit(() ->
                    archives.moveAside(owner.get(), season, "import-undone-" + clock.millis()))
                .thenApply(aside -> record))
        .thenComposeAsync(
            record -> {
              Gallery current = hall.gallery(gallery.id()).orElse(gallery);
              Cell next = current.cell(cell.id()).orElse(cell);
              for (AnchorType type :
                  List.of(AnchorType.FIGURE, AnchorType.ARCHIVE, AnchorType.TROPHY)) {
                next = next.withoutAnchors(type);
              }
              next = next.withOwner(season, Optional.empty()).withImported(false);
              var _ = hall.save(current.withCell(next));
              audit.record(
                  actor,
                  "import-undo",
                  record.name(),
                  "ok",
                  "cell "
                      + gallery.id()
                      + ":"
                      + cell.id()
                      + "; "
                      + summary(record.imported().orElseThrow()));
              return journal.append(
                  JournalKind.IMPORT_UNDO_DONE, owner.get(), season, record.contentHash());
            },
            serverThread)
        .thenApply(done -> null);
  }

  /** Removes the cell's displays and puts each item back where the record says it was. */
  private ArchiveRecord putBack(
      Gallery gallery, Cell cell, ArchiveRecord record, Imports.Scan live) {
    ImportedCell imported = record.imported().orElseThrow();
    for (ImportedCell.Container container : imported.containers()) {
      Optional<Imports.Container> found =
          live.containers().stream()
              .filter(c -> sameBlock(c.block(), container.block()))
              .findFirst();
      if (found.isEmpty()) {
        throw new Stop(
            "the " + type(container.type()) + " at " + place(container.block()) + " is gone");
      }
      if (!isEmpty(found.get().slots()) && !same(found.get().slots(), container.slots())) {
        throw new Stop(
            "the " + type(container.type()) + " at " + place(container.block()) + " isn't empty");
      }
    }
    Bounds bounds = cell.bounds().orElseThrow();
    platform
        .displays()
        .removeTagged(
            bounds.min(),
            bounds.max(),
            DisplayTag.prefix(gallery.id(), cell.id(), gallery.season()));
    for (ImportedCell.Container container : imported.containers()) {
      boolean holds =
          live.containers().stream()
              .anyMatch(c ->
                  sameBlock(c.block(), container.block()) && same(c.slots(), container.slots()));
      if (!holds && !platform.imports().fillContainer(container.block(), container.slots())) {
        throw new Stop(
            "the "
                + type(container.type())
                + " at "
                + place(container.block())
                + " wouldn't take its items");
      }
    }
    imported
        .stand()
        .filter(stand -> live.stands().stream().noneMatch(s -> sameBlock(s.at(), stand.at())))
        .ifPresent(stand ->
            platform.imports().spawnStand(stand.at(), stand.properties(), stand.equipment()));
    for (ImportedCell.Frame frame : imported.frames()) {
      if (live.frames().stream().noneMatch(f -> sameBlock(f.at(), frame.at()))) {
        platform.imports().spawnFrame(frame.at(), frame.properties(), frame.item());
      }
    }
    return record;
  }

  // The report

  private CompletableFuture<String> report(Gallery gallery, List<Plan> plans, boolean dryRun) {
    StringBuilder text = new StringBuilder();
    text.append(dryRun ? "Dry run of the import of " : "Import of ")
        .append(gallery.id())
        .append(" (Season ")
        .append(gallery.season())
        .append("), ")
        .append(clock.instant())
        .append('\n');
    List<Plan> sorted =
        plans.stream().sorted(Comparator.comparing((Plan p) -> p.cell().id())).toList();
    for (Plan plan : sorted) {
      text.append('\n');
      describe(text, plan);
    }
    String name = reportName(gallery.id());
    Path file = reports.resolve(name);
    byte[] bytes = text.toString().getBytes(UTF_8);
    return writer.submit(() -> {
      AtomicFiles.write(file, bytes);
      return name;
    });
  }

  private static void describe(StringBuilder text, Plan plan) {
    text.append("Cell ").append(plan.cell().id()).append(": ");
    switch (plan.owner()) {
      case Owner.Found found ->
          text.append("ready\n")
              .append("  owner: ")
              .append(found.name())
              .append(" (")
              .append(found.id())
              .append("), ")
              .append(found.how())
              .append('\n');
      case Owner.Flagged flagged -> text.append("flagged: ").append(flagged.reason()).append('\n');
      case Owner.Done done -> text.append("already imported\n");
    }
    ImportedCell imported = plan.imported();
    imported
        .stand()
        .ifPresent(stand -> {
          text.append("  armour stand at ").append(place(stand.at())).append(':');
          for (int i = 0; i < stand.equipment().size(); i++) {
            int slot = i;
            stand
                .equipment()
                .get(i)
                .ifPresent(item ->
                    text.append(' ')
                        .append(ImportedCell.EQUIPMENT.get(slot))
                        .append(' ')
                        .append(item(item))
                        .append(';'));
          }
          text.append('\n');
        });
    for (ImportedCell.Container container : imported.containers()) {
      List<NeutralItem> items = container.slots().stream().flatMap(Optional::stream).toList();
      text.append("  ")
          .append(type(container.type()))
          .append(" at ")
          .append(place(container.block()))
          .append(": ")
          .append(items.size())
          .append(" stacks, ")
          .append(items.stream().mapToInt(HallImporter::count).sum())
          .append(" items\n");
    }
    for (ImportedCell.Frame frame : imported.frames()) {
      text.append("  item frame at ")
          .append(place(frame.at()))
          .append(": ")
          .append(frame.item().map(HallImporter::item).orElse("empty"))
          .append('\n');
    }
    for (String sign : imported.signs()) {
      text.append("  sign: ").append(sign.replace('\n', ' ')).append('\n');
    }
    for (Imports.Other other : plan.scan().others()) {
      text.append("  left in place: ")
          .append(type(other.type()))
          .append(" at ")
          .append(place(other.block()))
          .append(" with ")
          .append(other.items())
          .append(" items\n");
    }
  }

  // Helpers

  private static String summary(ImportedCell imported) {
    List<NeutralItem> items = imported.items();
    return items.size()
        + " stacks, "
        + items.stream().mapToInt(HallImporter::count).sum()
        + " items, hash "
        + ArchiveHash.ofImported(imported);
  }

  private static String item(NeutralItem item) {
    return count(item) + " × " + id(item);
  }

  private static String id(NeutralItem item) {
    try {
      return String.valueOf(Nbt.read(item.nbt()).get("id"));
    } catch (IOException e) {
      return "an unreadable item";
    }
  }

  private static int count(NeutralItem item) {
    try {
      return Nbt.read(item.nbt()).get("count") instanceof Number n ? n.intValue() : 1;
    } catch (IOException e) {
      return 1;
    }
  }

  private static String type(String id) {
    String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    return bare.replace('_', ' ');
  }

  private static String place(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
  }

  private static Position block(Position at) {
    return new Position(at.dimension(), at.blockX(), at.blockY(), at.blockZ(), at.yaw(), 0f);
  }

  private static boolean sameBlock(Position a, Position b) {
    return a.dimension().equals(b.dimension())
        && a.blockX() == b.blockX()
        && a.blockY() == b.blockY()
        && a.blockZ() == b.blockZ();
  }

  private static boolean isEmpty(List<Optional<NeutralItem>> slots) {
    return slots.stream().allMatch(Optional::isEmpty);
  }

  /** Whether two lists of slots hold the same items, comparing the item data itself. */
  static boolean same(List<Optional<NeutralItem>> a, List<Optional<NeutralItem>> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      Optional<NeutralItem> x = a.get(i);
      Optional<NeutralItem> y = b.get(i);
      if (x.isPresent() != y.isPresent()) {
        return false;
      }
      if (x.isPresent() && !Arrays.equals(canonical(x.get()), canonical(y.orElseThrow()))) {
        return false;
      }
    }
    return true;
  }

  private static byte[] canonical(NeutralItem item) {
    try {
      return Nbt.canonical(Nbt.read(item.nbt()));
    } catch (IOException e) {
      return item.nbt();
    }
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable cause = failure;
    while (cause instanceof CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static String reason(Throwable failure) {
    Throwable cause = unwrap(failure);
    return cause instanceof Stop || cause instanceof Unchanged
        ? String.valueOf(cause.getMessage())
        : String.valueOf(cause);
  }
}
