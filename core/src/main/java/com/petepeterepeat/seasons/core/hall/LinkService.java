// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Hall links' rules (M8): where a link leads and lands, who may take it and when, which trigger a
 * block or a move is, and the upgrade that copies 1.1's links into {@code links.yml}. Runs on the
 * server thread.
 */
public final class LinkService {
  /** Ops by default: the access a link set to {@code op} asks for. */
  public static final String PERMISSION_OP = "seasons.link.op";

  /** Making, changing and removing links and points anywhere (M8); under seasons.admin.hof. */
  public static final String PERMISSION_ADMIN = "seasons.admin.links";

  /** Placing a link sign from {@code /hof link item} (M8). */
  public static final String PERMISSION_PLACE = "seasons.link.place";

  /** Styling sign and floating text through Seasons (M8). */
  public static final String PERMISSION_STYLE = "seasons.link.text.style";

  private static final int LANDING_REACH = 3;

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService halls;
  private final PlayerStore players;
  private final IntFunction<Optional<Position>> seasonSpawn;
  private final Clock clock;
  private Supplier<Optional<SeasonState>> state = Optional::empty;

  /** The trigger index, rebuilt when the layout changes. */
  private HallLayout indexed = HallLayout.NONE;

  private Map<String, List<Indexed>> byChunk = Map.of();
  private List<Indexed> areas = List.of();
  private List<Indexed> texts = List.of();

  public LinkService(
      Platform platform,
      ConfigHolder config,
      HallService halls,
      PlayerStore players,
      IntFunction<Optional<Position>> seasonSpawn,
      Clock clock) {
    this.platform = platform;
    this.config = config;
    this.halls = halls;
    this.players = players;
    this.seasonSpawn = seasonSpawn;
    this.clock = clock;
  }

  /** Connects the season state, once the season service exists. */
  public void bind(Supplier<Optional<SeasonState>> state) {
    this.state = state;
  }

  private SeasonsConfig.HallLinks settings() {
    return config.get().config().links();
  }

  private Messages messages() {
    return config.get().messages();
  }

  // What a block is

  /** Whether a block type is pressed by standing on it: pressure plates and tripwire. */
  public static boolean pressed(String type) {
    return type.endsWith("_pressure_plate")
        || type.equals("minecraft:tripwire")
        || type.equals("minecraft:tripwire_hook");
  }

  /** A new block trigger's kind (M8): pressed blocks fire underfoot; everything else is clicked. */
  public static HallLayout.BlockKind newKind(BlockInfo block) {
    return pressed(block.type()) ? HallLayout.BlockKind.STEP : HallLayout.BlockKind.USE;
  }

  /**
   * A 1.1 block link's kind after the upgrade (M8, Upgrading existing setups): blocks whose click
   * does something {@code use}; pressure plates, tripwire and any other block {@code both}, as they
   * fired before. {@code keepStepping} {@code plates-only} makes the last {@code use} too, and
   * {@code all} keeps every one {@code both}.
   */
  public static HallLayout.BlockKind upgradedKind(BlockInfo block, String keepStepping) {
    if (keepStepping.equals("all") || pressed(block.type())) {
      // A plate kept firing on a click in 1.1 too; harmless, so it is kept.
      return HallLayout.BlockKind.BOTH;
    }
    if (block.interactable() || keepStepping.equals("plates-only")) {
      return HallLayout.BlockKind.USE;
    }
    return HallLayout.BlockKind.BOTH;
  }

  // The upgrade

  /**
   * Copies 1.1's links from {@code hall.yml} into a new {@code links.yml}, once: only while there
   * is no {@code links.yml} and {@code hall.yml} has links. Each keeps its id, place and
   * destination, everyone may take it, and its block's kind follows {@link #upgradedKind}. {@code
   * hall.yml} is left exactly as it was. Returns how many links were copied, and how many became
   * click-only.
   */
  public java.util.concurrent.CompletableFuture<Optional<int[]>> upgrade() {
    HallLayout start = halls.layout();
    if (halls.linksFileExists() || start.frozen().isEmpty() || halls.hallLeftOut()) {
      return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }
    // Each link's block is read to tell a button from a floor tile, so its chunk is loaded first.
    List<java.util.concurrent.CompletableFuture<?>> loads = new ArrayList<>();
    for (HallLayout.Link old : start.frozen().values()) {
      for (HallLayout.Trigger trigger : old.triggers()) {
        if (trigger instanceof HallLayout.Trigger.OnBlock on) {
          loads.add(platform.world().loadChunk(on.at()));
        }
      }
    }
    return java.util.concurrent.CompletableFuture.allOf(
            loads.toArray(java.util.concurrent.CompletableFuture[]::new))
        .handleAsync((loaded, failure) -> copy(), platform.scheduler()::runGlobal);
  }

