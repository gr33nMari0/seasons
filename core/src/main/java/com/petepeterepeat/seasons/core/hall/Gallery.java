// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code galleries/<id>.yml}: the part of the Hall for one season, with its cells. Assignment,
 * access and the decorating window come from config.yml for now.
 *
 * @param dimension where the gallery is; its bounds, cells and anchors are all in it
 * @param cells in the order they were made
 */
public record Gallery(
    String id,
    Kind kind,
    int season,
    String dimension,
    Optional<Bounds> bounds,
    Optional<Position> entry,
    List<Position> exits,
    List<Cell> cells) {
  public Gallery {
    exits = List.copyOf(exits);
    cells = List.copyOf(cells);
  }

  /** A season's gallery, or the museum, which has no owners. */
  public enum Kind {
    SEASON,
    MUSEUM
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
        List.of());
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
    return new Gallery(id, kind, season, dimension, Optional.of(next), entry, exits, cells);
  }

  public Gallery withEntry(Position next) {
    return new Gallery(id, kind, season, dimension, bounds, Optional.of(next), exits, cells);
  }

  public Gallery withExit(Position exit) {
    List<Position> next = new ArrayList<>(exits);
    next.add(exit);
    return new Gallery(id, kind, season, dimension, bounds, entry, next, cells);
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
    return new Gallery(id, kind, season, dimension, bounds, entry, exits, next);
  }
}
