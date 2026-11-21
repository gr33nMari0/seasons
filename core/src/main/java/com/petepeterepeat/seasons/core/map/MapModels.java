// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.hall.Bounds;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.map.MapModel.Area;
import com.petepeterepeat.seasons.core.map.MapModel.Outline;
import com.petepeterepeat.seasons.core.map.MapModel.Point;
import com.petepeterepeat.seasons.core.map.MapModel.Style;
import com.petepeterepeat.seasons.core.map.MapModel.WaypointSet;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.World;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.zone.Zone;
import com.petepeterepeat.seasons.core.zone.ZoneShape;
import com.petepeterepeat.seasons.core.zone.Zones;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the map shows, worked out from the files, the season state and the zones in force:
 *
 * <ul>
 *   <li>the spawn of every season that has launched, so the next season's spawn appears at its
 *       launch;
 *   <li>the next season's spawn candidates, until it launches;
 *   <li>the Hall, where {@code /hof} lands, and each museum;
 *   <li>each zone in force as an area, styled by what it stops now, so Homecoming changes the areas
 *       when it changes the zones, and nothing shows before launch or between seasons, when no zone
 *       is in force; the Hall and the zone's own exemptions are holes in it;
 *   <li>the shared waypoints, a set of points each.
 * </ul>
 */
public final class MapModels {
  private MapModels() {}

  /**
   * The map for these files and this season state.
   *
   * @param zones the zones in force now, with what each stops
   * @param until until when a zone is locked, as players read it
   * @param world for each dimension's build heights, the bounds of a zone with no y limits
   */
  public static MapModel build(
      ConfigSnapshot snapshot,
      Optional<SeasonState> state,
      List<Zones.InForce> zones,
      Function<Zone, String> until,
      World world) {
    Messages messages = snapshot.messages();
    List<Point> points = new ArrayList<>();
    state.ifPresent(current -> {
      for (SeasonFile file : bySeason(snapshot.seasons())) {
        if (file.season() <= current.season() && file.spawn().isPresent()) {
          points.add(
              new Point(
                  "spawn-" + file.season(),
                  messages.text("map.spawn", "season", Integer.toString(file.season())),
                  middle(file.spawn().get())));
        }
      }
      snapshot
          .season(current.season() + 1)
          .ifPresent(next ->
              next.spawnCandidates()
                  .forEach((name, at) ->
                      points.add(
                          new Point(
                              "candidate-" + name,
                              messages.text("map.candidate", "name", name.replace('-', ' ')),
                              middle(at)))));
    });
    snapshot
        .hall()
        .spawn()
        .map(MapModels::middle)
        .or(() -> hall(snapshot.allGalleries()))
        .ifPresent(at -> points.add(new Point("hall", messages.text("map.hall"), at)));
    for (Gallery gallery : snapshot.allGalleries()) {
      if (gallery.kind() == Gallery.Kind.MUSEUM) {
        place(gallery)
            .ifPresent(at ->
                points.add(new Point("museum-" + gallery.id(), messages.text("map.museum"), at)));
      }
    }

    // The Hall's boxes, the hub's included, are cut out of every zone (questions 243).
    List<Bounds> hallBoxes = new ArrayList<>();
    snapshot.hall().hub().ifPresent(hallBoxes::add);
    snapshot.allGalleries().forEach(g -> g.bounds().ifPresent(hallBoxes::add));
    List<Area> areas = new ArrayList<>();
    for (Zones.InForce zone : zones) {
      area(zone, until, world, messages, hallBoxes).ifPresent(areas::add);
    }

    List<WaypointSet> sets = new ArrayList<>();
    snapshot
        .waypoints()
        .sets()
        .forEach((set, waypoints) ->
            sets.add(
                new WaypointSet(
                    set,
                    set,
                    waypoints.stream()
                        .map(w -> new Point(Waypoints.key(w.name()), w.name(), middle(w.at())))
                        .toList())));
    return new MapModel(messages.text("map.set"), points, areas, sets);
  }

  private static List<SeasonFile> bySeason(Map<Integer, SeasonFile> seasons) {
    return seasons.values().stream().sorted(Comparator.comparingInt(SeasonFile::season)).toList();
  }

  /** With no Hall spawn, the Hall's point: the newest season gallery's entry. */
  private static Optional<Position> hall(java.util.Collection<Gallery> galleries) {
    List<Gallery> seasons =
        galleries.stream()
            .filter(g -> g.kind() == Gallery.Kind.SEASON)
            .sorted(Comparator.comparingInt(Gallery::season).reversed())
            .toList();
    return seasons.stream()
        .filter(g -> g.entry().isPresent())
        .findFirst()
        .flatMap(MapModels::place)
        .or(() -> seasons.stream().findFirst().flatMap(MapModels::place));
  }

  /** A gallery on the map: its entry, else the middle of its floor. */
  private static Optional<Position> place(Gallery gallery) {
    if (gallery.entry().isPresent()) {
      return Optional.of(middle(gallery.entry().get()));
    }
    return gallery
        .bounds()
        .map(b ->
            Position.of(
                b.dimension(),
                (b.minX() + b.maxX() + 1) / 2.0,
                b.minY(),
                (b.minZ() + b.maxZ() + 1) / 2.0));
  }

