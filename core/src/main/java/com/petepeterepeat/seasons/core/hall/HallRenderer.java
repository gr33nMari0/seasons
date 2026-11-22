// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ImportedCell;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.Figure;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.Awards;
import com.petepeterepeat.seasons.core.season.SeasonAwards;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Draws a player's displays in their cell from their archive: the figure in their skin and gear at
 * the figure anchor, the plaque, and an imported cell's trophies. Every redraw first removes what
 * the last one drew, so drawing twice leaves one set. Only copies of items are ever shown. Cells
 * are drawn a few displays a tick, within {@link #DRAW_BUDGET_NANOS}, so a rollover causes no lag
 * spike.
 */
public final class HallRenderer implements DisplayRenderer {
  /** How high above the plaque anchor's block its text floats. */
  private static final double PLAQUE_HEIGHT = 1.2;

  /** The drawing a tick may take, so a rollover draws a few cells a tick (A43). */
  static final long DRAW_BUDGET_NANOS = 2_000_000;

  private final Platform platform;
  private final ConfigHolder config;
  private final SerialWriter writer;
  private final ArchiveFiles archives;
  private final HallService hall;
  private final Executor serverThread;
  private final DrawQueue drawing;

  /** Cells with a draw under way, by key, with how many; server thread only. */
  private final java.util.Map<String, Integer> pending = new java.util.HashMap<>();

  public HallRenderer(
      Platform platform,
      ConfigHolder config,
      SerialWriter writer,
      ArchiveFiles archives,
      HallService hall) {
    this.platform = platform;
    this.config = config;
    this.writer = writer;
    this.archives = archives;
    this.hall = hall;
    this.serverThread = platform.scheduler()::runGlobal;
    this.drawing =
        new DrawQueue(platform.scheduler()::runGlobal, System::nanoTime, DRAW_BUDGET_NANOS);
  }

  @Override
  public CompletableFuture<Void> render(PlayerId player, int season) {
    return writer
        .submit(() -> archives.read(player, season))
        .thenComposeAsync(
            found -> {
              if (found.isEmpty()) {
                return CompletableFuture.completedFuture(null);
              }
              Optional<HallService.CellRef> ref = hall.ensureCell(player, season);
              if (ref.isEmpty() || ref.get().cell().bounds().isEmpty()) {
                return CompletableFuture.completedFuture(null);
              }
              return loaded(ref.get().cell().bounds().get())
                  .thenComposeAsync(_ -> queue(ref.get(), found.get()), serverThread);
            },
            serverThread);
  }

  /**
   * Queues a cell's drawing, noted as under way from its first step, the clearing, until its last
   * display is drawn. On the server thread.
   */
  private CompletableFuture<Void> queue(HallService.CellRef ref, ArchiveRecord archive) {
    String key = ref.key();
    return drawing
        .submitSteps(() -> {
          pending.merge(key, 1, Integer::sum);
          return draw(ref, archive);
        })
        .whenComplete(
            (done, failure) -> pending.computeIfPresent(key, (k, n) -> n > 1 ? n - 1 : null));
  }

  /**
   * Whether a cell's drawing is under way, its displays half drawn, so reconciliation leaves it to
   * the draw. A draw still waiting its turn will clear the cell first, whatever is there by then.
   */
  boolean drawing(HallService.CellRef ref) {
    return pending.containsKey(ref.key());
  }

  @Override
  public void remove(PlayerId player, int season) {
    hall.cellOf(player, season).ifPresent(this::clear);
  }

  /** Removes everything drawn in a cell for its gallery's season. */
  public void clear(HallService.CellRef ref) {
    Optional<Bounds> bounds = ref.cell().bounds();
    if (bounds.isEmpty()) {
      return;
    }
    String prefix = DisplayTag.prefix(ref.gallery().id(), ref.cell().id(), ref.gallery().season());
    var _ =
        loaded(bounds.get())
            .thenRunAsync(
                () ->
                    platform
                        .displays()
                        .removeTagged(bounds.get().min(), bounds.get().max(), prefix),
                serverThread);
  }

  /**
   * Clears a cell and plans its displays; its chunks are loaded. Returns the steps that spawn them,
   * one display each, which the draw queue spreads over ticks.
   */
  List<Runnable> draw(HallService.CellRef queued, ArchiveRecord archive) {
    // The cell as it is now: its gallery may have changed while the draw waited its turn.
    HallService.CellRef ref =
        hall.gallery(queued.gallery().id())
            .flatMap(g -> g.cell(queued.cell().id()).map(c -> new HallService.CellRef(g, c)))
            .orElse(queued);
    if (ref.cell().bounds().isEmpty()) {
      return List.of();
    }
    Bounds bounds = ref.cell().bounds().get();
    platform
        .displays()
        .removeTagged(
            bounds.min(),
            bounds.max(),
            DisplayTag.prefix(ref.gallery().id(), ref.cell().id(), archive.season()));
    List<Planned> planned = plan(ref, archive);
    if (planned.isEmpty()) {
      return List.of();
    }
    // The first display, the figure, goes up in the same step as the clearing, so a cell being
    // drawn again never stands empty.
    spawn(planned.getFirst());
    return planned.subList(1, planned.size()).stream()
        .<Runnable>map(next -> () -> spawn(next))
        .toList();
  }

  /** One display a cell shows for an archive. */
  sealed interface Planned {
    String tag();

    Position at();

    record Text(Position at, Message text, String tag, double scale) implements Planned {
      public Text(Position at, Message text, String tag) {
        this(at, text, tag, 1.0);
      }
    }

    record Shape(Figure figure) implements Planned {
      @Override
      public String tag() {
        return figure.tag();
      }

      @Override
      public Position at() {
        return figure.at();
      }
    }

    record Item(Position at, NeutralItem item, String tag) implements Planned {}
  }

  /** The displays a cell shows for an archive: its figure, plaque and an import's trophies. */
  List<Planned> plan(HallService.CellRef ref, ArchiveRecord archive) {
    String gallery = ref.gallery().id();
    String cell = ref.cell().id();
    int season = archive.season();
    Messages messages = config.get().messages();
    String seasonText = Integer.toString(season);
    List<Planned> planned = new ArrayList<>();
    boolean stand =
        ref.gallery().renderer() == FigureRenderer.ARMOUR_STAND
            || !platform.capabilities().has(Capability.MANNEQUINS);
    List<Optional<NeutralItem>> equipment = new ArrayList<>(archive.figureEquipment());
    // An armour stand wears the player's head; its helmet goes on a trophy anchor (questions 170).
    Optional<NeutralItem> helmet = Optional.empty();
    if (stand && !equipment.isEmpty()) {
      helmet = equipment.getFirst();
      equipment.set(0, Optional.empty());
    }
    ref.cell()
        .anchor(AnchorType.FIGURE)
        .ifPresent(anchor ->
            planned.add(
                new Planned.Shape(
                    new Figure(
                        HallService.standOn(anchor.at()),
                        messages.get(
                            "hof.figure-name", "name", archive.name(), "season", seasonText),
                        archive.skin(),
                        equipment,
                        new DisplayTag(gallery, cell, season, DisplayTag.FIGURE).toString(),
                        stand ? Figure.Kind.ARMOUR_STAND : Figure.Kind.MANNEQUIN,
                        stand ? Optional.empty() : ref.cell().pose()))));
    List<Position> framed =
        archive
            .imported()
            .map(i -> i.frames().stream().map(ImportedCell.Frame::at).toList())
            .orElse(List.of());
    // Trophy anchors an import's frames don't hold: the helmet first, then the awards.
    List<Anchor> free =
        new ArrayList<>(
            ref.cell().anchors(AnchorType.TROPHY).stream()
                .filter(trophy -> framed.stream().noneMatch(trophy::isAt))
                .toList());
    if (helmet.isPresent() && !free.isEmpty()) {
      planned.add(
          new Planned.Item(
              above(free.removeFirst().at(), 0.5),
              helmet.get(),
              new DisplayTag(gallery, cell, season, DisplayTag.TROPHY).toString()));
    }
    List<SeasonAwards.Award> won =
        hall.seasonState()
            .flatMap(s -> s.awards(season))
            .map(a -> a.wonBy(archive.player()))
            .orElse(List.of());
    // One trophy per award, while free anchors last (questions 202).
    for (int i = 0; i < Math.min(won.size(), free.size()); i++) {
      Optional<NeutralItem> trophy = trophyItem(won.get(i).id());
      if (trophy.isPresent()) {
        planned.add(
            new Planned.Item(
                above(free.get(i).at(), 0.5),
                trophy.get(),
                new DisplayTag(gallery, cell, season, DisplayTag.TROPHY).toString()));
      }
    }
    ref.cell()
        .anchor(AnchorType.PLAQUE)
        .ifPresent(anchor ->
            planned.add(
                new Planned.Text(
                    above(anchor.at(), PLAQUE_HEIGHT),
                    plaque(messages, archive, won),
                    new DisplayTag(gallery, cell, season, DisplayTag.PLAQUE).toString(),
                    ref.gallery().plaqueScale(anchor))));
    if (archive.imported().isPresent()) {
      List<ImportedCell.Frame> frames = new ArrayList<>(archive.imported().get().frames());
      for (Anchor trophy : ref.cell().anchors(AnchorType.TROPHY)) {
        frames.stream()
            .filter(frame -> trophy.isAt(frame.at()))
            .findFirst()
            .flatMap(ImportedCell.Frame::item)
            .ifPresent(item ->
                planned.add(
                    new Planned.Item(
                        above(trophy.at(), 0.5),
                        item,
                        new DisplayTag(gallery, cell, season, DisplayTag.TROPHY).toString())));
      }
    }
    return planned;
  }

  /** Spawns one planned display. */
  UUID spawn(Planned planned) {
    return switch (planned) {
      case Planned.Shape shape -> platform.displays().spawnFigure(shape.figure());
      case Planned.Text text ->
          platform.displays().spawnText(text.at(), text.text(), text.tag(), text.scale());
      case Planned.Item item -> platform.displays().spawnItem(item.at(), item.item(), item.tag());
    };
  }

  /** An award's trophy: the item config.yml names for it now. */
  private Optional<NeutralItem> trophyItem(String award) {
    return Optional.ofNullable(config.get().config().awards().get(award))
        .flatMap(a -> Optional.ofNullable(a.item()))
        .flatMap(id -> platform.world().item(id.indexOf(':') < 0 ? "minecraft:" + id : id));
  }

  private static Message plaque(
      Messages messages, ArchiveRecord archive, List<SeasonAwards.Award> won) {
    Message plaque = plaque(messages, archive);
    if (won.isEmpty()) {
      return plaque;
    }
    String names =
        String.join(
            messages.text("hof.award-separator"),
            won.stream().map(a -> Awards.name(messages, a.id())).toList());
    return Message.lines(List.of(plaque, messages.get("hof.plaque-awards", "awards", names)));
  }

  private static Message plaque(Messages messages, ArchiveRecord archive) {
    String season = Integer.toString(archive.season());
    if (archive.imported().isPresent()) {
      return messages.get("hof.plaque-imported", "name", archive.name(), "season", season);
    }
    return messages.get(
        "hof.plaque",
        "name",
        archive.name(),
        "season",
        season,
        "completed",
        Integer.toString(archive.advancements().completed()),
        "visible",
        Integer.toString(archive.advancements().visible()));
  }

  private static Position above(Position block, double height) {
    return new Position(
        block.dimension(),
        block.blockX() + 0.5,
        block.blockY() + height,
        block.blockZ() + 0.5,
        block.yaw(),
        0f);
  }

  /** Loads every chunk a box touches. */
  CompletableFuture<Void> loaded(Bounds bounds) {
    List<CompletableFuture<Void>> chunks = new ArrayList<>();
    for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) {
      for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) {
        chunks.add(
            platform
                .world()
                .loadChunk(Position.of(bounds.dimension(), x * 16.0, bounds.minY(), z * 16.0)));
      }
    }
    return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new));
  }
}
