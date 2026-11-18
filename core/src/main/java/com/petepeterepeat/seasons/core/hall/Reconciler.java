// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciliation: each cell's displays compared with its record and put right. A missing display is
 * drawn, one nobody expects is removed, and a figure whose gear was changed is drawn again; each
 * repair is audited. Cells are queued when their chunk's entities load and every {@link #EVERY}
 * while loaded, and worked through on the server thread within about {@link #BUDGET_NANOS} a tick.
 * Only {@code /hof reconcile} loads chunks. Records are read on the writer thread.
 */
public final class Reconciler {
  /** How often every loaded cell is checked. */
  public static final Duration EVERY = Duration.ofMinutes(5);

  /** The work a tick may take, in nanoseconds. */
  static final long BUDGET_NANOS = 1_000_000;

  private static final Logger LOG = LoggerFactory.getLogger(Reconciler.class);
  private static final Duration TICK = Duration.ofMillis(50);

  /** How far a display may stand from where it was drawn and still count as that display. */
  private static final double NEAR = 0.25;

  /** Below and above any world's build height, for whole chunk columns. */
  private static final int MIN_Y = -2048;

  private static final int MAX_Y = 2047;

  private final Platform platform;
  private final HallService halls;
  private final HallRenderer renderer;
  private final ArchiveFiles archives;
  private final SerialWriter writer;
  private final AuditLog audit;
  private final LongSupplier nanoTime;
  private final Executor serverThread;

  /** Cell keys queued, waiting for their record or to be compared. */
  private final Set<String> queued = new HashSet<>();

  private final ArrayDeque<String> waiting = new ArrayDeque<>();
  private final ArrayDeque<Read> ready = new ArrayDeque<>();
  private final List<ScheduledTask> tasks = new ArrayList<>();

  /**
   * A cell's record, read.
   *
   * @param record empty when the cell has no owner or the owner no record
   */
  private record Read(String key, Optional<ArchiveRecord> record) {}

  /**
   * @param nanoTime a monotonic clock in nanoseconds, for the budget
   */
  public Reconciler(
      Platform platform,
      HallService halls,
      HallRenderer renderer,
      ArchiveFiles archives,
      SerialWriter writer,
      AuditLog audit,
      LongSupplier nanoTime) {
    this.platform = platform;
    this.halls = halls;
    this.renderer = renderer;
    this.archives = archives;
    this.writer = writer;
    this.audit = audit;
    this.nanoTime = nanoTime;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  public void start() {
    tasks.add(platform.scheduler().runGlobalRepeating(TICK, this::work));
    tasks.add(platform.scheduler().runGlobalRepeating(EVERY, this::sweep));
  }

  public void stop() {
    tasks.forEach(ScheduledTask::cancel);
    tasks.clear();
  }

  /**
   * A chunk's entities loaded: queues every cell that touches it, and next tick removes any display
   * there that belongs to a cell somewhere else, such as a figure pushed out of the Hall.
   */
  public void chunkLoaded(String dimension, int chunkX, int chunkZ) {
    platform.scheduler().runGlobal(() -> strays(dimension, chunkX, chunkZ));
    for (Gallery gallery : halls.galleries()) {
      if (!gallery.dimension().equals(dimension)
          || gallery.bounds().isEmpty()
          || !touches(gallery.bounds().get(), chunkX, chunkZ)) {
        continue;
      }
      for (Cell cell : gallery.cells()) {
        if (cell.bounds().isPresent() && touches(cell.bounds().get(), chunkX, chunkZ)) {
          enqueue(new HallService.CellRef(gallery, cell).key());
        }
      }
    }
  }

  /** Queues every cell whose chunks are all loaded. */
  void sweep() {
    for (Gallery gallery : halls.galleries()) {
      for (Cell cell : gallery.cells()) {
        if (cell.bounds().isPresent() && loaded(cell.bounds().get())) {
          enqueue(new HallService.CellRef(gallery, cell).key());
        }
      }
    }
  }

  private void enqueue(String key) {
    if (queued.add(key)) {
      waiting.add(key);
    }
  }

  /** One tick's work: read records and compare cells until the budget is spent. */
  void work() {
    long start = nanoTime.getAsLong();
    while ((!ready.isEmpty() || !waiting.isEmpty())
        && nanoTime.getAsLong() - start < BUDGET_NANOS) {
      Read read = ready.poll();
      if (read != null) {
        queued.remove(read.key());
        Optional<HallService.CellRef> ref = find(read.key());
        if (ref.isPresent()
            && ref.get().cell().bounds().isPresent()
            && loaded(ref.get().cell().bounds().get())) {
          reconcile(ref.get(), read.record());
        }
        continue;
      }
      String key = waiting.poll();
      if (key != null) {
        startReading(key);
      }
    }
  }

  private void startReading(String key) {
    Optional<HallService.CellRef> ref = find(key);
    if (ref.isEmpty()) {
      queued.remove(key);
      return;
    }
    var _ =
        record(ref.get())
            .whenCompleteAsync(
                (record, failure) -> {
                  if (failure != null) {
                    // Without its record nothing in the cell can be judged, so nothing is touched.
                    LOG.warn("Can't read the record for cell {}; not reconciled", key, failure);
                    queued.remove(key);
                  } else {
                    ready.add(new Read(key, record));
                  }
                },
                serverThread);
  }

  /**
   * {@code /hof reconcile}: loads every cell's chunks, then reconciles it. Completes with repairs.
   */
  public CompletableFuture<Integer> reconcile(Gallery gallery) {
    List<CompletableFuture<Integer>> cells = new ArrayList<>();
    for (Cell cell : gallery.cells()) {
      Optional<Bounds> bounds = cell.bounds();
      if (bounds.isEmpty()) {
        continue;
      }
      HallService.CellRef ref = new HallService.CellRef(gallery, cell);
      cells.add(
          renderer
              .loaded(bounds.get())
              .thenCompose(loaded -> record(ref))
              .thenApplyAsync(record -> reconcile(ref, record), serverThread));
    }
    return CompletableFuture.allOf(cells.toArray(CompletableFuture[]::new))
        .thenApply(done -> cells.stream().mapToInt(CompletableFuture::join).sum());
  }

  private CompletableFuture<Optional<ArchiveRecord>> record(HallService.CellRef ref) {
    int season = ref.gallery().season();
    Optional<PlayerId> owner = ref.cell().owner(season);
    if (owner.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return writer.submit(() -> archives.read(owner.get(), season));
  }

  /**
   * Compares a cell's displays with what its record draws and repairs the difference. Its chunks
   * are loaded.
   *
   * @return how many repairs it made
   */
  int reconcile(HallService.CellRef ref, Optional<ArchiveRecord> record) {
    Bounds bounds = ref.cell().bounds().orElseThrow();
    Displays displays = platform.displays();
    // Every display in the cell, and every one of the cell's own anywhere in its chunks, so one
    // moved out of the cell is found and removed rather than left beside the one drawn again.
    List<Displays.Shown> shown = new ArrayList<>(displays.tagged(bounds.min(), bounds.max()));
    Set<UUID> seen = new HashSet<>();
    shown.forEach(display -> seen.add(display.id()));
    for (Displays.Shown display : displays.tagged(columnsMin(bounds), columnsMax(bounds))) {
      if (belongsTo(display, ref) && seen.add(display.id())) {
        shown.add(display);
      }
    }
    List<HallRenderer.Planned> expected = record.map(r -> renderer.plan(ref, r)).orElse(List.of());
    int repairs = 0;
    for (HallRenderer.Planned planned : expected) {
      Displays.Shown match = take(shown, planned);
      if (match == null) {
        renderer.spawn(planned);
        repaired(ref, "drew the missing " + kind(planned.tag()));
        repairs++;
      } else if (planned instanceof HallRenderer.Planned.Shape shape
          && !displays.wears(match.id(), shape.figure().equipment())) {
        displays.remove(match.id());
        renderer.spawn(planned);
        repaired(ref, "fixed the figure's gear");
        repairs++;
      }
    }
    for (Displays.Shown stray : shown) {
      displays.remove(stray.id());
      repaired(ref, "removed a stray display " + stray.tag());
      repairs++;
    }
    return repairs;
  }

  /** Takes the display that stands for a planned one out of the list, if one does. */
  private static Displays.@Nullable Shown take(
      List<Displays.Shown> shown, HallRenderer.Planned planned) {
    for (int i = 0; i < shown.size(); i++) {
      Displays.Shown candidate = shown.get(i);
      if (candidate.tag().equals(planned.tag()) && near(candidate.at(), planned.at())) {
        return shown.remove(i);
      }
    }
    return null;
  }

  private void repaired(HallService.CellRef ref, String what) {
    LOG.info("Reconciled cell {}: {}", ref.key(), what);
    audit.record(AuditLog.SEASONS, "reconcile", ref.key(), "repaired", what);
  }

  private static String kind(String tag) {
    return DisplayTag.parse(tag).map(DisplayTag::kind).orElse("display");
  }

  private static boolean near(Position a, Position b) {
    return a.dimension().equals(b.dimension())
        && Math.abs(a.x() - b.x()) <= NEAR
        && Math.abs(a.y() - b.y()) <= NEAR
        && Math.abs(a.z() - b.z()) <= NEAR;
  }

  private Optional<HallService.CellRef> find(String key) {
    for (Gallery gallery : halls.galleries()) {
      for (Cell cell : gallery.cells()) {
        HallService.CellRef ref = new HallService.CellRef(gallery, cell);
        if (ref.key().equals(key)) {
          return Optional.of(ref);
        }
      }
    }
    return Optional.empty();
  }

  /** Removes the displays in a loaded chunk whose cell's chunks are elsewhere. */
  private void strays(String dimension, int chunkX, int chunkZ) {
    if (!platform.world().isLoaded(Position.of(dimension, chunkX * 16.0, 0, chunkZ * 16.0))) {
      return;
    }
    Position min = Position.of(dimension, chunkX * 16.0, MIN_Y, chunkZ * 16.0);
    Position max = Position.of(dimension, chunkX * 16.0 + 15, MAX_Y, chunkZ * 16.0 + 15);
    for (Displays.Shown display : platform.displays().tagged(min, max)) {
      Optional<HallService.CellRef> ref =
          DisplayTag.parse(display.tag()).flatMap(tag -> find(tag.gallery() + ":" + tag.cell()));
      // A display of a cell that isn't known (a gallery file left out) is kept until it is.
      if (ref.isEmpty()) {
        continue;
      }
      Optional<Bounds> bounds = ref.get().cell().bounds();
      boolean home =
          bounds.isPresent()
              && ref.get().gallery().dimension().equals(dimension)
              && touches(bounds.get(), chunkX, chunkZ);
      if (!home) {
        platform.displays().remove(display.id());
        repaired(ref.get(), "removed a stray display " + display.tag());
      }
    }
  }

  private static boolean belongsTo(Displays.Shown display, HallService.CellRef ref) {
    return DisplayTag.parse(display.tag())
        .filter(
            tag -> tag.gallery().equals(ref.gallery().id()) && tag.cell().equals(ref.cell().id()))
        .isPresent();
  }

  /** The lowest corner of the whole chunk columns a box touches. */
  private static Position columnsMin(Bounds bounds) {
    return Position.of(
        bounds.dimension(), (bounds.minX() >> 4) * 16.0, MIN_Y, (bounds.minZ() >> 4) * 16.0);
  }

  /** The highest corner of the whole chunk columns a box touches. */
  private static Position columnsMax(Bounds bounds) {
    return Position.of(
        bounds.dimension(),
        (bounds.maxX() >> 4) * 16.0 + 15,
        MAX_Y,
        (bounds.maxZ() >> 4) * 16.0 + 15);
  }

  /** Whether every chunk a box touches is loaded. */
  private boolean loaded(Bounds bounds) {
    for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
      for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
        if (!platform
            .world()
            .isLoaded(Position.of(bounds.dimension(), x * 16.0, bounds.minY(), z * 16.0))) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean touches(Bounds bounds, int chunkX, int chunkZ) {
    return bounds.minX() >> 4 <= chunkX
        && chunkX <= bounds.maxX() >> 4
        && bounds.minZ() >> 4 <= chunkZ
        && chunkZ <= bounds.maxZ() >> 4;
  }
}
