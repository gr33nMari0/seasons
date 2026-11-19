// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One player's slot in a gallery.
 *
 * @param bounds the cell's area; owners decorate inside it
 * @param owners the owner for each season, by season number
 * @param imported whether {@code /hof import} filled it from a hand-built cell
 */
public record Cell(
    String id,
    Optional<Bounds> bounds,
    List<Anchor> anchors,
    Map<Integer, PlayerId> owners,
    boolean imported) {
  public Cell {
    anchors = List.copyOf(anchors);
    owners = Map.copyOf(owners);
  }

  public static Cell create(String id) {
    return new Cell(id, Optional.empty(), List.of(), Map.of(), false);
  }

  public Optional<PlayerId> owner(int season) {
    return Optional.ofNullable(owners.get(season));
  }

  public List<Anchor> anchors(AnchorType type) {
    return anchors.stream().filter(a -> a.type() == type).toList();
  }

  public Optional<Anchor> anchor(AnchorType type) {
    return anchors.stream().filter(a -> a.type() == type).findFirst();
  }

  /**
   * The gate plane between the cell's two gate anchors, for access mode {@code gate}; empty unless
   * it has both.
   */
  public Optional<Bounds> gate() {
    List<Anchor> corners = anchors(AnchorType.GATE);
    if (corners.size() != 2) {
      return Optional.empty();
    }
    Position a = corners.get(0).at();
    Position b = corners.get(1).at();
    return Optional.of(
        Bounds.between(
            a.dimension(), a.blockX(), a.blockY(), a.blockZ(), b.blockX(), b.blockY(), b.blockZ()));
  }

  /** Whether the block a position is in lies inside the cell's bounds. */
  public boolean contains(Position position) {
    return bounds.map(b -> b.contains(position)).orElse(false);
  }

  public Cell withBounds(Bounds next) {
    return new Cell(id, Optional.of(next), anchors, owners, imported);
  }

  /**
   * Adds an anchor. Types that allow only so many replace the oldest of their kind, so setting a
   * figure again moves it.
   */
  public Cell withAnchor(Anchor anchor) {
    List<Anchor> next = new ArrayList<>(anchors);
    next.removeIf(a -> a.isAt(anchor.at()) && a.type() == anchor.type());
    List<Anchor> same = next.stream().filter(a -> a.type() == anchor.type()).toList();
    if (same.size() >= anchor.type().max()) {
      next.remove(same.getFirst());
    }
    next.add(anchor);
    return new Cell(id, bounds, next, owners, imported);
  }

  public Cell withoutAnchors(AnchorType type) {
    List<Anchor> next = new ArrayList<>(anchors);
    next.removeIf(a -> a.type() == type);
    return new Cell(id, bounds, next, owners, imported);
  }

  public Cell withAnchors(List<Anchor> next) {
    return new Cell(id, bounds, next, owners, imported);
  }

  public Cell withOwner(int season, Optional<PlayerId> owner) {
    Map<Integer, PlayerId> next = new HashMap<>(owners);
    owner.ifPresentOrElse(o -> next.put(season, o), () -> next.remove(season));
    return new Cell(id, bounds, anchors, next, imported);
  }

  public Cell withImported(boolean value) {
    return new Cell(id, bounds, anchors, owners, value);
  }
}
