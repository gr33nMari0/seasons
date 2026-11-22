// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Words;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Displays.Outline;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /hof cell row} (questions 277): the selected cell repeated along a hallway, on one side or
 * both, shown as outlines until confirmed. Merged into the {@code /hof} tree.
 */
public final class CellRows {
  /** How long a row waits to be confirmed. */
  static final Duration PREVIEW = Duration.ofSeconds(60);

  private static final List<String> DIRECTIONS = List.of("+x", "-x", "+z", "-z");

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final CellEdits edits;
  private final Wand wand;
  private final Clock clock;
  private final CommandHelp help;
  private final Map<PlayerId, Row> waiting = new HashMap<>();

  /** A row waiting to be confirmed: its gallery and the cells it adds, in id order. */
  private record Row(String gallery, List<Cell> cells, Instant expires) {}

  public CellRows(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      CellEdits edits,
      Wand wand,
      Clock clock,
      CommandHelp help) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.edits = edits;
    this.wand = wand;
    this.clock = clock;
    this.help = help;
  }

  public CommandNode root() {
    return CommandNode.literal("hof")
        .then(
            CommandNode.literal("cell")
                .permission(HallCommands.PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("row")
                        .then(CommandNode.literal("confirm").executes(this::confirm))
                        .then(CommandNode.literal("cancel").executes(this::cancel))
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .then(
                                    CommandNode.argument("count", ArgumentType.integer(1, 64))
                                        .executes(this::row)
                                        .then(
                                            CommandNode.argument("options", ArgumentType.greedy())
                                                .suggests((s, t) -> rowWords(t))
                                                .executes(this::row))))))
        .build();
  }

  private static List<String> rowWords(String typed) {
    List<String> words = Words.of(typed);
    String last = words.isEmpty() || typed.endsWith(" ") ? "" : words.getLast();
    int done = words.size() - (last.isEmpty() ? 0 : 1);
    String before = done > 0 ? words.get(done - 1) : "";
    if (before.equals("--along") || before.equals("--across")) {
      return DIRECTIONS;
    }
    return List.of("--along", "--wall", "--across", "--like", "--from");
  }

  /** The boxes waiting to be confirmed, as outlines for the admin who asked. */
  public List<Outline> previews(PlayerId player) {
    Row row = waiting.get(player);
    if (row == null) {
      return List.of();
    }
    if (!clock.instant().isBefore(row.expires())) {
      waiting.remove(player);
      return List.of();
    }
    List<Outline> boxes = new ArrayList<>();
    for (Cell cell : row.cells()) {
      cell.bounds()
          .ifPresent(
              b -> boxes.add(new Outline(b.min(), b.max(), "preview", Optional.of(cell.id()))));
    }
    return boxes;
  }

  void row(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    PlayerId player = source.player().get();
    String id = arguments.string("gallery");
    Optional<Gallery> found = hall.gallery(id);
    if (found.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", id));
      return;
    }
    Gallery gallery = found.get();
    int count = arguments.integer("count");
    Optional<String> along = Optional.empty();
    int wall = 1;
    Optional<String> across = Optional.empty();
    int width = 0;
    Optional<Cell> like = Optional.empty();
    Optional<Bounds> from = wand.selection(player);
    List<String> words = Words.of(arguments.optionalString("options").orElse(""));
    try {
      for (int i = 0; i < words.size(); i++) {
        switch (words.get(i)) {
          case "--along" -> along = Optional.of(direction(words.get(++i)));
          case "--wall" -> {
            wall = Integer.parseInt(words.get(++i));
            if (wall < 0 || wall > 4) {
              throw new IllegalArgumentException();
            }
          }
          case "--across" -> {
            across = Optional.of(direction(words.get(++i)));
            width = Integer.parseInt(words.get(++i));
            if (width < 1 || width > 64) {
              throw new IllegalArgumentException();
            }
          }
          case "--like" -> {
            String cell = words.get(++i);
            like = gallery.cell(cell.contains(":") ? cell.substring(cell.indexOf(':') + 1) : cell);
            if (like.isEmpty()) {
              source.reply(messages().get("hof.no-cell", "cell", gallery.id() + ":" + cell));
              return;
            }
          }
          case "--from" -> {
            int[] c = new int[6];
            for (int k = 0; k < 6; k++) {
              c[k] = Integer.parseInt(words.get(++i));
            }
            from =
                Optional.of(
                    Bounds.between(gallery.dimension(), c[0], c[1], c[2], c[3], c[4], c[5]));
          }
          default -> throw new IllegalArgumentException();
        }
      }
    } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
      source.reply(messages().get("hof.row.usage"));
      return;
    }
    if (from.isEmpty()) {
      source.reply(messages().get("hof.row.no-selection"));
      return;
    }
    Bounds base = from.get();
    String axis = along.orElseGet(() -> awayFromHub(gallery));
    if (across.isPresent() && across.get().charAt(1) == axis.charAt(1)) {
      source.reply(messages().get("hof.row.same-axis"));
      return;
    }
    List<Bounds> boxes = new ArrayList<>();
    List<Boolean> far = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Bounds near = shift(base, axis, i * (length(base, axis) + wall));
      boxes.add(near);
      far.add(false);
      if (across.isPresent()) {
        boxes.add(shift(near, across.get(), length(base, across.get()) + width));
        far.add(true);
      }
    }
    // Check every cell first, as a stamp does: the whole row or nothing.
    List<Cell> planned = new ArrayList<>();
    List<String> taken = new ArrayList<>(gallery.cells().stream().map(Cell::id).toList());
    for (int i = 0; i < boxes.size(); i++) {
      Bounds box = boxes.get(i);
      Optional<Cell> same =
          gallery.cells().stream().filter(c -> c.bounds().equals(Optional.of(box))).findFirst();
      if (same.isPresent()) {
        // The selected cell itself, or one already tagged there: it stays as it is.
        continue;
      }
      if (gallery.bounds().isEmpty() || !gallery.bounds().get().contains(box)) {
        source.reply(
            messages()
                .get(
                    "hof.row.outside",
                    "from",
                    CellEdits.corner(box.min()),
                    "to",
                    CellEdits.corner(box.max()),
                    "gallery",
                    gallery.id()));
        return;
      }
      for (Cell other : gallery.cells()) {
        if (other.bounds().map(b -> b.overlaps(box)).orElse(false)) {
          source.reply(
              messages()
                  .get(
                      "hof.row.overlaps",
                      "from",
                      CellEdits.corner(box.min()),
                      "to",
                      CellEdits.corner(box.max()),
                      "cell",
                      gallery.id() + ":" + other.id()));
          return;
        }
      }
      String cellId = next(taken);
      taken.add(cellId);
      List<Anchor> anchors = new ArrayList<>();
      if (like.isPresent() && like.get().bounds().isPresent()) {
        Bounds source0 = like.get().bounds().get();
        for (Anchor anchor : like.get().anchors()) {
          anchors.add(copy(anchor, source0, box, far.get(i) ? across : Optional.empty()));
        }
      }
      planned.add(Cell.create(cellId).withBounds(box).withAnchors(anchors));
    }
    if (planned.isEmpty()) {
      source.reply(messages().get("hof.row.nothing"));
      return;
    }
    Gallery next = gallery;
    for (Cell cell : planned) {
      next = next.withCell(cell);
    }
    List<GalleryRules.Problem> problems = GalleryRules.check(next);
    if (!problems.isEmpty()) {
      source.reply(
          messages()
              .get(
                  "hof.fix.breaks",
                  "path",
                  problems.getFirst().path(),
                  "problem",
                  problems.getFirst().message()));
      return;
    }
    waiting.put(player, new Row(gallery.id(), planned, clock.instant().plus(PREVIEW)));
    source.reply(
        messages()
            .get(
                planned.size() == 1 ? "hof.row.preview-one" : "hof.row.preview",
                "count",
                Integer.toString(planned.size()),
                "gallery",
                gallery.id(),
                "cells",
                span(planned)));
  }

  void confirm(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = source.player();
    Row row = player.map(waiting::remove).orElse(null);
    if (row == null || !clock.instant().isBefore(row.expires())) {
      source.reply(messages().get("hof.row.none"));
      return;
    }
    Optional<Gallery> found = hall.gallery(row.gallery());
    if (found.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", row.gallery()));
      return;
    }
    Gallery next = found.get();
    for (Cell cell : row.cells()) {
      if (next.cell(cell.id()).isPresent()
          || next.cells().stream()
              .anyMatch(c ->
                  c.bounds().map(b -> b.overlaps(cell.bounds().orElseThrow())).orElse(false))) {
        // The gallery changed since the preview.
        source.reply(messages().get("hof.row.changed", "gallery", row.gallery()));
        return;
      }
      next = next.withCell(cell);
    }
    if (edits.held(source, next)) {
      return;
    }
    Gallery written = next;
    edits.saved(
        source,
        hall.save(written),
        messages()
            .get(
                "hof.row.added",
                "count",
                Integer.toString(row.cells().size()),
                "gallery",
                row.gallery(),
                "cells",
                span(row.cells())),
        () -> {
          hall.audited(actor(source), "cell-row", row.gallery(), span(row.cells()));
          source.reply(messages().get("hof.undo.offer"));
          help.after(source, "hof.cell.row", "gallery", row.gallery());
        });
  }

  void cancel(CommandSource source, Arguments arguments) {
    source.player().ifPresent(waiting::remove);
    source.reply(messages().get("hof.row.cancelled"));
  }

  private static String span(List<Cell> cells) {
    return cells.size() == 1
        ? cells.getFirst().id()
        : cells.getFirst().id() + " to " + cells.getLast().id();
  }

  /**
   * An anchor of the cell copied, placed by its offset from that cell's minimum corner, and on the
   * far side of the hallway reflected across it with its yaw turned to match.
   */
  private static Anchor copy(
      Anchor anchor, Bounds from, Bounds to, Optional<String> reflectedAcross) {
    Position at = anchor.at();
    double x = to.minX() + (at.x() - from.minX());
    double y = to.minY() + (at.y() - from.minY());
    double z = to.minZ() + (at.z() - from.minZ());
    float yaw = at.yaw();
    if (reflectedAcross.isPresent()) {
      if (reflectedAcross.get().endsWith("x")) {
        x = to.maxX() - (at.blockX() - from.minX()) + (at.x() - at.blockX());
        yaw = Wand.facing(-yaw);
      } else {
        z = to.maxZ() - (at.blockZ() - from.minZ()) + (at.z() - at.blockZ());
        yaw = Wand.facing(180f - yaw);
      }
    }
    return new Anchor(
        anchor.type(),
        new Position(at.dimension(), x, y, z, yaw, at.pitch()),
        anchor.page(),
        anchor.egg());
  }

  /** The direction along a gallery's longer axis that points away from the hub. */
  private String awayFromHub(Gallery gallery) {
    if (gallery.bounds().isEmpty()) {
      return "+x";
    }
    Bounds b = gallery.bounds().get();
    boolean alongX = (b.maxX() - b.minX()) >= (b.maxZ() - b.minZ());
    double centre = alongX ? (b.minX() + b.maxX()) / 2.0 : (b.minZ() + b.maxZ()) / 2.0;
    double hub =
        hall.layout()
            .hub()
            .map(h -> alongX ? (h.minX() + h.maxX()) / 2.0 : (h.minZ() + h.maxZ()) / 2.0)
            .orElse(centre - 1);
    String sign = centre >= hub ? "+" : "-";
    return sign + (alongX ? "x" : "z");
  }

  private static String direction(String word) {
    if (!DIRECTIONS.contains(word)) {
      throw new IllegalArgumentException(word);
    }
    return word;
  }

  private static int length(Bounds b, String direction) {
    return direction.endsWith("x") ? b.maxX() - b.minX() + 1 : b.maxZ() - b.minZ() + 1;
  }

  private static Bounds shift(Bounds b, String direction, int by) {
    int signed = direction.startsWith("-") ? -by : by;
    int dx = direction.endsWith("x") ? signed : 0;
    int dz = direction.endsWith("z") ? signed : 0;
    return new Bounds(
        b.dimension(),
        b.minX() + dx,
        b.minY(),
        b.minZ() + dz,
        b.maxX() + dx,
        b.maxY(),
        b.maxZ() + dz);
  }

  private static String next(List<String> taken) {
    for (int i = 1; ; i++) {
      String id = "c" + i;
      if (!taken.contains(id)) {
        return id;
      }
    }
  }

  private String actor(CommandSource source) {
    return source
        .player()
        .flatMap(p -> platform.players().find(p))
        .map(PlayerRef::name)
        .orElse("console");
  }

  private Messages messages() {
    return config.get().messages();
  }
}