  private Optional<int[]> copy() {
    HallLayout layout = halls.layout();
    if (halls.linksFileExists() || layout.frozen().isEmpty() || halls.hallLeftOut()) {
      return Optional.empty();
    }
    String keepStepping = settings().upgrade().keepStepping();
    Map<String, HallLayout.Link> copied = new LinkedHashMap<>();
    Map<String, String> before = new LinkedHashMap<>();
    int clickOnly = 0;
    for (HallLayout.Link old : layout.frozen().values()) {
      List<HallLayout.Trigger> triggers = new ArrayList<>();
      for (HallLayout.Trigger trigger : old.triggers()) {
        if (trigger instanceof HallLayout.Trigger.OnBlock(Position at, HallLayout.BlockKind kind)) {
          HallLayout.BlockKind next = upgradedKind(platform.world().block(at), keepStepping);
          if (next == HallLayout.BlockKind.USE) {
            clickOnly++;
          }
          before.put(old.id(), kind.word());
          triggers.add(new HallLayout.Trigger.OnBlock(at, next));
        } else {
          before.put(old.id(), "area");
          triggers.add(trigger);
        }
      }
      copied.put(old.id(), old.withTriggers(triggers));
    }
    HallLayout next =
        layout
            .withLinks(copied)
            .withUpgrade(
                Optional.of(
                    new HallLayout.Upgrade(
                        clock.instant().toString(), false, before, fingerprint(layout.frozen()))));
    var _ = halls.saveHall(next);
    return Optional.of(new int[] {copied.size(), clickOnly});
  }

  /**
   * A fingerprint of hall.yml's 1.1 links: their ids, places and destinations, so a change made to
   * them since the copy (by the previous Seasons, after going back) is noticed.
   */
  public static String fingerprint(Map<String, HallLayout.Link> frozen) {
    return Integer.toHexString(frozen.toString().hashCode());
  }

  /** Whether hall.yml's 1.1 links changed since they were copied into links.yml. */
  public static boolean frozenChanged(HallLayout layout) {
    return layout.upgrade().isPresent()
        && !layout.frozen().isEmpty()
        && !layout.upgrade().get().frozen().equals(fingerprint(layout.frozen()));
  }

  /**
   * hall.yml's 1.1 links copied into links.yml again, replacing links of the same ids ({@code /hof
   * link upgrade import --replace}), with the kinds the upgrade gives their blocks.
   */
  public HallLayout recopy(HallLayout layout) {
    String keepStepping = settings().upgrade().keepStepping();
    HallLayout next = layout;
    for (HallLayout.Link old : layout.frozen().values()) {
      List<HallLayout.Trigger> triggers = new ArrayList<>();
      for (HallLayout.Trigger trigger : old.triggers()) {
        triggers.add(
            trigger instanceof HallLayout.Trigger.OnBlock(Position at, HallLayout.BlockKind kind)
                ? new HallLayout.Trigger.OnBlock(
                    at, upgradedKind(platform.world().block(at), keepStepping))
                : trigger);
      }
      next = next.withLink(old.withTriggers(triggers));
    }
    String print = fingerprint(layout.frozen());
    return next.withUpgrade(
        Optional.of(
            layout
                .upgrade()
                .map(u -> u.recopied(print))
                .orElse(
                    new HallLayout.Upgrade(clock.instant().toString(), false, Map.of(), print))));
  }

  // Where a link leads

  /** Where a link leads, as admins read it. */
  public String describe(HallLayout.Target to) {
    return describe(messages(), to);
  }

