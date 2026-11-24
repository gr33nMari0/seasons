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
 * @param areas its entry as a box, and exits that are areas (questions 279)
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
    Optional<Position> launch,
    Areas areas) {
  public Gallery {
    exits = List.copyOf(exits);
    cells = List.copyOf(cells);
  }

  public Gallery(
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
    this(id, kind, season, dimension, bounds, entry, exits, cells, settings, launch, Areas.NONE);
  }

  /**
   * Where arrivals spread and exits that are areas (questions 279).
   *
   * @param entry the entry as a box, from the entry point; empty for a single block
   * @param exits exits that are boxes, beside the single-block exits
   */
  public record Areas(Optional<Bounds> entry, List<Bounds> exits) {
    public static final Areas NONE = new Areas(Optional.empty(), List.of());

    public Areas {
      exits = List.copyOf(exits);
    }
  }

  /** A season's gallery, or the museum, which has no owners. */
  public enum Kind {
    SEASON,
    MUSEUM,
    /**
     * Hand-built cells kept as they are (questions 281): protected as Hall, but never drawn in,
     * assigned, locked or gated.
     */
    LEGACY
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
      Optional<Duration> afterArchive,
      Optional<Double> plaqueScale) {
    /** Everything from config.yml. */
    public static final Settings NONE =
        new Settings(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    public Settings(
        Optional<SeasonsConfig.CellAssignment> assignment,
        Optional<SeasonsConfig.CellAccess> access,
        Optional<FigureRenderer> renderer,
        Optional<Boolean> lockAtEnd,
        Optional<Duration> afterArchive) {
      this(assignment, access, renderer, lockAtEnd, afterArchive, Optional.empty());
    }

    public Settings withPlaqueScale(Optional<Double> next) {
      return new Settings(assignment, access, renderer, lockAtEnd, afterArchive, next);
    }
  }

  /** The plaque text size for a cell's plaque anchor: its own, else the gallery's, else 1. */
  public double plaqueScale(Anchor plaque) {
    return plaque.scale().isPresent()
        ? plaque.scale().getAsDouble()
        : settings.plaqueScale().orElse(1.0);
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
        id,
        kind,
        season,
        dimension,
        Optional.of(next),
        entry,
        exits,
        cells,
        settings,
        launch,
        areas);
  }

  public Gallery withEntry(Position next) {
    return withEntry(next, Optional.empty());
  }

  /** The entry, a block or a box from it (questions 279). */
  public Gallery withEntry(Position next, Optional<Bounds> area) {
    return new Gallery(
        id,
        kind,
        season,
        dimension,
        bounds,
        Optional.of(next),
        exits,
        cells,
        settings,
        launch,
        new Areas(area, areas.exits()));
  }

  /** No entry: {@code /hof gallery entry <gallery> none}. */
  public Gallery withoutEntry() {
    return new Gallery(
        id,
        kind,
        season,
        dimension,
        bounds,
        Optional.empty(),
        exits,
        cells,
        settings,
        launch,
        new Areas(Optional.empty(), areas.exits()));
  }

  /** An exit that is a box. */
  public Gallery withExitArea(Bounds area) {
    List<Bounds> next = new ArrayList<>(areas.exits());
    next.add(area);
    return new Gallery(
        id,
        kind,
        season,
        dimension,
        bounds,
        entry,
        exits,
        cells,
        settings,
        launch,
        new Areas(areas.entry(), next));
  }

  /** The same gallery as another kind, such as a legacy gallery once imported (questions 281). */
  public Gallery withKind(Kind next) {
    return new Gallery(
        id, next, season, dimension, bounds, entry, exits, cells, settings, launch, areas);
  }

  public Gallery withExit(Position exit) {
    List<Position> next = new ArrayList<>(exits);
    next.add(exit);
    return new Gallery(
        id, kind, season, dimension, bounds, entry, next, cells, settings, launch, areas);
  }

  public Gallery withLaunch(Optional<Position> next) {
    return new Gallery(
        id, kind, season, dimension, bounds, entry, exits, cells, settings, next, areas);
  }

  public Gallery withSettings(Settings next) {
    return new Gallery(
        id, kind, season, dimension, bounds, entry, exits, cells, next, launch, areas);
  }

  /** Without the cell with that id. */
  public Gallery withoutCell(String cellId) {
    List<Cell> next = new ArrayList<>(cells);
    next.removeIf(c -> c.id().equals(cellId));
    return new Gallery(
        id, kind, season, dimension, bounds, entry, exits, next, settings, launch, areas);
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
    return new Gallery(
        id, kind, season, dimension, bounds, entry, exits, next, settings, launch, areas);
  }
}
