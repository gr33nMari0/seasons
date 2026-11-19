// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code galleries/<id>.yml}: the part of the Hall for one season, with its cells.
 *
 * @param dimension where the gallery is; its bounds, cells and anchors are all in it
 * @param cells in the order they were made
 * @param settings what the gallery sets for itself; the rest comes from config.yml
 * @param launch the launch button's block, if the gallery has one (questions 172)
 */
public record Gallery(
    String id,
    Kind kind,
    int season,
    String dimension,
    Optional<Bounds> bounds,
    Optional<Position> entry,
    List<Position> exits,
    List<Cell> cells,
    Settings settings,
    Optional<Position> launch) {
  public Gallery {
    exits = List.copyOf(exits);
    cells = List.copyOf(cells);
  }

  /** A season's gallery, or the museum, which has no owners. */
  public enum Kind {
    SEASON,
    MUSEUM
  }

  /**
   * A gallery's own assignment, access, renderer and decorating times (questions 166). Each one
   * left out comes from config.yml.
   */
  public record Settings(
      Optional<SeasonsConfig.CellAssignment> assignment,
      Optional<SeasonsConfig.CellAccess> access,
      Optional<FigureRenderer> renderer,
      Optional<Boolean> lockAtEnd,
      Optional<Duration> afterArchive) {
    /** Everything from config.yml. */
    public static final Settings NONE =
        new Settings(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
  }

  public static Gallery create(String id, int season, String dimension) {
    return new Gallery(
        id,
        Kind.SEASON,
        season,
        dimension,
        Optional.empty(),
        Optional.empty(),
        List.of(),
        List.of(),
        Settings.NONE,
        Optional.empty());
  }

  public SeasonsConfig.CellAssignment assignment(SeasonsConfig config) {
    return settings.assignment().orElse(config.cells().assignment());
  }

  public SeasonsConfig.CellAccess access(SeasonsConfig config) {
    return settings.access().orElse(config.cells().access());
  }

  public FigureRenderer renderer() {
    return settings.renderer().orElse(FigureRenderer.MANNEQUIN);
  }

  public boolean lockAtEnd(SeasonsConfig config) {
    return settings.lockAtEnd().orElse(config.decorating().lockAtEnd());
  }

  public Duration afterArchive(SeasonsConfig config) {
    return settings.afterArchive().orElse(config.decorating().afterArchive());
  }

  public Optional<Cell> cell(String cellId) {
    return cells.stream().filter(c -> c.id().equals(cellId)).findFirst();
  }

  /** The cell a player owns here for the gallery's season. */
  public Optional<Cell> cellOf(PlayerId player) {
    return cells.stream().filter(c -> c.owner(season).equals(Optional.of(player))).findFirst();
  }

  /** The cell whose bounds hold a position. */
  public Optional<Cell> cellAt(Position position) {
    return cells.stream().filter(c -> c.contains(position)).findFirst();
  }

  public boolean contains(Position position) {
    return bounds.map(b -> b.contains(position)).orElse(false);
  }

  public Gallery withBounds(Bounds next) {
    return new Gallery(
        id, kind, season, dimension, Optional.of(next), entry, exits, cells, settings, launch);
  }

  public Gallery withEntry(Position next) {
    return new Gallery(
        id, kind, season, dimension, bounds, Optional.of(next), exits, cells, settings, launch);
  }

  public Gallery withExit(Position exit) {
    List<Position> next = new ArrayList<>(exits);
    next.add(exit);
    return new Gallery(id, kind, season, dimension, bounds, entry, next, cells, settings, launch);
  }

  public Gallery withLaunch(Optional<Position> next) {
    return new Gallery(id, kind, season, dimension, bounds, entry, exits, cells, settings, next);
  }

  public Gallery withSettings(Settings next) {
    return new Gallery(id, kind, season, dimension, bounds, entry, exits, cells, next, launch);
  }

  /** Replaces the cell with the same id, or adds it at the end. */
  public Gallery withCell(Cell cell) {
    List<Cell> next = new ArrayList<>(cells);
    int at = -1;
    for (int i = 0; i < next.size(); i++) {
      if (next.get(i).id().equals(cell.id())) {
        at = i;
      }
    }
    if (at >= 0) {
      next.set(at, cell);
    } else {
      next.add(cell);
    }
    return new Gallery(id, kind, season, dimension, bounds, entry, exits, next, settings, launch);
  }
}
