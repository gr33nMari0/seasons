// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Movement;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Whether a player may set off for the Hall from where they stand ({@code hof.entry}). Checked in a
 * fixed order, and the first that fails is the one the player hears about: the bed, hazards,
 * movement, monsters, then recent damage. Hazards come before movement and damage so standing in
 * lava says "lava", not "hurt".
 */
public final class EntryChecks {
  /** Vanilla's box for "you may not rest now, there are monsters nearby". */
  public static final double MONSTER_REACH = 8;

  public static final double MONSTER_HEIGHT = 5;

  /** Half a player's width, for the corners they can stand on. */
  private static final double HALF_WIDTH = 0.3;

  private EntryChecks() {}

  /**
   * What a check needs to know about the player and where they stand.
   *
   * @param ownBed the bed block of their respawn point, if it is a bed
   * @param hostiles monsters within {@link #MONSTER_REACH} across and {@link #MONSTER_HEIGHT} up
   *     and down
   * @param lastDamage when they last took damage, if Seasons has seen it
   */
  public record Situation(
      Position at,
      Movement movement,
      Function<Position, BlockInfo> blocks,
      Optional<Position> ownBed,
      List<Position> hostiles,
      Optional<Instant> lastDamage,
      Instant now) {}

  /**
   * A failed check, as the message key the player hears and its placeholders.
   *
   * @param key under {@code hof.entry}
   */
  public record Failure(String key, List<String> placeholders) {
    public Failure {
      placeholders = List.copyOf(placeholders);
    }

    Failure(String key, String... placeholders) {
      this(key, List.of(placeholders));
    }
  }

  public static Optional<Failure> check(SeasonsConfig.Hof.Entry rules, Situation s) {
    Optional<Failure> bed = bed(rules, s);
    if (bed.isPresent()) {
      return bed;
    }
    if (rules.noHazards()) {
      Optional<String> hazard = hazard(s.at(), s.blocks());
      if (hazard.isPresent()) {
        return Optional.of(new Failure(hazard.get()));
      }
      if (s.movement().burning()) {
        return Optional.of(new Failure("fire"));
      }
    }
    if (rules.onGround()) {
      Movement m = s.movement();
      if (m.riding()) {
        return Optional.of(new Failure("riding"));
      }
      if (m.gliding()) {
        return Optional.of(new Failure("gliding"));
      }
      if (m.swimming()) {
        return Optional.of(new Failure("swimming"));
      }
      if (!standing(s.at(), s.blocks())) {
        return Optional.of(new Failure("falling"));
      }
    }
    if (rules.noHostiles() && !s.hostiles().isEmpty()) {
      return Optional.of(new Failure("monsters"));
    }
    Duration quiet = rules.noDamageFor();
    if (quiet.isPositive()
        && s.lastDamage().isPresent()
        && s.now().isBefore(s.lastDamage().get().plus(quiet))) {
      return Optional.of(new Failure("damage", "seconds", Long.toString(quiet.toSeconds())));
    }
    return Optional.empty();
  }

  private static Optional<Failure> bed(SeasonsConfig.Hof.Entry rules, Situation s) {
    int reach = rules.bedDistance();
    String distance = Integer.toString(reach);
    if (!rules.anyBed()) {
      boolean near = s.ownBed().map(bed -> blockDistance(s.at(), bed) <= reach).orElse(false);
      return near ? Optional.empty() : Optional.of(new Failure("no-own-bed", "distance", distance));
    }
    for (int dx = -reach; dx <= reach; dx++) {
      for (int dy = -reach; dy <= reach; dy++) {
        for (int dz = -reach; dz <= reach; dz++) {
          Position block =
              Position.of(
                  s.at().dimension(),
                  s.at().blockX() + dx,
                  s.at().blockY() + dy,
                  s.at().blockZ() + dz);
          if (blockDistance(s.at(), block) <= reach && s.blocks().apply(block).bed()) {
            return Optional.empty();
          }
        }
      }
    }
    return Optional.of(new Failure("no-bed", "distance", distance));
  }

  /** Between the middles of the block a player stands in and another block. */
  static double blockDistance(Position at, Position block) {
    double dx = at.blockX() - block.blockX();
    double dy = at.blockY() - block.blockY();
    double dz = at.blockZ() - block.blockZ();
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /** Lava, fire or powder snow at a player's feet or head, as a message key. */
  static Optional<String> hazard(Position at, Function<Position, BlockInfo> blocks) {
    for (int up = 0; up <= 1; up++) {
      Optional<String> found =
          hazardOf(
              blocks
                  .apply(Position.of(at.dimension(), at.blockX(), at.blockY() + up, at.blockZ()))
                  .type());
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  static Optional<String> hazardOf(String type) {
    return switch (type) {
      case "minecraft:lava" -> Optional.of("lava");
      case "minecraft:fire", "minecraft:soul_fire" -> Optional.of("fire");
      case "minecraft:powder_snow" -> Optional.of("powder-snow");
      default -> Optional.empty();
    };
  }

  /**
   * Whether a player stands on something: a solid block under any corner of their feet. Worked out
   * from the world, not from what the client claims.
   */
  static boolean standing(Position at, Function<Position, BlockInfo> blocks) {
    int below = (int) Math.floor(at.y() - 0.05);
    for (double ox : new double[] {-HALF_WIDTH, HALF_WIDTH}) {
      for (double oz : new double[] {-HALF_WIDTH, HALF_WIDTH}) {
        Position under =
            Position.of(at.dimension(), Math.floor(at.x() + ox), below, Math.floor(at.z() + oz));
        if (blocks.apply(under).solid()) {
          return true;
        }
      }
    }
    return false;
  }
}