  /** Where a link leads, as admins read it. */
  public static String describe(Messages messages, HallLayout.Target to) {
    return switch (to) {
      case HallLayout.Target.Hall() -> messages.text("hof.link.to-hall");
      case HallLayout.Target.SeasonSpawn(int season) ->
          messages.text("hof.link.to-season", "season", Integer.toString(season));
      case HallLayout.Target.At(Position at) -> CellEdits.corner(at);
      case HallLayout.Target.AtPoint(String point) ->
          messages.text("hof.link.to-point", "point", point);
      case HallLayout.Target.ToLink(String link) ->
          messages.text("hof.link.to-link", "other", link);
      case HallLayout.Target.ToGallery(String gallery) ->
          messages.text("hof.link.to-gallery", "gallery", gallery);
      case HallLayout.Target.Return() -> messages.text("hof.link.to-return");
    };
  }

  /** A destination, or the message key saying why there is none. */
  public sealed interface Resolved {
    record There(Position at) implements Resolved {}

    record Hall() implements Resolved {}

    record Back() implements Resolved {}

    record Nowhere(String key, List<String> placeholders) implements Resolved {
      public Nowhere {
        placeholders = List.copyOf(placeholders);
      }
    }
  }

  /** Where a link's destination is now. */
  public Resolved resolve(HallLayout.Target to) {
    HallLayout layout = halls.layout();
    return switch (to) {
      case HallLayout.Target.Hall() -> new Resolved.Hall();
      case HallLayout.Target.Return() -> new Resolved.Back();
      case HallLayout.Target.SeasonSpawn(int season) ->
          seasonSpawn
              .apply(season)
              .<Resolved>map(Resolved.There::new)
              .orElse(
                  new Resolved.Nowhere(
                      "hof.link.no-spawn", List.of("season", Integer.toString(season))));
      case HallLayout.Target.At(Position at) -> new Resolved.There(HallService.standOn(at));
      case HallLayout.Target.AtPoint(String name) ->
          layout
              .point(name)
              .<Resolved>map(p -> new Resolved.There(halls.land(p.at(), p.area())))
              .orElse(new Resolved.Nowhere("hof.link.no-point", List.of("point", name)));
      case HallLayout.Target.ToLink(String id) ->
          layout
              .link(id)
              .filter(HallLayout.Link::placed)
              .<Resolved>map(l -> new Resolved.There(landing(l)))
              .orElse(new Resolved.Nowhere("hof.link.no-link", List.of("other", id)));
      case HallLayout.Target.ToGallery(String id) ->
          halls
              .gallery(id)
              .flatMap(halls::galleryEntry)
              .<Resolved>map(Resolved.There::new)
              .orElse(new Resolved.Nowhere("hof.link.no-gallery", List.of("gallery", id)));
    };
  }

