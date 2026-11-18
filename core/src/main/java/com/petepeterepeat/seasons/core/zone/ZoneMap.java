// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The zones in force at one moment, each with the mode it has now, indexed by chunk so a lookup
 * usually checks only the zones that can reach the chunk. Built afresh whenever the files or the
 * season state change, and never changed; server thread only, since the index fills lazily.
 */
final class ZoneMap {
  /** No zones at all: before launch, or a season with none. */
  static final ZoneMap EMPTY = new ZoneMap(List.of(), at -> false);

  /**
   * One zone and what it stops now.
   *
   * @param locked whether it is locked: its own mode applies, rather than its after-unlock one
   */
  record Live(Zone zone, ZoneMode now, boolean locked) {}

  private final List<Live> zones;
  private final Predicate<Position> exempt;
  private final Map<String, Map<Long, List<Live>>> byChunk = new HashMap<>();

  /**
   * @param exempt positions no zone ever covers, such as the Hall
   */
  ZoneMap(List<Live> zones, Predicate<Position> exempt) {
    this.zones = List.copyOf(zones);
    this.exempt = exempt;
  }

  List<Live> zones() {
    return zones;
  }

  Optional<Live> find(String id) {
    return zones.stream().filter(live -> live.zone().id().equals(id)).findFirst();
  }

  /**
   * The zone with the strongest mode now that holds a position, leaving out zones that are off; or
   * empty where no zone applies.
   */
  Optional<Live> at(Position position) {
    if (zones.isEmpty()) {
      return Optional.empty();
    }
    Live strongest = null;
    for (Live live : near(position)) {
      if (live.now() != ZoneMode.OFF
          && (strongest == null || live.now().compareTo(strongest.now()) > 0)
          && live.zone().contains(position)) {
        strongest = live;
      }
    }
    if (strongest == null || exempt.test(position)) {
      return Optional.empty();
    }
    return Optional.of(strongest);
  }

  /** The zone that blocks a position now, if one does. */
  Optional<Live> blocking(Position position) {
    return at(position).filter(live -> live.now() == ZoneMode.BLOCK);
  }

  /** The zones whose shapes reach the chunk a position is in. */
  private List<Live> near(Position position) {
    int chunkX = position.blockX() >> 4;
    int chunkZ = position.blockZ() >> 4;
    long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    return byChunk
        .computeIfAbsent(position.dimension(), _ -> new HashMap<>())
        .computeIfAbsent(
            key,
            _ -> {
              List<Live> found = new ArrayList<>();
              for (Live live : zones) {
                ZoneShape shape = live.zone().shape();
                if (live.zone().dimension().equals(position.dimension())
                    && shape.maxX() >= chunkX << 4
                    && shape.minX() <= (chunkX << 4) + 15
                    && shape.maxZ() >= chunkZ << 4
                    && shape.minZ() <= (chunkZ << 4) + 15) {
                  found.add(live);
                }
              }
              return List.copyOf(found);
            });
  }
}
