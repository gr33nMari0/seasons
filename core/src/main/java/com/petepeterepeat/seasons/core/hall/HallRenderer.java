// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ImportedCell;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Figure;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Draws a player's displays in their cell from their archive: the figure in their skin and gear at
 * the figure anchor, the plaque, and an imported cell's trophies. Every redraw first removes what
 * the last one drew, so drawing twice leaves one set. Only copies of items are ever shown.
 */
public final class HallRenderer implements DisplayRenderer {
  /** How high above the plaque anchor's block its text floats. */
  private static final double PLAQUE_HEIGHT = 1.2;

  private final Platform platform;
  private final ConfigHolder config;
  private final SerialWriter writer;
  private final ArchiveFiles archives;
  private final HallService hall;
  private final Executor serverThread;

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
                  .thenRunAsync(() -> draw(ref.get(), found.get()), serverThread);
            },
            serverThread);
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

  /** Draws a cell's displays; its chunks are loaded. */
  void draw(HallService.CellRef ref, ArchiveRecord archive) {
    Bounds bounds = ref.cell().bounds().orElseThrow();
    String gallery = ref.gallery().id();
    String cell = ref.cell().id();
    int season = archive.season();
    platform
        .displays()
        .removeTagged(bounds.min(), bounds.max(), DisplayTag.prefix(gallery, cell, season));
    Messages messages = config.get().messages();
    String seasonText = Integer.toString(season);
    ref.cell()
        .anchor(AnchorType.FIGURE)
        .ifPresent(anchor ->
            platform
                .displays()
                .spawnFigure(
                    new Figure(
                        HallService.standOn(anchor.at()),
                        messages.get(
                            "hof.figure-name", "name", archive.name(), "season", seasonText),
                        archive.skin(),
                        archive.figureEquipment(),
                        new DisplayTag(gallery, cell, season, DisplayTag.FIGURE).toString())));
    ref.cell()
        .anchor(AnchorType.PLAQUE)
        .ifPresent(anchor ->
            platform
                .displays()
                .spawnText(
                    above(anchor.at(), PLAQUE_HEIGHT),
                    plaque(messages, archive),
                    new DisplayTag(gallery, cell, season, DisplayTag.PLAQUE).toString()));
    if (archive.imported().isPresent()) {
      List<Anchor> trophies = ref.cell().anchors(AnchorType.TROPHY);
      List<ImportedCell.Frame> frames = new ArrayList<>(archive.imported().get().frames());
      for (Anchor trophy : trophies) {
        frames.stream()
            .filter(frame -> trophy.isAt(frame.at()))
            .findFirst()
            .flatMap(ImportedCell.Frame::item)
            .ifPresent(item ->
                platform
                    .displays()
                    .spawnItem(
                        above(trophy.at(), 0.5),
                        item,
                        new DisplayTag(gallery, cell, season, DisplayTag.TROPHY).toString()));
      }
    }
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
  private CompletableFuture<Void> loaded(Bounds bounds) {
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
