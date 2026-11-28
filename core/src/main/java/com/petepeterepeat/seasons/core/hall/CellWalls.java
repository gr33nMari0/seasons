// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.World;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A cell's bounds are the space inside it (spec, Anchors), but a cell selected from the outside
 * takes in its walls, floor and roof, which its owner may then break while decorating (1.4.1 plan,
 * H1). This finds the faces of a cell's bounds that are walls: outer layers that are mostly solid
 * blocks (questions 341). It also keeps, for each cell, the walls it had when first asked during a
 * decorating window, so what an owner builds against a face never turns into a wall they can't take
 * down (questions 342). On the server thread.
 */
public final class CellWalls {
  /** The share of a face's blocks that must be solid for it to be a wall. */
  static final double WALL_SHARE = 0.6;

  /** How many walls make a cell look as if its bounds take in its walls. */
  static final int LOOKS_WALLED = 2;

  /** A face of a box, in the order they are listed. */
  public enum Face {
    FLOOR("floor"),
    ROOF("roof"),
    NORTH("north"),
    SOUTH("south"),
    WEST("west"),
    EAST("east");

    private final String id;

    Face(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }

    /** Whether a block lies in this face's outer layer of a box. */
    boolean holds(Bounds b, Position at) {
      return switch (this) {
        case FLOOR -> at.blockY() == b.minY();
        case ROOF -> at.blockY() == b.maxY();
        case NORTH -> at.blockZ() == b.minZ();
        case SOUTH -> at.blockZ() == b.maxZ();
        case WEST -> at.blockX() == b.minX();
        case EAST -> at.blockX() == b.maxX();
      };
    }

    /** How thick the box is across this face. */
    int depth(Bounds b) {
      return switch (this) {
        case FLOOR, ROOF -> b.maxY() - b.minY() + 1;
        case NORTH, SOUTH -> b.maxZ() - b.minZ() + 1;
        case WEST, EAST -> b.maxX() - b.minX() + 1;
      };
    }

    /** The box with this face moved in by {@code n}. */
    Bounds shrunk(Bounds b, int n) {
      return switch (this) {
        case FLOOR ->
            new Bounds(
                b.dimension(), b.minX(), b.minY() + n, b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        case ROOF ->
            new Bounds(
                b.dimension(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY() - n, b.maxZ());
        case NORTH ->
            new Bounds(
                b.dimension(), b.minX(), b.minY(), b.minZ() + n, b.maxX(), b.maxY(), b.maxZ());
        case SOUTH ->
            new Bounds(
                b.dimension(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ() - n);
        case WEST ->
            new Bounds(
                b.dimension(), b.minX() + n, b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        case EAST ->
            new Bounds(
                b.dimension(), b.minX(), b.minY(), b.minZ(), b.maxX() - n, b.maxY(), b.maxZ());
      };
    }
  }

  private final World world;

  /** Each cell's walls as first found in this decorating window, by cell key. */
  private final Map<String, Kept> kept = new HashMap<>();

  private record Kept(Bounds bounds, Set<Face> walls) {}

  public CellWalls(World world) {
    this.world = world;
  }

  /**
   * The faces of a box that are walls now, in {@link Face} order; empty if its chunks aren't
   * loaded. A face is a wall only where the box is at least three blocks thick across it, so moving
   * it in still leaves a space inside.
   */
  public List<Face> walls(Bounds b) {
    if (!world.isLoaded(b.min()) || !world.isLoaded(b.max())) {
      return List.of();
    }
    List<Face> walls = new ArrayList<>();
    for (Face face : Face.values()) {
      if (face.depth(b) >= 3 && solidShare(b, face) >= WALL_SHARE) {
        walls.add(face);
      }
    }
    return walls;
  }

  /** Whether a box looks as if it takes in its walls: two faces or more are walls. */
  public boolean looksWalled(Bounds b) {
    return walls(b).size() >= LOOKS_WALLED;
  }

  /**
   * Whether a block is in one of a cell's walls as they were when first asked in this decorating
   * window: its owner can't break it.
   */
  public boolean inWall(HallService.CellRef ref, Position block) {
    Optional<Bounds> bounds = ref.cell().bounds();
    if (bounds.isEmpty() || !bounds.get().contains(block)) {
      return false;
    }
    for (Face face : remember(ref)) {
      if (face.holds(bounds.get(), block)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The walls a cell had when first seen in this decorating window, seeing them now if they haven't
   * been: as the window opens, as the cell is handed out during it, and at its owner's first build
   * or break after a restart, before that change is made.
   */
  public Set<Face> remember(HallService.CellRef ref) {
    Optional<Bounds> bounds = ref.cell().bounds();
    if (bounds.isEmpty()) {
      return Set.of();
    }
    Kept known = kept.get(ref.key());
    if (known == null || !known.bounds().equals(bounds.get())) {
      if (!world.isLoaded(bounds.get().min()) || !world.isLoaded(bounds.get().max())) {
        return Set.of();
      }
      known = new Kept(bounds.get(), Set.copyOf(walls(bounds.get())));
      kept.put(ref.key(), known);
    }
    return known.walls();
  }

  /** Forgets the walls kept, as a decorating window opens. */
  public void forget() {
    kept.clear();
  }

  /** Lists faces as text, such as {@code floor, roof, north}. */
  public static String names(List<Face> faces) {
    return String.join(", ", faces.stream().map(Face::id).toList());
  }

  /** The box with each face given moved in by {@code n}. */
  public static Bounds shrunk(Bounds b, Set<Face> faces, int n) {
    Bounds next = b;
    for (Face face : EnumSet.copyOf(faces)) {
      next = face.shrunk(next, n);
    }
    return next;
  }

  private double solidShare(Bounds b, Face face) {
    int solid = 0;
    int all = 0;
    int x0 = face == Face.EAST ? b.maxX() : b.minX();
    int x1 = face == Face.WEST ? b.minX() : b.maxX();
    int y0 = face == Face.ROOF ? b.maxY() : b.minY();
    int y1 = face == Face.FLOOR ? b.minY() : b.maxY();
    int z0 = face == Face.SOUTH ? b.maxZ() : b.minZ();
    int z1 = face == Face.NORTH ? b.minZ() : b.maxZ();
    for (int x = x0; x <= x1; x++) {
      for (int y = y0; y <= y1; y++) {
        for (int z = z0; z <= z1; z++) {
          all++;
          if (world.block(Position.of(b.dimension(), x, y, z)).solid()) {
            solid++;
          }
        }
      }
    }
    return all == 0 ? 0 : (double) solid / all;
  }
}
