// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.TemplateScan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Where a stamp's copies go (questions 232 and 233): each copy's minimum corner before turning is
 * the origin plus the step times its index, and the copy is turned about it as the game turns
 * structures. Pure, so recovery works out the same copies from the saved plan.
 *
 * @param rotation clockwise seen from above: 0, 90, 180 or 270
 * @param cells the new cells' ids, one per copy
 * @param actor who stamped, for the audit log
 */
public record StampPlan(
    String gallery,
    String template,
    Template.Source source,
    String dimension,
    int originX,
    int originY,
    int originZ,
    int rotation,
    int stepX,
    int stepY,
    int stepZ,
    List<String> cells,
    String actor) {
  private static final java.util.regex.Pattern COMMA = java.util.regex.Pattern.compile(",");

  /** The plan file's format, written first; a plan of another format isn't read. */
  public static final int FORMAT_VERSION = 1;

  public StampPlan {
    cells = List.copyOf(cells);
    if (rotation % 90 != 0 || rotation < 0 || rotation >= 360) {
      throw new IllegalArgumentException("A rotation is 0, 90, 180 or 270");
    }
  }

  /**
   * One copy.
   *
   * @param sets every block the copy sets, which must be air before it is placed
   * @param markers the blocks its markers stand on, which become air once it is placed
   */
  public record Copy(
      String cell,
      Position origin,
      Bounds bounds,
      List<Anchor> anchors,
      List<Position> sets,
      List<Position> markers) {
    public Copy {
      anchors = List.copyOf(anchors);
      sets = List.copyOf(sets);
      markers = List.copyOf(markers);
    }
  }

  /** The default step: the turned template's width along +x, so copies stand side by side. */
  public static int[] defaultStep(TemplateScan scan, int rotation) {
    int width = rotation == 90 || rotation == 270 ? scan.sizeZ() : scan.sizeX();
    return new int[] {width, 0, 0};
  }

  /** A relative x and z turned clockwise by {@code rotation} degrees, as the game turns them. */
  public static int[] turn(int x, int z, int rotation) {
    return switch (rotation) {
      case 90 -> new int[] {-z, x};
      case 180 -> new int[] {-x, -z};
      case 270 -> new int[] {z, -x};
      default -> new int[] {x, z};
    };
  }

  /** The yaw of something facing +z in the template once turned: 0, 90, 180 or -90. */
  public static float yaw(int rotation) {
    return rotation == 270 ? -90f : rotation;
  }

  /** Every copy, in order. */
  public List<Copy> copies(Template template) {
    TemplateScan scan = template.scan();
    List<Template.Marker> markers = template.markers();
    List<Copy> out = new ArrayList<>();
    for (int i = 0; i < cells.size(); i++) {
      int ox = originX + stepX * i;
      int oy = originY + stepY * i;
      int oz = originZ + stepZ * i;
      Position origin = Position.of(dimension, ox, oy, oz);
      Position a = at(ox, oy, oz, 0, 0, 0);
      Position b = at(ox, oy, oz, scan.sizeX() - 1, scan.sizeY() - 1, scan.sizeZ() - 1);
      Bounds bounds =
          Bounds.between(
              dimension, a.blockX(), a.blockY(), a.blockZ(), b.blockX(), b.blockY(), b.blockZ());
      List<Position> sets = new ArrayList<>();
      for (TemplateScan.Block block : scan.blocks()) {
        sets.add(at(ox, oy, oz, block.x(), block.y(), block.z()));
      }
      List<Anchor> anchors = new ArrayList<>();
      List<Position> markerBlocks = new ArrayList<>();
      float yaw = yaw(rotation);
      for (Template.Marker marker : markers) {
        Position spot = at(ox, oy, oz, marker.x(), marker.y(), marker.z());
        anchors.add(
            Anchor.of(
                marker.type(), new Position(dimension, spot.x(), spot.y(), spot.z(), yaw, 0f)));
        markerBlocks.add(at(ox, oy, oz, marker.markerX(), marker.markerY(), marker.markerZ()));
      }
      out.add(new Copy(cells.get(i), origin, bounds, anchors, sets, markerBlocks));
    }
    return out;
  }

  private Position at(int ox, int oy, int oz, int x, int y, int z) {
    int[] turned = turn(x, z, rotation);
    return Position.of(dimension, ox + turned[0], oy + y, oz + turned[1]);
  }

  /** The plan as lines of {@code key=value}, for {@code data/stamps/<id>.plan}. */
  public String write() {
    Map<String, String> lines = new LinkedHashMap<>();
    lines.put("format-version", Integer.toString(FORMAT_VERSION));
    lines.put("gallery", gallery);
    lines.put("template", template);
    lines.put("source", source.name());
    lines.put("dimension", dimension);
    lines.put("origin", originX + "," + originY + "," + originZ);
    lines.put("rotation", Integer.toString(rotation));
    lines.put("step", stepX + "," + stepY + "," + stepZ);
    lines.put("cells", String.join(",", cells));
    lines.put("actor", actor);
    return lines.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("\n"))
        + "\n";
  }

  /**
   * Reads what {@link #write} wrote.
   *
   * @throws IllegalArgumentException if it is damaged
   */
  public static StampPlan read(String text) {
    Map<String, String> lines = new LinkedHashMap<>();
    for (String line : text.lines().toList()) {
      int eq = line.indexOf('=');
      if (eq > 0) {
        lines.put(line.substring(0, eq), line.substring(eq + 1));
      }
    }
    try {
      int format = Integer.parseInt(required(lines, "format-version"));
      if (format != FORMAT_VERSION) {
        throw new IllegalArgumentException("format-version " + format + " isn't one this reads");
      }
      int[] origin = ints(required(lines, "origin"));
      int[] step = ints(required(lines, "step"));
      return new StampPlan(
          required(lines, "gallery"),
          required(lines, "template"),
          Template.Source.valueOf(required(lines, "source")),
          required(lines, "dimension"),
          origin[0],
          origin[1],
          origin[2],
          Integer.parseInt(required(lines, "rotation")),
          step[0],
          step[1],
          step[2],
          List.of(COMMA.split(required(lines, "cells"))),
          lines.getOrDefault("actor", "console"));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("A damaged stamp plan: " + e.getMessage(), e);
    }
  }

  private static String required(Map<String, String> lines, String key) {
    String value = lines.get(key);
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("no " + key);
    }
    return value;
  }

  private static int[] ints(String text) {
    String[] parts = COMMA.split(text, -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException("a position needs three numbers");
    }
    return new int[] {
      Integer.parseInt(parts[0].trim()),
      Integer.parseInt(parts[1].trim()),
      Integer.parseInt(parts[2].trim())
    };
  }
}
