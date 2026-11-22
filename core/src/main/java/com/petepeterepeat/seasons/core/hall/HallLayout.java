// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code hall.yml}: the Hall's own parts (questions 243): the hub, the Hall spawn in it, the Hall's
 * exits and the links.
 *
 * @param dimension where the hub and the Hall spawn are
 * @param links by id, in the order they were made
 * @param areas the Hall spawn as a box, and exits that are areas (questions 279)
 */
public record HallLayout(
    String dimension,
    Optional<Bounds> hub,
    Optional<Position> spawn,
    List<Position> exits,
    Map<String, Link> links,
    Gallery.Areas areas) {
  public HallLayout(
      String dimension,
      Optional<Bounds> hub,
      Optional<Position> spawn,
      List<Position> exits,
      Map<String, Link> links) {
    this(dimension, hub, spawn, exits, links, Gallery.Areas.NONE);
  }

  public static final String OVERWORLD = "minecraft:overworld";

  /** No hub, no spawn, no links: a server that hasn't marked its Hall. */
  public static final HallLayout NONE =
      new HallLayout(OVERWORLD, Optional.empty(), Optional.empty(), List.of(), Map.of());

  public HallLayout {
    exits = List.copyOf(exits);
    links = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(links));
  }

  /**
   * A block that takes whoever clicks or steps on it somewhere (questions 248).
   *
   * @param block the link's block, in any dimension
   * @param to where it leads; empty until set
   */
  public record Link(String id, Position block, Optional<Target> to, Optional<Bounds> area) {
    public Link(String id, Position block, Optional<Target> to) {
      this(id, block, to, Optional.empty());
    }

    public Link withTo(Optional<Target> next) {
      return new Link(id, block, next, area);
    }

    /**
     * Whether a position is in the link's block. A link that is only an area (questions 279) has
     * its area's first corner as its block, which isn't clicked or stood on as a block.
     */
    public boolean isAt(Position at) {
      return area.isEmpty()
          && block.dimension().equals(at.dimension())
          && block.blockX() == at.blockX()
          && block.blockY() == at.blockY()
          && block.blockZ() == at.blockZ();
    }
  }

  /** Where a link leads. */
  public sealed interface Target {
    /** The Hall spawn. */
    record Hall() implements Target {}

    /** A season's recorded spawn. */
    record SeasonSpawn(int season) implements Target {}

    /** A spot an admin stood at. */
    record At(Position at) implements Target {}
  }

  /** Whether a position is inside the hub. */
  public boolean inHub(Position position) {
    return hub.map(b -> b.contains(position)).orElse(false);
  }

  public Optional<Link> link(String id) {
    return Optional.ofNullable(links.get(id));
  }

  /** The link whose block a position is in. */
  public Optional<Link> linkAt(Position at) {
    return links.values().stream().filter(l -> l.isAt(at)).findFirst();
  }

  public HallLayout withHub(Bounds next) {
    return new HallLayout(next.dimension(), Optional.of(next), spawn, exits, links, areas);
  }

  public HallLayout withSpawn(Position next) {
    return withSpawn(next, Optional.empty());
  }

  /** The Hall spawn, a block or a box from it (questions 279). */
  public HallLayout withSpawn(Position next, Optional<Bounds> area) {
    return new HallLayout(
        dimension, hub, Optional.of(next), exits, links, new Gallery.Areas(area, areas.exits()));
  }

  /** An exit that is a box. */
  public HallLayout withExitArea(Bounds area) {
    List<Bounds> next = new ArrayList<>(areas.exits());
    next.add(area);
    return new HallLayout(
        dimension, hub, spawn, exits, links, new Gallery.Areas(areas.entry(), next));
  }

  public HallLayout withExit(Position exit) {
    List<Position> next = new ArrayList<>(exits);
    next.add(exit);
    return new HallLayout(dimension, hub, spawn, next, links, areas);
  }

  public HallLayout withoutExits() {
    return new HallLayout(
        dimension, hub, spawn, List.of(), links, new Gallery.Areas(areas.entry(), List.of()));
  }

  /** Adds a link, or replaces the one with its id. */
  public HallLayout withLink(Link link) {
    Map<String, Link> next = new LinkedHashMap<>(links);
    next.put(link.id(), link);
    return new HallLayout(dimension, hub, spawn, exits, next, areas);
  }

  public HallLayout withoutLink(String id) {
    Map<String, Link> next = new LinkedHashMap<>(links);
    next.remove(id);
    return new HallLayout(dimension, hub, spawn, exits, next, areas);
  }

  /** The next free link id: {@code l1}, {@code l2}, … */
  public String nextLinkId() {
    for (int i = 1; ; i++) {
      String id = "l" + i;
      if (!links.containsKey(id)) {
        return id;
      }
    }
  }
}
