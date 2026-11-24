// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The Hall's own parts (questions 243): from {@code hall.yml} the hub, the Hall spawn in it and the
 * Hall's exits; from {@code links.yml} the links and named points (M8).
 *
 * @param dimension where the hub and the Hall spawn are
 * @param links by id, in the order they were made
 * @param areas the Hall spawn as a box, and exits that are areas (questions 279)
 * @param points named spots and boxes anywhere, by name (M8)
 * @param frozen the links {@code hall.yml} held before {@code links.yml} existed, kept in it as
 *     they were so the previous Seasons still reads them, and never taken by this one once {@code
 *     links.yml} exists (M8, Upgrading existing setups)
 * @param upgrade how {@code links.yml} came about, if it was copied from {@code hall.yml}
 */
public record HallLayout(
    String dimension,
    Optional<Bounds> hub,
    Optional<Position> spawn,
    List<Position> exits,
    Map<String, Link> links,
    Gallery.Areas areas,
    Map<String, Point> points,
    Map<String, Link> frozen,
    Optional<Upgrade> upgrade) {
  public HallLayout(
      String dimension,
      Optional<Bounds> hub,
      Optional<Position> spawn,
      List<Position> exits,
      Map<String, Link> links) {
    this(dimension, hub, spawn, exits, links, Gallery.Areas.NONE);
  }

  public HallLayout(
      String dimension,
      Optional<Bounds> hub,
      Optional<Position> spawn,
      List<Position> exits,
      Map<String, Link> links,
      Gallery.Areas areas) {
    this(dimension, hub, spawn, exits, links, areas, Map.of(), Map.of(), Optional.empty());
  }

  public static final String OVERWORLD = "minecraft:overworld";

  /** No hub, no spawn, no links: a server that hasn't marked its Hall. */
  public static final HallLayout NONE =
      new HallLayout(OVERWORLD, Optional.empty(), Optional.empty(), List.of(), Map.of());

  public HallLayout {
    exits = List.copyOf(exits);
    links = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(links));
    points = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(points));
    frozen = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(frozen));
  }

  /**
   * How a block trigger fires (M8): {@code use} by a right-click only, {@code step} by standing in
   * or on the block (a pressure plate's press), {@code both} by either, as every block link did
   * before M8.
   */
  public enum BlockKind {
    USE,
    STEP,
    BOTH;

    public boolean clicked() {
      return this != STEP;
    }

    public boolean stepped() {
      return this != USE;
    }

    public String word() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Optional<BlockKind> of(String word) {
      for (BlockKind kind : values()) {
        if (kind.word().equals(word)) {
          return Optional.of(kind);
        }
      }
      return Optional.empty();
    }
  }

  /** How a player takes a link (M8). A link may have any number. */
  public sealed interface Trigger {
    /** The position the trigger is drawn and found at: its block, or its box's first corner. */
    Position at();

    /** A block: a button, a sign, a plate, any block. */
    record OnBlock(Position at, BlockKind kind) implements Trigger {}

    /** A box passed through: walking, falling or flying (questions 279). */
    record Area(Bounds box) implements Trigger {
      @Override
      public Position at() {
        return box.min();
      }
    }

    /**
     * Floating text with a hitbox: right-clicked, left-clicked with {@code left}, walked into
     * within {@code walk} blocks when above zero.
     */
    record Text(
        Position at, List<String> lines, double scale, String billboard, double walk, boolean left)
        implements Trigger {
      public Text {
        lines = List.copyOf(lines);
      }

      public Text withScale(double next) {
        return new Text(at, lines, next, billboard, walk, left);
      }

      public Text withLines(List<String> next) {
        return new Text(at, next, scale, billboard, walk, left);
      }
    }
  }

  /** Who may take a link (M8): everyone, ops, or holders of a permission node. */
  public record Access(Kind kind, Optional<String> node) {
    public enum Kind {
      EVERYONE,
      OP,
      PERMISSION
    }

    public static final Access EVERYONE = new Access(Kind.EVERYONE, Optional.empty());
    public static final Access OP = new Access(Kind.OP, Optional.empty());

    /** The node a {@code permission} link asks for: its own, or {@code seasons.link.use.<id>}. */
    public String node(String link) {
      return node.orElse("seasons.link.use." + link);
    }

    public String word() {
      return kind.name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * When a link may be taken (M8): {@code always}, {@code after-homecoming}, {@code stage
   * frontier|open}, or {@code played <season>}.
   */
  public record When(String kind, OptionalInt season, Optional<String> stage) {
    public static final When ALWAYS = new When("always", OptionalInt.empty(), Optional.empty());

    /** As the commands and links.yml write it. */
    public String text() {
      return switch (kind) {
        case "stage" -> "stage " + stage.orElse("open");
        case "played" -> "played " + season.orElse(0);
        default -> kind;
      };
    }

    /** Reads {@code text()}'s words back, or nothing if they aren't a condition. */
    public static Optional<When> parse(String text) {
      String[] words =
          com.petepeterepeat.seasons.core.command.Words.of(text).toArray(String[]::new);
      if (words.length == 0) {
        return Optional.empty();
      }
      return switch (words[0]) {
        case "always" -> words.length == 1 ? Optional.of(ALWAYS) : Optional.empty();
        case "after-homecoming" ->
            words.length == 1
                ? Optional.of(new When("after-homecoming", OptionalInt.empty(), Optional.empty()))
                : Optional.empty();
        case "stage" ->
            words.length == 2 && (words[1].equals("frontier") || words[1].equals("open"))
                ? Optional.of(new When("stage", OptionalInt.empty(), Optional.of(words[1])))
                : Optional.empty();
        case "played" -> {
          try {
            int season = words.length == 2 ? Integer.parseInt(words[1]) : 0;
            yield season >= 1
                ? Optional.of(new When("played", OptionalInt.of(season), Optional.empty()))
                : Optional.empty();
          } catch (NumberFormatException e) {
            yield Optional.empty();
          }
        }
        default -> Optional.empty();
      };
    }
  }

  /**
   * A link (questions 248, M8): one destination, any number of triggers.
   *
   * @param name what players read for it, MiniMessage
   * @param to where it leads; empty until set
   * @param landing where another link leading to this one lands; by default in front of its first
   *     trigger
   * @param pair the other half of a two-way link
   */
  public record Link(
      String id,
      Optional<String> name,
      Optional<Target> to,
      List<Trigger> triggers,
      Access access,
      When when,
      Optional<Position> landing,
      Optional<String> pair) {
    public Link {
      triggers = List.copyOf(triggers);
    }

    /** A link as 1.1 made them: one block, clicked or stood on, or one area. */
    public Link(String id, Position block, Optional<Target> to, Optional<Bounds> area) {
      this(
          id,
          Optional.empty(),
          to,
          area.isPresent()
              ? List.of(new Trigger.Area(area.get()))
              : List.of(new Trigger.OnBlock(block, BlockKind.BOTH)),
          Access.EVERYONE,
          When.ALWAYS,
          Optional.empty(),
          Optional.empty());
    }

    public Link(String id, Position block, Optional<Target> to) {
      this(id, block, to, Optional.empty());
    }

    /** A new link with nothing but its id, destination and triggers. */
    public static Link of(String id, Optional<Target> to, List<Trigger> triggers, Access access) {
      return new Link(
          id,
          Optional.empty(),
          to,
          triggers,
          access,
          When.ALWAYS,
          Optional.empty(),
          Optional.empty());
    }

    public Link withTo(Optional<Target> next) {
      return new Link(id, name, next, triggers, access, when, landing, pair);
    }

    public Link withTriggers(List<Trigger> next) {
      return new Link(id, name, to, next, access, when, landing, pair);
    }

    public Link withTrigger(Trigger next) {
      List<Trigger> all = new ArrayList<>(triggers);
      all.add(next);
      return withTriggers(all);
    }

    public Link withId(String next) {
      return new Link(next, name, to, triggers, access, when, landing, pair);
    }

    public Link withName(Optional<String> next) {
      return new Link(id, next, to, triggers, access, when, landing, pair);
    }

    public Link withAccess(Access next) {
      return new Link(id, name, to, triggers, next, when, landing, pair);
    }

    public Link withWhen(When next) {
      return new Link(id, name, to, triggers, access, next, landing, pair);
    }

    public Link withLanding(Optional<Position> next) {
      return new Link(id, name, to, triggers, access, when, next, pair);
    }

    public Link withPair(Optional<String> next) {
      return new Link(id, name, to, triggers, access, when, landing, next);
    }

    /**
     * Where the link is drawn and listed: its first block, else its first trigger, else its
     * landing; a command-only link with none of those has no place (see {@link #placed}).
     */
    public Position block() {
      for (Trigger trigger : triggers) {
        if (trigger instanceof Trigger.OnBlock on) {
          return on.at();
        }
      }
      if (!triggers.isEmpty()) {
        return triggers.getFirst().at();
      }
      return landing.orElse(Position.of(OVERWORLD, 0, 0, 0));
    }

    /** Whether the link has a place in the world at all: a trigger or a landing. */
    public boolean placed() {
      return !triggers.isEmpty() || landing.isPresent();
    }

    /** The first area, as 1.1's links had at most one. */
    public Optional<Bounds> area() {
      for (Trigger trigger : triggers) {
        if (trigger instanceof Trigger.Area area) {
          return Optional.of(area.box());
        }
      }
      return Optional.empty();
    }

    /** The block trigger at a position, if the link has one there. */
    public Optional<Trigger.OnBlock> blockAt(Position at) {
      for (Trigger trigger : triggers) {
        if (trigger instanceof Trigger.OnBlock on && sameBlock(on.at(), at)) {
          return Optional.of(on);
        }
      }
      return Optional.empty();
    }

    /** Whether a position is in one of the link's blocks (not its areas or texts). */
    public boolean isAt(Position at) {
      return blockAt(at).isPresent();
    }

    /** Whether the link is as 1.1 made them: one block or one area, nothing more. */
    public boolean simple() {
      return triggers.size() <= 1
          && name.isEmpty()
          && landing.isEmpty()
          && pair.isEmpty()
          && access.equals(Access.EVERYONE)
          && when.equals(When.ALWAYS)
          && triggers.stream().noneMatch(t -> t instanceof Trigger.Text);
    }
  }

  /** Whether two positions are the same block. */
  public static boolean sameBlock(Position a, Position b) {
    return a.dimension().equals(b.dimension())
        && a.blockX() == b.blockX()
        && a.blockY() == b.blockY()
        && a.blockZ() == b.blockZ();
  }

  /** A named spot or box anywhere (M8), a source or a destination. */
  public record Point(String name, Position at, Optional<Bounds> area) {}

  /**
   * How {@code links.yml} was copied from {@code hall.yml} (M8): when, whether an admin has
   * finished reviewing it with {@code /hof link upgrade done}, and each copied link's trigger as it
   * was ({@code both} or {@code area}) by id, for the report.
   */
  public record Upgrade(String at, boolean done, Map<String, String> before) {
    public Upgrade {
      before = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(before));
    }

    public Upgrade finished() {
      return new Upgrade(at, true, before);
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

    /** A named point (M8). */
    record AtPoint(String point) implements Target {}

    /** Another link's landing (M8). */
    record ToLink(String link) implements Target {}

    /** A gallery's entry (M8). */
    record ToGallery(String gallery) implements Target {}

    /** Back where the player came from, as {@code /hof leave} (M8). */
    record Return() implements Target {}
  }

  /** Whether a position is inside the hub. */
  public boolean inHub(Position position) {
    return hub.map(b -> b.contains(position)).orElse(false);
  }

  public Optional<Link> link(String id) {
    return Optional.ofNullable(links.get(id));
  }

  public Optional<Point> point(String name) {
    return Optional.ofNullable(points.get(name));
  }

  /** The link whose block a position is in. */
  public Optional<Link> linkAt(Position at) {
    return links.values().stream().filter(l -> l.isAt(at)).findFirst();
  }

  private HallLayout with(Map<String, Link> nextLinks, Map<String, Point> nextPoints) {
    return new HallLayout(
        dimension, hub, spawn, exits, nextLinks, areas, nextPoints, frozen, upgrade);
  }

  public HallLayout withHub(Bounds next) {
    return new HallLayout(
        next.dimension(), Optional.of(next), spawn, exits, links, areas, points, frozen, upgrade);
  }

  public HallLayout withSpawn(Position next) {
    return withSpawn(next, Optional.empty());
  }

  /** The Hall spawn, a block or a box from it (questions 279). */
  public HallLayout withSpawn(Position next, Optional<Bounds> area) {
    return new HallLayout(
        dimension,
        hub,
        Optional.of(next),
        exits,
        links,
        new Gallery.Areas(area, areas.exits()),
        points,
        frozen,
        upgrade);
  }

  /** An exit that is a box. */
  public HallLayout withExitArea(Bounds area) {
    List<Bounds> next = new ArrayList<>(areas.exits());
    next.add(area);
    return new HallLayout(
        dimension,
        hub,
        spawn,
        exits,
        links,
        new Gallery.Areas(areas.entry(), next),
        points,
        frozen,
        upgrade);
  }

  public HallLayout withExit(Position exit) {
    List<Position> next = new ArrayList<>(exits);
    next.add(exit);
    return new HallLayout(dimension, hub, spawn, next, links, areas, points, frozen, upgrade);
  }

  public HallLayout withoutExits() {
    return new HallLayout(
        dimension,
        hub,
        spawn,
        List.of(),
        links,
        new Gallery.Areas(areas.entry(), List.of()),
        points,
        frozen,
        upgrade);
  }

  /** Adds a link, or replaces the one with its id. */
  public HallLayout withLink(Link link) {
    Map<String, Link> next = new LinkedHashMap<>(links);
    next.put(link.id(), link);
    return with(next, points);
  }

  public HallLayout withoutLink(String id) {
    Map<String, Link> next = new LinkedHashMap<>(links);
    next.remove(id);
    return with(next, points);
  }

  /** Every link, replaced at once, such as by a rename that rewrites references. */
  public HallLayout withLinks(Map<String, Link> next) {
    return with(next, points);
  }

  public HallLayout withPoint(Point point) {
    Map<String, Point> next = new LinkedHashMap<>(points);
    next.put(point.name(), point);
    return with(links, next);
  }

  public HallLayout withoutPoint(String name) {
    Map<String, Point> next = new LinkedHashMap<>(points);
    next.remove(name);
    return with(links, next);
  }

  /** Every point, replaced at once, such as by a rename. */
  public HallLayout withPoints(Map<String, Point> next) {
    return with(links, next);
  }

  /** The frozen 1.1 links, replaced (or dropped, with none). */
  public HallLayout withFrozen(Map<String, Link> next) {
    return new HallLayout(dimension, hub, spawn, exits, links, areas, points, next, upgrade);
  }

  public HallLayout withUpgrade(Optional<Upgrade> next) {
    return new HallLayout(dimension, hub, spawn, exits, links, areas, points, frozen, next);
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

  /** Whether hall.yml's own parts (not links.yml's) are the same in two layouts. */
  public boolean hallPartsEqual(HallLayout other) {
    return dimension.equals(other.dimension)
        && hub.equals(other.hub)
        && spawn.equals(other.spawn)
        && exits.equals(other.exits)
        && areas.equals(other.areas)
        && frozen.equals(other.frozen);
  }
}
