// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A zone an admin drew, as its season file holds it.
 *
 * @param id a slug, such as {@code peter-base}
 * @param dimension a namespaced dimension id, such as {@code minecraft:the_nether}
 * @param mode what it stops while it is locked
 * @param lockedUntil when it unlocks by itself; the manual command always works too
 * @param afterUnlock what it stops once unlocked: {@link ZoneMode#OFF} or {@link ZoneMode#NO_BUILD}
 * @param exempt boxes inside it that are never enforced, besides the Hall, which always is
 * @param message what a player is shown instead of the default, if set
 */
public record Zone(
    String id,
    String dimension,
    ZoneShape shape,
    ZoneMode mode,
    UnlockCondition lockedUntil,
    ZoneMode afterUnlock,
    List<ZoneShape.Box> exempt,
    Optional<String> message) {
  /** Lower-case letters, digits and dashes, starting with a letter or digit. */
  public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

  /** Words no zone may be called, because commands use them. */
  public static final String HOMECOMING = "homecoming";

  public Zone {
    exempt = List.copyOf(exempt);
  }

  /** A new zone as {@code /season zone add} makes it: blocked until Homecoming, then off. */
  public static Zone drawn(String id, String dimension, ZoneShape shape) {
    return new Zone(
        id,
        dimension,
        shape,
        ZoneMode.BLOCK,
        new UnlockCondition.Homecoming(),
        ZoneMode.OFF,
        List.of(),
        Optional.empty());
  }

  /** Whether an id may name a zone. */
  public static boolean validId(String id) {
    return ID.matcher(id).matches() && !id.equals(HOMECOMING);
  }

  /** Whether the block a position is in is inside, and not in one of the zone's own exemptions. */
  public boolean contains(Position at) {
    if (!at.dimension().equals(dimension)) {
      return false;
    }
    int x = at.blockX();
    int y = at.blockY();
    int z = at.blockZ();
    if (!shape.contains(x, y, z)) {
      return false;
    }
    for (ZoneShape.Box box : exempt) {
      if (box.contains(x, y, z)) {
        return false;
      }
    }
    return true;
  }

  /** As players read it: {@code old-world} is {@code old world}. */
  public String displayName() {
    return id.replace('-', ' ');
  }

  public Zone withMode(ZoneMode next) {
    return new Zone(id, dimension, shape, next, lockedUntil, afterUnlock, exempt, message);
  }

  public Zone withAfterUnlock(ZoneMode next) {
    return new Zone(id, dimension, shape, mode, lockedUntil, next, exempt, message);
  }
}
