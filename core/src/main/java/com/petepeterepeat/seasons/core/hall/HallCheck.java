// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * {@code /hof hall check} (questions 255): what doesn't fit together in a Hall that loads, line by
 * line, then each gallery's cells and free cells. Read-only.
 */
public final class HallCheck {
  private HallCheck() {}

  /** What the check found: the things to look at, then the counts. */
  public record Report(List<Message> findings, List<Message> counts) {
    public Report {
      findings = List.copyOf(findings);
      counts = List.copyOf(counts);
    }
  }

  /**
   * Checks a Hall.
   *
   * @param air whether a block is air, for link blocks
   * @param seasonSpawn a season's recorded spawn, if it has one
   */
  public static Report run(
      HallLayout hall,
      Collection<Gallery> galleries,
      Predicate<Position> air,
      IntFunction<Optional<Position>> seasonSpawn,
      Messages messages) {
    return run(hall, galleries, air, at -> "", "", at -> true, seasonSpawn, messages);
  }

  /**
   * Checks a Hall, with what the world holds.
   *
   * @param blockType the block at a position, for gate planes (questions 280)
   * @param gateBlock {@code cells.gateBlock}, namespaced
   * @param floor whether a player can stand at a position, for entries and the Hall spawn
   */
  public static Report run(
      HallLayout hall,
      Collection<Gallery> galleries,
      Predicate<Position> air,
      java.util.function.Function<Position, String> blockType,
      String gateBlock,
      Predicate<Position> floor,
      IntFunction<Optional<Position>> seasonSpawn,
      Messages messages) {
    List<Message> found = new ArrayList<>();
    List<Gallery> sorted = galleries.stream().sorted(Comparator.comparing(Gallery::id)).toList();
    if (hall.hub().isEmpty()) {
      found.add(messages.get("hof.check.no-hub"));
    }
    if (hall.spawn().isEmpty()) {
      found.add(messages.get("hof.check.no-spawn"));
    }
    Set<String> joined = joined(hall, sorted);
    for (Gallery gallery : sorted) {
      String id = gallery.id();
      if (gallery.bounds().isEmpty()) {
        found.add(messages.get("hof.check.no-bounds", "gallery", id));
      } else if (hall.hub().isPresent() && !joined.contains(id)) {
        found.add(messages.get("hof.check.apart", "gallery", id));
      }
      if (gallery.entry().isEmpty()) {
        found.add(messages.get("hof.check.no-entry", "gallery", id));
      }
      gallery
          .areas()
          .entry()
          .filter(area -> area.blocks().stream().noneMatch(floor))
          .ifPresent(area -> found.add(messages.get("hof.check.no-floor", "what", id)));
      Optional<String> usual = usualSize(gallery);
      for (Cell cell : gallery.cells()) {
        String key = id + ":" + cell.id();
        if (cell.bounds().isEmpty()) {
          found.add(messages.get("hof.check.cell-no-bounds", "cell", key));
        } else if (usual.isPresent() && !cell.bounds().get().size().equals(usual.get())) {
          found.add(
              messages.get(
                  "hof.check.cell-size",
                  "cell",
                  key,
                  "size",
                  cell.bounds().get().size(),
                  "gallery",
                  id,
                  "usual",
                  usual.get()));
        }
        if (gallery.kind() == Gallery.Kind.SEASON && cell.anchor(AnchorType.FIGURE).isEmpty()) {
          found.add(messages.get("hof.check.cell-no-figure", "cell", key));
        }
        // Decoration of the gate's own block would go when the gate opens (questions 280).
        Optional<Bounds> plane = cell.gate();
        if (plane.isPresent() && !cell.gateShut()) {
          for (Position block : plane.get().blocks()) {
            if (blockType.apply(block).equals(gateBlock)) {
              found.add(
                  messages.get(
                      "hof.check.gate-own-block",
                      "cell",
                      key,
                      "block",
                      gateBlock,
                      "x",
                      Integer.toString(block.blockX()),
                      "y",
                      Integer.toString(block.blockY()),
                      "z",
                      Integer.toString(block.blockZ())));
              break;
            }
          }
        }
        cell.anchor(AnchorType.ENTRY)
            .flatMap(Anchor::area)
            .filter(area -> area.blocks().stream().noneMatch(floor))
            .ifPresent(area -> found.add(messages.get("hof.check.no-floor", "what", key)));
      }
    }
    for (HallLayout.Link link : hall.links().values()) {
      if (link.to().isEmpty()) {
        found.add(messages.get("hof.check.link-nowhere", "link", link.id()));
      } else if (link.to().get() instanceof HallLayout.Target.SeasonSpawn(int season)
          && seasonSpawn.apply(season).isEmpty()) {
        found.add(
            messages.get(
                "hof.check.link-no-spawn", "link", link.id(), "season", Integer.toString(season)));
      }
      if (air.test(link.block())) {
        Position at = link.block();
        found.add(
            messages.get(
                "hof.check.link-air",
                "link",
                link.id(),
                "x",
                Integer.toString(at.blockX()),
                "y",
                Integer.toString(at.blockY()),
                "z",
                Integer.toString(at.blockZ())));
      }
    }
    List<Message> counts = new ArrayList<>();
    for (Gallery gallery : sorted) {
      int season = gallery.season();
      long free = gallery.cells().stream().filter(c -> c.owner(season).isEmpty()).count();
      counts.add(
          messages.get(
              gallery.kind() == Gallery.Kind.SEASON
                  ? "hof.check.counts"
                  : "hof.check.counts-museum",
              "gallery",
              gallery.id(),
              "season",
              Integer.toString(season),
              "cells",
              Integer.toString(gallery.cells().size()),
              "free",
              Long.toString(free)));
    }
    return new Report(found, counts);
  }

  /**
   * The galleries a walk from the hub reaches: whose boxes share a face with it or one that does.
   */
  static Set<String> joined(HallLayout hall, List<Gallery> galleries) {
    Set<String> reached = new HashSet<>();
    if (hall.hub().isEmpty()) {
      return reached;
    }
    Deque<Bounds> todo = new ArrayDeque<>();
    todo.add(hall.hub().get());
    while (!todo.isEmpty()) {
      Bounds from = todo.poll();
      for (Gallery gallery : galleries) {
        if (gallery.bounds().isPresent()
            && !reached.contains(gallery.id())
            && gallery.bounds().get().touches(from)) {
          reached.add(gallery.id());
          todo.add(gallery.bounds().get());
        }
      }
    }
    return reached;
  }

  /** The size most of a gallery's cells have, if two or more cells have bounds. */
  private static Optional<String> usualSize(Gallery gallery) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Cell cell : gallery.cells()) {
      cell.bounds().ifPresent(b -> counts.merge(b.size(), 1, Integer::sum));
    }
    if (counts.values().stream().mapToInt(Integer::intValue).sum() < 2) {
      return Optional.empty();
    }
    return counts.entrySet().stream()
        .max(Comparator.comparing((Map.Entry<String, Integer> e) -> e.getValue()))
        .filter(e -> e.getValue() > 1)
        .map(Map.Entry::getKey);
  }
}
