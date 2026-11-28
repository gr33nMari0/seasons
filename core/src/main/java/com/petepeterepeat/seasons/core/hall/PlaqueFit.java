// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where a cell's plaque text stands so it never clips into blocks (1.4.1 plan, H4; questions 344 to
 * 346). A plaque anchor on a wall block (solid, with a solid block above it) shows its text just in
 * front of the face it faces, fixed. One on anything else floats above it and turns to the viewer,
 * as before, while the space it turns in is clear; if not, it is fixed facing out. Either way the
 * text shrinks until it fits, down to {@link #MIN}.
 */
public final class PlaqueFit {
  /** The smallest a plaque is drawn to fit. */
  public static final double MIN = 0.25;

  /** How far above a floating plaque's anchor block its text starts. */
  static final double FLOATING = 1.2;

  /** How far in front of a wall block's face its text stands. */
  static final double IN_FRONT = 0.52;

  // A text display at scale 1: 40 pixels to a block, about 6 pixels a character, 10 a line, lines
  // wrapped at 200 pixels, and a pixel of background all round.
  private static final double PIXEL = 1 / 40.0;
  private static final int CHAR = 6;
  private static final int LINE = 10;
  private static final int WRAP = 200;
  private static final double STEP = 0.9;

  private PlaqueFit() {}

  /** The way a plaque faces: the side of its block its text stands on. */
  public enum Facing {
    NORTH("north", 0, -1, 180f),
    SOUTH("south", 0, 1, 0f),
    WEST("west", -1, 0, 90f),
    EAST("east", 1, 0, -90f),
    UP("up", 0, 0, 0f);

    private final String id;
    private final int dx;
    private final int dz;
    private final float yaw;

    Facing(String id, int dx, int dz, float yaw) {
      this.id = id;
      this.dx = dx;
      this.dz = dz;
      this.yaw = yaw;
    }

    public String id() {
      return id;
    }

    /** The yaw an entity turned this way has. */
    public float yaw() {
      return yaw;
    }

    public static Optional<Facing> of(String id) {
      for (Facing facing : values()) {
        if (facing.id.equals(id.toLowerCase(Locale.ROOT))) {
          return Optional.of(facing);
        }
      }
      return Optional.empty();
    }

    static final List<Facing> SIDES = List.of(NORTH, SOUTH, WEST, EAST);
  }

  /**
   * How a plaque fitted when drawn.
   *
   * @param asked the size the cell or gallery asks for
   * @param shown the size it is drawn at
   * @param fits whether it fits at that size; false when it doesn't even at {@link #MIN}
   */
  public record Fit(double asked, double shown, boolean fits) {}

  /**
   * Where and how a plaque's text is drawn.
   *
   * @param at where the display stands, its yaw the way it faces when fixed
   * @param fixed whether it keeps that yaw, rather than turning to the viewer
   * @param guessed the facing guessed for an anchor without one, to keep
   */
  public record Placed(Position at, boolean fixed, Fit fit, Optional<Facing> guessed) {}

  /**
   * Places a plaque.
   *
   * @param block the plaque anchor's block
   * @param facing the anchor's facing, if set
   * @param inside the cell's bounds, for guessing a facing into the cell
   * @param solid whether a block is solid
   */
  public static Placed place(
      Position block,
      Optional<Facing> facing,
      Optional<Bounds> inside,
      Message text,
      double asked,
      Predicate<Position> solid) {
    double[] size = size(text);
    boolean mounted = solid.test(block) && solid.test(offset(block, 0, 1, 0));
    if (mounted && facing.filter(f -> f == Facing.UP).isEmpty()) {
      Optional<Facing> guessed = Optional.empty();
      Facing face;
      if (facing.isPresent()) {
        face = facing.get();
      } else {
        face = guess(block, inside, solid, true);
        guessed = Optional.of(face);
      }
      return onWall(block, face, size, asked, solid, guessed);
    }
    Position above =
        new Position(
            block.dimension(),
            block.blockX() + 0.5,
            block.blockY() + FLOATING,
            block.blockZ() + 0.5,
            block.yaw(),
            0f);
    if (turnsFreely(above, size, asked, solid)) {
      return new Placed(above, false, new Fit(asked, asked, true), Optional.empty());
    }
    Optional<Facing> guessed = Optional.empty();
    Facing face;
    if (facing.filter(f -> f != Facing.UP).isPresent()) {
      face = facing.get();
    } else {
      face = guess(block, inside, solid, false);
      if (facing.isEmpty()) {
        guessed = Optional.of(face);
      }
    }
    double scale = asked;
    while (!fixedFits(above, face, size, scale, solid) && scale > MIN) {
      scale = Math.max(MIN, round(scale * STEP));
    }
    boolean fits = fixedFits(above, face, size, scale, solid);
    return new Placed(withYaw(above, face.yaw()), true, new Fit(asked, scale, fits), guessed);
  }

  private static Placed onWall(
      Position block,
      Facing face,
      double[] size,
      double asked,
      Predicate<Position> solid,
      Optional<Facing> guessed) {
    double scale = asked;
    while (!wallFits(block, face, size, scale, solid) && scale > MIN) {
      scale = Math.max(MIN, round(scale * STEP));
    }
    boolean fits = wallFits(block, face, size, scale, solid);
    double height = size[1] * scale;
    Position at =
        new Position(
            block.dimension(),
            block.blockX() + 0.5 + face.dx * IN_FRONT,
            block.blockY() + 0.5 - height / 2,
            block.blockZ() + 0.5 + face.dz * IN_FRONT,
            face.yaw(),
            0f);
    return new Placed(at, true, new Fit(asked, scale, fits), guessed);
  }

  /** Whether the text, in front of a wall block's face, is clear of the blocks beside it. */
  private static boolean wallFits(
      Position block, Facing face, double[] size, double scale, Predicate<Position> solid) {
    double width = size[0] * scale;
    double height = size[1] * scale;
    double bottom = block.blockY() + 0.5 - height / 2;
    Position front = offset(block, face.dx, 0, face.dz);
    return clear(
        front, face, block.blockX() + 0.5, block.blockZ() + 0.5, width, bottom, height, solid);
  }

  /** Whether fixed text, through a floating plaque's middle, is clear of blocks. */
  private static boolean fixedFits(
      Position at, Facing face, double[] size, double scale, Predicate<Position> solid) {
    Position layer =
        Position.of(at.dimension(), Math.floor(at.x()), Math.floor(at.y()), Math.floor(at.z()));
    return clear(layer, face, at.x(), at.z(), size[0] * scale, at.y(), size[1] * scale, solid);
  }

  /**
   * Whether a stretch of text in one layer of blocks across a face is clear: {@code width} along
   * the face from the middle, {@code height} up from {@code bottom}.
   */
  private static boolean clear(
      Position layer,
      Facing face,
      double middleX,
      double middleZ,
      double width,
      double bottom,
      double height,
      Predicate<Position> solid) {
    boolean alongX = face.dz != 0;
    double middle = alongX ? middleX : middleZ;
    int from = (int) Math.floor(middle - width / 2 + 1e-6);
    int to = (int) Math.floor(middle + width / 2 - 1e-6);
    int low = (int) Math.floor(bottom + 1e-6);
    int high = (int) Math.floor(bottom + height - 1e-6);
    for (int along = from; along <= to; along++) {
      for (int y = low; y <= high; y++) {
        Position at =
            alongX
                ? Position.of(layer.dimension(), along, y, layer.blockZ())
                : Position.of(layer.dimension(), layer.blockX(), y, along);
        if (solid.test(at)) {
          return false;
        }
      }
    }
    return true;
  }

  /** Whether text turning to face the viewer stays clear of blocks all the way round. */
  private static boolean turnsFreely(
      Position at, double[] size, double scale, Predicate<Position> solid) {
    double radius = size[0] * scale / 2;
    int x0 = (int) Math.floor(at.x() - radius + 1e-6);
    int x1 = (int) Math.floor(at.x() + radius - 1e-6);
    int z0 = (int) Math.floor(at.z() - radius + 1e-6);
    int z1 = (int) Math.floor(at.z() + radius - 1e-6);
    int y0 = (int) Math.floor(at.y() + 1e-6);
    int y1 = (int) Math.floor(at.y() + size[1] * scale - 1e-6);
    for (int x = x0; x <= x1; x++) {
      for (int y = y0; y <= y1; y++) {
        for (int z = z0; z <= z1; z++) {
          if (solid.test(Position.of(at.dimension(), x, y, z))) {
            return false;
          }
        }
      }
    }
    return true;
  }

  /**
   * A facing for an anchor without one (questions 346): the open side of its block, if it is on a
   * wall, else any side, that points most into the cell.
   */
  static Facing guess(
      Position block, Optional<Bounds> inside, Predicate<Position> solid, boolean open) {
    double cx = inside.map(b -> (b.minX() + b.maxX() + 1) / 2.0).orElse(block.blockX() + 0.5);
    double cz = inside.map(b -> (b.minZ() + b.maxZ() + 1) / 2.0).orElse(block.blockZ() + 0.5);
    Facing best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (Facing face : Facing.SIDES) {
      Position next = offset(block, face.dx, 0, face.dz);
      if (open && solid.test(next)) {
        continue;
      }
      double score =
          face.dx * (cx - (block.blockX() + 0.5)) + face.dz * (cz - (block.blockZ() + 0.5));
      if (inside.isPresent() && inside.get().contains(next)) {
        score += 1000;
      }
      if (score > bestScore) {
        bestScore = score;
        best = face;
      }
    }
    if (best != null) {
      return best;
    }
    // Every side is solid: the facing the block's yaw gives, as before.
    return fromYaw(block.yaw());
  }

  /** The side a yaw faces. */
  static Facing fromYaw(float yaw) {
    float normal = ((yaw % 360f) + 360f) % 360f;
    if (normal >= 45 && normal < 135) {
      return Facing.WEST;
    }
    if (normal >= 135 && normal < 225) {
      return Facing.NORTH;
    }
    if (normal >= 225 && normal < 315) {
      return Facing.EAST;
    }
    return Facing.SOUTH;
  }

  /**
   * A text's width and height in blocks at size 1, from its plain text: placeholders filled in,
   * tags dropped, long lines wrapped as a text display wraps them.
   */
  static double[] size(Message text) {
    String filled = text.template();
    for (Map.Entry<String, String> value : text.placeholders().entrySet()) {
      filled = filled.replace("<" + value.getKey() + ">", value.getValue());
    }
    filled = filled.replace("<newline>", "\n").replace("<br>", "\n");
    List<String> lines = new ArrayList<>();
    for (String line : filled.split("\n", -1)) {
      lines.add(line.replaceAll("<[^>]*>", ""));
    }
    int widest = 0;
    int rows = 0;
    for (String line : lines) {
      int pixels = line.length() * CHAR;
      widest = Math.max(widest, Math.min(pixels, WRAP));
      rows += Math.max(1, (pixels + WRAP - 1) / WRAP);
    }
    return new double[] {(widest + 2) * PIXEL, (rows * LINE + 2) * PIXEL};
  }

  /** A size as commands show it, such as {@code 0.6} or {@code 1.0}. */
  public static String text(double size) {
    return Double.toString(round(size));
  }

  private static double round(double size) {
    return Math.round(size * 100) / 100.0;
  }

  private static Position offset(Position block, int dx, int dy, int dz) {
    return Position.of(
        block.dimension(), block.blockX() + dx, block.blockY() + dy, block.blockZ() + dz);
  }

  private static Position withYaw(Position at, float yaw) {
    return new Position(at.dimension(), at.x(), at.y(), at.z(), yaw, 0f);
  }
}
