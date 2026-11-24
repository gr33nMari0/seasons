// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Displays.Outline;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The Hall's outlines for admins (questions 273 and 276): while an admin holds the wand, or after
 * {@code /hof show on}, the hub, galleries, cells, gates, entries, exits, links and their selection
 * nearby are drawn for them alone, and the action bar says what the wand's next selection does.
 * Runs on the server thread, twice a second.
 */
public final class HallOutlines {
  private static final Duration PASS = Duration.ofMillis(500);

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final Wand wand;
  private final Set<PlayerId> showing = new HashSet<>();
  private final Map<PlayerId, List<Outline>> shown = new HashMap<>();
  private Function<PlayerId, List<Outline>> previews = player -> List.of();
  private @Nullable ScheduledTask passes;

  public HallOutlines(Platform platform, ConfigHolder config, HallService hall, Wand wand) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.wand = wand;
  }

  /** Where a row's preview boxes come from (questions 277). */
  public void bindPreviews(Function<PlayerId, List<Outline>> next) {
    this.previews = next;
  }

  public void start() {
    passes = platform.scheduler().runGlobalRepeating(PASS, this::pass);
  }

  public void stop() {
    if (passes != null) {
      passes.cancel();
      passes = null;
    }
    for (PlayerId player : List.copyOf(shown.keySet())) {
      platform.displays().outline(player, List.of());
    }
    shown.clear();
  }

  /** {@code /hof show on|off}, until logout. */
  public void show(PlayerId player, boolean on) {
    if (on) {
      showing.add(player);
    } else {
      showing.remove(player);
    }
    pass(player);
  }

  /** Forgets a player who left; the adapter takes their outlines away. */
  public void left(PlayerId player) {
    showing.remove(player);
    shown.remove(player);
  }

  void pass() {
    for (PlayerRef ref : platform.players().online()) {
      pass(ref.id());
    }
  }

  private void pass(PlayerId player) {
    boolean admin = platform.permissions().has(player, HallCommands.PERMISSION_ADMIN);
    boolean holding = admin && platform.inventories().holdsWand(player);
    if (holding) {
      platform.players().actionBar(player, bar(player));
    }
    List<Outline> boxes =
        admin && (holding || showing.contains(player)) ? boxes(player) : List.of();
    List<Outline> before = shown.getOrDefault(player, List.of());
    if (!boxes.equals(before)) {
      platform.displays().outline(player, boxes);
      if (boxes.isEmpty()) {
        shown.remove(player);
      } else {
        shown.put(player, boxes);
      }
    }
  }

  private com.petepeterepeat.seasons.core.message.Message bar(PlayerId player) {
    Messages messages = config.get().messages();
    String mode =
        messages.text(wand.placeMode(player) ? "hof.wand.mode-place" : "hof.wand.mode-block");
    String what =
        wand.wandCell(player)
            .map(ref -> messages.text("hof.wand.bar-cell", "cell", ref.key()) + has(ref, messages))
            .orElseGet(() -> messages.text("hof.wand.bar-selection"));
    return messages.get("hof.wand.bar", "mode", mode, "what", what);
  }

  /** What a cell has and lacks, for the action bar (M8, Seeing anchors). */
  private static String has(HallService.CellRef ref, Messages messages) {
    Cell cell = ref.cell();
    java.util.function.Function<AnchorType, String> one =
        type -> cell.anchor(type).isPresent() ? "✓" : "✗";
    java.util.function.Function<AnchorType, String> many =
        type -> Long.toString(cell.anchors().stream().filter(a -> a.type() == type).count());
    return messages.text(
        "hof.wand.bar-anchors",
        "figure",
        one.apply(AnchorType.FIGURE),
        "plaque",
        one.apply(AnchorType.PLAQUE),
        "archive",
        many.apply(AnchorType.ARCHIVE),
        "trophy",
        many.apply(AnchorType.TROPHY),
        "entry",
        one.apply(AnchorType.ENTRY));
  }

  /** What an admin sees: every part of the Hall in range, nearest first, at most so many. */
  List<Outline> boxes(PlayerId player) {
    Position at = platform.players().position(player);
    List<Outline> all = new ArrayList<>();
    HallLayout layout = hall.layout();
    if (layout.dimension().equals(at.dimension())) {
      layout.hub().ifPresent(hub -> all.add(box(hub, "hub", Optional.of("hub"))));
      layout.spawn().ifPresent(spawn -> all.add(block(spawn, "entry", Optional.empty())));
      layout.exits().forEach(exit -> all.add(block(exit, "exit", Optional.empty())));
    }
    // Every link's triggers, landing and the named points (M8).
    for (HallLayout.Link link : layout.links().values()) {
      for (HallLayout.Trigger trigger : link.triggers()) {
        if (!trigger.at().dimension().equals(at.dimension())) {
          continue;
        }
        switch (trigger) {
          case HallLayout.Trigger.Area(Bounds b) -> all.add(box(b, "link", Optional.of(link.id())));
          case HallLayout.Trigger.OnBlock on ->
              all.add(block(on.at(), "link", Optional.of(link.id() + " · " + on.kind().word())));
          case HallLayout.Trigger.Text text ->
              all.add(block(text.at(), "link", Optional.of(link.id() + " · text")));
        }
      }
      link.landing()
          .filter(l -> l.dimension().equals(at.dimension()))
          .ifPresent(l -> all.add(block(l, "landing", Optional.of(link.id() + " lands"))));
    }
    for (HallLayout.Point point : layout.points().values()) {
      if (point.at().dimension().equals(at.dimension())) {
        all.add(
            point
                .area()
                .map(b -> box(b, "point", Optional.of(point.name())))
                .orElseGet(() -> block(point.at(), "point", Optional.of(point.name()))));
      }
    }
    Optional<String> focus = wand.wandCell(player).map(HallService.CellRef::key);
    for (Gallery gallery : hall.galleries()) {
      if (!gallery.dimension().equals(at.dimension())) {
        continue;
      }
      gallery.bounds().ifPresent(b -> all.add(box(b, "gallery", Optional.of(gallery.id()))));
      gallery.entry().ifPresent(entry -> all.add(block(entry, "entry", Optional.empty())));
      gallery.exits().forEach(exit -> all.add(block(exit, "exit", Optional.empty())));
      for (Cell cell : gallery.cells()) {
        String key = gallery.id() + ":" + cell.id();
        String kind = focus.filter(key::equals).isPresent() ? "focus" : "cell";
        boolean noFigure =
            gallery.kind() == Gallery.Kind.SEASON && cell.anchor(AnchorType.FIGURE).isEmpty();
        cell.bounds()
            .ifPresent(b ->
                all.add(
                    box(
                        b,
                        kind,
                        Optional.of(
                            noFigure
                                ? key + config.get().messages().text("hof.wand.no-figure")
                                : key))));
        cell.gate().ifPresent(g -> all.add(box(g, "gate", Optional.empty())));
        anchors(cell, at, all);
      }
    }
    Optional<Selection> selection = wand.rawSelection(player);
    if (selection.isPresent() && selection.get().dimension().equals(at.dimension())) {
      Optional<Bounds> whole = selection.get().bounds();
      if (whole.isPresent()) {
        Bounds b = whole.get();
        all.add(
            box(
                b,
                "selection",
                Optional.of(
                    (b.maxX() - b.minX() + 1)
                        + " × "
                        + (b.maxY() - b.minY() + 1)
                        + " × "
                        + (b.maxZ() - b.minZ() + 1))));
      } else {
        selection
            .get()
            .first()
            .or(() -> selection.get().second())
            .ifPresent(corner -> all.add(block(corner, "selection", Optional.of("1"))));
      }
    }
    all.addAll(previews.apply(player));
    var settings = config.get().config().wand().outlines();
    int range = settings.range();
    return all.stream()
        .filter(o -> distance(o, at) <= range)
        .sorted(Comparator.comparingDouble(o -> distance(o, at)))
        .limit(settings.max())
        .toList();
  }

  /**
   * Every anchor of a cell as a marker in its type's colour (M8, Seeing anchors), labelled within
   * wand.anchors.labelRange: a figure as the 1 × 2 box it stands in.
   */
  private void anchors(Cell cell, Position at, List<Outline> all) {
    var settings = config.get().config().wand().anchors();
    if (!settings.markers()) {
      cell.anchor(AnchorType.ENTRY)
          .ifPresent(entry -> all.add(block(entry.at(), "entry", Optional.empty())));
      return;
    }
    java.util.Map<AnchorType, Integer> counts = new java.util.EnumMap<>(AnchorType.class);
    for (Anchor anchor : cell.anchors()) {
      if (anchor.type() == AnchorType.GATE) {
        continue;
      }
      int n = counts.merge(anchor.type(), 1, Integer::sum);
      Position p = anchor.at();
      boolean near =
          Math.hypot(Math.hypot(p.x() + 0.5 - at.x(), p.y() - at.y()), p.z() + 0.5 - at.z())
              <= settings.labelRange();
      String kind =
          anchor.egg().isPresent()
              ? "egg"
              : switch (anchor.type()) {
                case FIGURE -> "figure";
                case ARCHIVE -> "archive";
                case PLAQUE -> "plaque";
                case TROPHY -> "trophy";
                default -> "entry";
              };
      Optional<String> label = near ? Optional.of(label(anchor, n)) : Optional.empty();
      if (anchor.area().isPresent()) {
        all.add(box(anchor.area().get(), kind, label));
      } else if (anchor.type() == AnchorType.FIGURE) {
        Position foot = Position.of(p.dimension(), p.blockX(), p.blockY(), p.blockZ());
        all.add(
            new Outline(
                foot,
                Position.of(p.dimension(), p.blockX(), p.blockY() + 1, p.blockZ()),
                kind,
                label));
      } else {
        all.add(block(p, kind, label));
      }
    }
  }

  /** An anchor's label: its type, and its page, number or size where it has one. */
  private static String label(Anchor anchor, int n) {
    String type = anchor.type().id();
    if (anchor.egg().isPresent()) {
      return "egg S" + anchor.egg().getAsInt();
    }
    return switch (anchor.type()) {
      case ARCHIVE ->
          anchor.page().isPresent() ? "archive p" + (anchor.page().getAsInt() + 1) : type + " " + n;
      case PLAQUE -> anchor.scale().isPresent() ? "plaque ×" + anchor.scale().getAsDouble() : type;
      case TROPHY -> "trophy " + n;
      default -> type;
    };
  }

  private static Outline box(Bounds b, String kind, Optional<String> label) {
    return new Outline(b.min(), b.max(), kind, label);
  }

  private static Outline block(Position at, String kind, Optional<String> label) {
    Position block = Position.of(at.dimension(), at.blockX(), at.blockY(), at.blockZ());
    return new Outline(block, block, kind, label);
  }

  /** How far a box is from a position: 0 inside it. */
  private static double distance(Outline o, Position at) {
    double dx = Math.max(0, Math.max(o.min().blockX() - at.x(), at.x() - (o.max().blockX() + 1)));
    double dy = Math.max(0, Math.max(o.min().blockY() - at.y(), at.y() - (o.max().blockY() + 1)));
    double dz = Math.max(0, Math.max(o.min().blockZ() - at.z(), at.z() - (o.max().blockZ() + 1)));
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }
}