  /** A zone in force as an area, or none while it stops nothing. */
  private static Optional<Area> area(
      Zones.InForce inForce,
      Function<Zone, String> until,
      World world,
      Messages messages,
      List<Bounds> hallBoxes) {
    Zone zone = inForce.zone();
    String name = zone.displayName();
    Optional<Style> style =
        switch (inForce.now()) {
          case BLOCK -> Optional.of(Style.LOCKED);
          case NO_BUILD -> Optional.of(Style.NO_BUILD);
          case ANNOUNCE -> Optional.of(Style.ANNOUNCE);
          case OFF -> Optional.empty();
        };
    if (style.isEmpty()) {
      return Optional.empty();
    }
    String label =
        switch (style.get()) {
          case LOCKED -> messages.text("map.locked", "zone", name, "until", until.apply(zone));
          case NO_BUILD -> messages.text("map.no-build", "zone", name);
          case ANNOUNCE -> messages.text("map.announce", "zone", name, "until", until.apply(zone));
        };
    Outline outline;
    double minY = world.minHeight(zone.dimension());
    double maxY = world.maxHeight(zone.dimension());
    switch (zone.shape()) {
      case ZoneShape.Box box -> {
        outline = new Outline.Rectangle(box.minX(), box.minZ(), box.maxX() + 1, box.maxZ() + 1);
        minY = box.minY();
        maxY = box.maxY() + 1;
      }
      case ZoneShape.Cylinder cylinder -> {
        outline = new Outline.Circle(cylinder.centerX(), cylinder.centerZ(), cylinder.radius());
        minY = cylinder.minY().orElse((int) minY);
        maxY = cylinder.maxY().isPresent() ? cylinder.maxY().getAsInt() + 1 : maxY;
      }
      case ZoneShape.OutsideCircle c ->
          outline = Outline.Beyond.around(new Outline.Circle(c.centerX(), c.centerZ(), c.radius()));
      case ZoneShape.OutsideBox b ->
          outline =
              Outline.Beyond.around(
                  new Outline.Rectangle(b.minX(), b.minZ(), b.maxX() + 1, b.maxZ() + 1));
      // A height zone covers every column, so as an area it would cover the whole map, which the
      // top-down view shows as every block of the dimension locked (questions 159).
      case ZoneShape.YAbove _, ZoneShape.YBelow _ -> {
        return Optional.empty();
      }
    }
    Outline area = outline;
    List<Outline> holes = new ArrayList<>();
    for (ZoneShape.Box box : zone.exempt()) {
      Outline.Rectangle hole =
          new Outline.Rectangle(box.minX(), box.minZ(), box.maxX() + 1, box.maxZ() + 1);
      if (canBeHole(area, hole)) {
        holes.add(hole);
      }
    }
    for (Bounds box : hallBoxes) {
      Optional.of(box)
          .filter(b ->
              b.dimension().equals(zone.dimension())
                  && zone.shape().reaches(b.minX(), b.minZ(), b.maxX(), b.maxZ()))
          .map(b -> new Outline.Rectangle(b.minX(), b.minZ(), b.maxX() + 1, b.maxZ() + 1))
          .filter(hole -> canBeHole(area, hole))
          .ifPresent(holes::add);
    }
    return Optional.of(
        new Area(
            "zone-" + zone.id(), label, zone.dimension(), area, holes, minY, maxY, style.get()));
  }

  /**
   * Whether a rectangle can be a hole in a zone's area: in an outside zone's ring, only if it lies
   * wholly beyond the ring's inside and wholly within its outer edge, since a hole crossing another
   * hole (the inside) or the outline breaks the area's shape. The zone is enforced there all the
   * same; the map just doesn't show the cut-out.
   */
  private static boolean canBeHole(Outline outline, Outline.Rectangle hole) {
    return !(outline instanceof Outline.Beyond ring)
        || (!overlaps(ring.inside(), hole) && holds(ring.outer(), hole));
  }

  /** Whether a rectangle and a circle or rectangle share any ground. */
  private static boolean overlaps(Outline outline, Outline.Rectangle r) {
    return switch (outline) {
      case Outline.Circle c -> {
        // The rectangle's point nearest the centre.
        double x = Math.clamp(c.centerX(), r.minX(), r.maxX());
        double z = Math.clamp(c.centerZ(), r.minZ(), r.maxZ());
        yield Math.hypot(x - c.centerX(), z - c.centerZ()) < c.radius();
      }
      case Outline.Rectangle o ->
          r.maxX() > o.minX() && r.minX() < o.maxX() && r.maxZ() > o.minZ() && r.minZ() < o.maxZ();
      case Outline.Beyond _ -> true;
    };
  }

  /** Whether a circle or rectangle holds all of a rectangle. */
  private static boolean holds(Outline outline, Outline.Rectangle r) {
    return switch (outline) {
      case Outline.Circle c -> {
        // The rectangle's corner furthest from the centre.
        double x = Math.max(Math.abs(r.minX() - c.centerX()), Math.abs(r.maxX() - c.centerX()));
        double z = Math.max(Math.abs(r.minZ() - c.centerZ()), Math.abs(r.maxZ() - c.centerZ()));
        yield Math.hypot(x, z) <= c.radius();
      }
      case Outline.Rectangle o ->
          r.minX() >= o.minX()
              && r.maxX() <= o.maxX()
              && r.minZ() >= o.minZ()
              && r.maxZ() <= o.maxZ();
      case Outline.Beyond _ -> false;
    };
  }

  /** The middle of the block a position is in, at its feet: where a player stands on it. */
  private static Position middle(Position at) {
    return Position.of(at.dimension(), at.blockX() + 0.5, at.blockY(), at.blockZ() + 0.5);
  }
}