  /**
   * Where a player arrives when a link leads to this one: its landing, else the free floor spot
   * nearest its first trigger that is in none of its triggers, else on its first trigger.
   */
  public Position landing(HallLayout.Link link) {
    if (link.landing().isPresent()) {
      return HallService.standOn(link.landing().get());
    }
    Position from = link.block();
    if (link.triggers().isEmpty()) {
      return HallService.standOn(from);
    }
    if (link.triggers().getFirst() instanceof HallLayout.Trigger.Area(Bounds box)) {
      from =
          Position.of(
              box.dimension(),
              Math.floorDiv(box.minX() + box.maxX(), 2),
              box.minY(),
              Math.floorDiv(box.minZ() + box.maxZ(), 2));
    }
    Position best = null;
    double bestDistance = Double.MAX_VALUE;
    int reach =
        LANDING_REACH
            + (link.triggers().getFirst() instanceof HallLayout.Trigger.Area(Bounds box)
                ? Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ())
                : 0);
    for (int dy : new int[] {0, -1, 1}) {
      for (int dx = -reach; dx <= reach; dx++) {
        for (int dz = -reach; dz <= reach; dz++) {
          if (dx == 0 && dz == 0 && dy == 0) {
            continue;
          }
          Position feet =
              Position.of(
                  from.dimension(), from.blockX() + dx, from.blockY() + dy, from.blockZ() + dz);
          if (!free(feet) || inTrigger(link, feet)) {
            continue;
          }
          double distance = Math.hypot(Math.hypot(dx, dz), dy * 2);
          if (distance < bestDistance) {
            best = feet;
            bestDistance = distance;
          }
        }
      }
    }
    Position spot = best != null ? best : from;
    return new Position(
        spot.dimension(), spot.blockX() + 0.5, spot.blockY(), spot.blockZ() + 0.5, 0f, 0f);
  }

  private boolean free(Position feet) {
    String d = feet.dimension();
    return platform
            .world()
            .block(Position.of(d, feet.blockX(), feet.blockY() - 1, feet.blockZ()))
            .solid()
        && platform.world().block(feet).passable()
        && platform
            .world()
            .block(Position.of(d, feet.blockX(), feet.blockY() + 1, feet.blockZ()))
            .passable();
  }

  /** Whether a feet position would fire one of a link's triggers. */
  private static boolean inTrigger(HallLayout.Link link, Position feet) {
    for (HallLayout.Trigger trigger : link.triggers()) {
      switch (trigger) {
        case HallLayout.Trigger.OnBlock(Position at, HallLayout.BlockKind kind) -> {
          if (HallLayout.sameBlock(at, feet)
              || HallLayout.sameBlock(
                  at,
                  Position.of(feet.dimension(), feet.blockX(), feet.blockY() - 1, feet.blockZ()))) {
            return true;
          }
        }
        case HallLayout.Trigger.Area(Bounds box) -> {
          if (box.contains(feet)) {
            return true;
          }
        }
        case HallLayout.Trigger.Text text -> {
          if (text.walk() > 0 && near(text.at(), feet, text.walk() + 1)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** How near a player must stand to a trigger to take its link with {@code /hof link go}. */
  public static final double GO_REACH = 6;

  /**
   * Whether a player standing at {@code feet} is at one of a link's triggers, within {@link
   * #GO_REACH} of a block or a floating text, or that near an area. A link with no trigger is
   * reached by nobody: only admins and {@code /hof link send} take it.
   */
  public static boolean reaches(HallLayout.Link link, Position feet) {
    for (HallLayout.Trigger trigger : link.triggers()) {
      boolean close =
          switch (trigger) {
            case HallLayout.Trigger.OnBlock(Position at, HallLayout.BlockKind kind) ->
                near(
                    Position.of(at.dimension(), at.blockX() + 0.5, at.blockY(), at.blockZ() + 0.5),
                    feet,
                    GO_REACH);
            case HallLayout.Trigger.Area(Bounds box) ->
                box.dimension().equals(feet.dimension())
                    && feet.x() >= box.minX() - GO_REACH
                    && feet.x() <= box.maxX() + 1 + GO_REACH
                    && feet.y() >= box.minY() - GO_REACH
                    && feet.y() <= box.maxY() + 1 + GO_REACH
                    && feet.z() >= box.minZ() - GO_REACH
                    && feet.z() <= box.maxZ() + 1 + GO_REACH;
            case HallLayout.Trigger.Text text -> near(text.at(), feet, GO_REACH);
          };
      if (close) {
        return true;
      }
    }
    return false;
  }

  private static boolean near(Position a, Position b, double distance) {
    return a.dimension().equals(b.dimension())
        && Math.hypot(Math.hypot(a.x() - b.x(), a.y() - b.y()), a.z() - b.z()) <= distance;
  }

  // Who may take a link

  /** The access a new link has by default, by where it leads (links.access in config.yml). */
  public HallLayout.Access defaultAccess(Optional<HallLayout.Target> to) {
    SeasonsConfig.HallLinks.Access access = settings().access();
    String word =
        switch (to.orElse(null)) {
          case HallLayout.Target.Hall h -> access.hall();
          case HallLayout.Target.SeasonSpawn s -> access.seasonSpawn();
          case null, default -> access.other();
        };
    return switch (word) {
      case "everyone" -> HallLayout.Access.EVERYONE;
      case "permission" ->
          new HallLayout.Access(HallLayout.Access.Kind.PERMISSION, Optional.empty());
      default -> HallLayout.Access.OP;
    };
  }

  /** Why a player may not take a link now, as a message key, if they may not. */
  public Optional<String> refused(PlayerId player, HallLayout.Link link) {
    boolean allowed =
        switch (link.access().kind()) {
          case EVERYONE -> true;
          case OP ->
              platform.permissions().has(player, PERMISSION_OP)
                  || platform.permissions().isOperator(player);
          case PERMISSION ->
              platform.permissions().has(player, link.access().node(link.id()))
                  // LuckPerms reads wildcards itself; Bukkit alone doesn't, so the default
                  // node's wildcard is asked for too.
                  || (link.access().node().isEmpty()
                      && platform.permissions().has(player, "seasons.link.use.*"));
        };
    if (!allowed) {
      return Optional.of("hof.link.not-allowed");
    }
    HallLayout.When when = link.when();
    Optional<SeasonState> now = state.get();
    boolean ok =
        switch (when.kind()) {
          case "after-homecoming" -> now.flatMap(SeasonState::homecomingAt).isPresent();
          case "stage" ->
              now.map(SeasonState::phase)
                  .filter(p -> p instanceof Phase.Active)
                  .map(p -> ((Phase.Active) p).stage())
                  .map(s ->
                      s
                          == (when.stage().orElse("open").equals("open")
                              ? Stage.OPEN
                              : Stage.FRONTIER))
                  .orElse(false);
          case "played" ->
              players
                  .find(player)
                  .map(PlayerRecord::seasons)
                  .map(s -> s.containsKey(when.season().orElse(0)))
                  .orElse(false);
          default -> true;
        };
    return ok ? Optional.empty() : Optional.of("hof.link.not-now");
  }

  // Finding triggers

  /** A trigger and its link. */
  public record Indexed(HallLayout.Link link, HallLayout.Trigger trigger) {}

  @SuppressWarnings("ReferenceEquality") // The very layout indexed, not an equal one.
  private void index() {
    HallLayout layout = halls.layout();
    if (layout == indexed) {
      return;
    }
    Map<String, List<Indexed>> chunks = new HashMap<>();
    List<Indexed> boxes = new ArrayList<>();
    List<Indexed> floating = new ArrayList<>();
    for (HallLayout.Link link : layout.links().values()) {
      for (HallLayout.Trigger trigger : link.triggers()) {
        switch (trigger) {
          case HallLayout.Trigger.OnBlock on ->
              chunks
                  .computeIfAbsent(key(on.at()), k -> new ArrayList<>())
                  .add(new Indexed(link, on));
          case HallLayout.Trigger.Area area -> boxes.add(new Indexed(link, area));
          case HallLayout.Trigger.Text text -> floating.add(new Indexed(link, text));
        }
      }
    }
    byChunk = chunks;
    areas = boxes;
    texts = floating;
    indexed = layout;
  }

  private static String key(Position at) {
    return at.dimension() + "/" + (at.blockX() >> 4) + "/" + (at.blockZ() >> 4);
  }

  /** The block trigger at a block, and its link. */
  public Optional<Indexed> blockAt(Position block) {
    index();
    for (Indexed found : byChunk.getOrDefault(key(block), List.of())) {
      if (HallLayout.sameBlock(found.trigger().at(), block)) {
        return Optional.of(found);
      }
    }
    return Optional.empty();
  }

  /**
   * The trigger a player standing at {@code feet} is in, as standing counts (questions 248): a
   * {@code step} or {@code both} block they stand in or on, or an area or walk-in text they are in.
   */
  public Optional<Indexed> standingIn(Position feet) {
    index();
    Position below = Position.of(feet.dimension(), feet.blockX(), feet.blockY() - 1, feet.blockZ());
    for (Position block : new Position[] {feet, below}) {
      Optional<Indexed> on = blockAt(block);
      if (on.isPresent()
          && on.get().trigger()
              instanceof HallLayout.Trigger.OnBlock(Position at, HallLayout.BlockKind kind)
          && kind.stepped()) {
        return on;
      }
    }
    for (Indexed area : areas) {
      if (((HallLayout.Trigger.Area) area.trigger()).box().contains(feet)) {
        return Optional.of(area);
      }
    }
    for (Indexed text : texts) {
      HallLayout.Trigger.Text t = (HallLayout.Trigger.Text) text.trigger();
      if (t.walk() > 0 && near(t.at(), feet, t.walk())) {
        return Optional.of(text);
      }
    }
    return Optional.empty();
  }

  /** Every area trigger, for crossings worked out on a move's line. */
  public List<Indexed> areas() {
    index();
    return areas;
  }

  /** Every floating text trigger. */
  public List<Indexed> texts() {
    index();
    return texts;
  }

  /** Now, for the arrival grace. */
  Instant now() {
    return clock.instant();
  }
}
