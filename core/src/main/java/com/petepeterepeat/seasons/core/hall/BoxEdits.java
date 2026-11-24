// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Words;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nudging a box's faces (M8, Nudging boxes): the hub, a gallery, a cell, a link's area, a point's
 * box or the selection, taken to edit by a left-click with the wand (the face clicked picked) or
 * {@code /hof sel edit}; sneak and scroll move the picked face by a step; {@code /hof sel apply}
 * writes it once, through the command that owns the box and its checks, which {@code /hof undo}
 * takes back. Until then only the wand's selection changes. Runs on the server thread.
 */
public final class BoxEdits {
  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final Wand wand;
  private @org.jspecify.annotations.Nullable HallCommands commands;

  /** What each admin is editing. */
  private final Map<PlayerId, Edit> editing = new HashMap<>();

  /** Each admin's step, an index into wand.nudge.steps. */
  private final Map<PlayerId, Integer> steps = new HashMap<>();

  /** What a box being edited belongs to. */
  sealed interface Owner {
    record Hub() implements Owner {}

    record OfGallery(String id) implements Owner {}

    record OfCell(String key) implements Owner {}

    record LinkArea(String link, int index) implements Owner {}

    record OfPoint(String name) implements Owner {}

    record Selected() implements Owner {}
  }

  private record Edit(Owner owner, Bounds original, Bounds current, String face) {
    Edit with(Bounds next, String nextFace) {
      return new Edit(owner, original, next, nextFace);
    }
  }

  public BoxEdits(Platform platform, ConfigHolder config, HallService hall, Wand wand) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.wand = wand;
  }

  /** Connects the Hall commands, which write the boxes they own. */
  public void bindCommands(HallCommands next) {
    this.commands = next;
  }

  /** Whether an admin is editing a box. */
  public boolean editing(PlayerId player) {
    return editing.containsKey(player);
  }

  /** The face being moved, as a flat box on that side of the edited box, for the outlines. */
  public List<com.petepeterepeat.seasons.core.port.Displays.Outline> outlines(PlayerId player) {
    Edit edit = editing.get(player);
    if (edit == null) {
      return List.of();
    }
    Bounds b = edit.current();
    int[] min = {b.minX(), b.minY(), b.minZ()};
    int[] max = {b.maxX(), b.maxY(), b.maxZ()};
    int axis = axis(edit.face());
    if (edit.face().startsWith("+")) {
      min[axis] = max[axis];
    } else {
      max[axis] = min[axis];
    }
    return List.of(
        new com.petepeterepeat.seasons.core.port.Displays.Outline(
            Position.of(b.dimension(), min[0], min[1], min[2]),
            Position.of(b.dimension(), max[0], max[1], max[2]),
            "face",
            Optional.of(edit.face())));
  }

  // The wand

  /** A left-click with the wand: the smallest box holding the block, with the face clicked. */
  public void pick(PlayerId player, Position block, String face) {
    Optional<Map.Entry<Owner, Bounds>> found = smallest(block);
    if (found.isEmpty()) {
      platform.players().actionBar(player, messages().get("hof.sel.nothing-here"));
      return;
    }
    Edit edit =
        new Edit(found.get().getKey(), found.get().getValue(), found.get().getValue(), sign(face));
    editing.put(player, edit);
    wand.setSelection(player, edit.current());
    platform.players().actionBar(player, line(player, edit));
  }

  /** Sneak and scroll: the picked face out (up) or in (down) by the step. */
  public void scroll(PlayerId player, boolean out) {
    Edit edit = editing.get(player);
    if (edit == null) {
      return;
    }
    int step = step(player);
    Optional<Bounds> next = nudge(edit.current(), edit.face(), out ? step : -step);
    if (next.isEmpty()) {
      platform.players().actionBar(player, messages().get("hof.sel.crossed"));
      return;
    }
    Edit moved = edit.with(next.get(), edit.face());
    editing.put(player, moved);
    wand.setSelection(player, moved.current());
    platform.players().actionBar(player, line(player, moved));
  }

  /** Drop (Q) with the wand: the next step of wand.nudge.steps. */
  public void cycleStep(PlayerId player) {
    List<Integer> all = config.get().config().wand().nudge().steps();
    steps.put(player, (steps.getOrDefault(player, 0) + 1) % Math.max(1, all.size()));
    platform
        .players()
        .actionBar(player, messages().get("hof.sel.step", "step", Integer.toString(step(player))));
  }

  private int step(PlayerId player) {
    List<Integer> all = config.get().config().wand().nudge().steps();
    if (all.isEmpty()) {
      return 1;
    }
    return all.get(Math.min(steps.getOrDefault(player, 0), all.size() - 1));
  }

  /** The box an admin is editing, described, if they are editing one. */
  public Optional<Message> describe(PlayerId player) {
    return Optional.ofNullable(editing.get(player)).map(e -> line(player, e));
  }

  // Commands

  /** The {@code /hof sel} words this class adds. */
  public CommandNode root() {
    return CommandNode.literal("hof")
        .then(
            CommandNode.literal("sel")
                .permission(HallCommands.PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("edit")
                        .then(
                            CommandNode.argument("what", ArgumentType.greedy())
                                .executes(this::editCommand)))
                .then(
                    CommandNode.literal("face")
                        .then(
                            CommandNode.argument("face", ArgumentType.word())
                                .executes(this::faceCommand)))
                .then(
                    CommandNode.literal("expand")
                        .then(
                            CommandNode.argument("rest", ArgumentType.greedy())
                                .executes((s, a) -> change(s, a, "expand"))))
                .then(
                    CommandNode.literal("contract")
                        .then(
                            CommandNode.argument("rest", ArgumentType.greedy())
                                .executes((s, a) -> change(s, a, "contract"))))
                .then(
                    CommandNode.literal("shift")
                        .then(
                            CommandNode.argument("rest", ArgumentType.greedy())
                                .executes((s, a) -> change(s, a, "shift"))))
                .then(
                    CommandNode.literal("step")
                        .executes(this::stepCommand)
                        .then(
                            CommandNode.argument("step", ArgumentType.integer(1, 64))
                                .executes(this::stepCommand)))
                .then(CommandNode.literal("apply").executes(this::apply))
                .then(CommandNode.literal("cancel").executes(this::cancel)))
        .build();
  }

  /** {@code /hof sel edit hub | gallery <id> | cell <cell> | link <id> [n] | point <name>}. */
  void editCommand(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = player(source);
    if (player.isEmpty()) {
      return;
    }
    List<String> words = Words.of(arguments.string("what"));
    String second = words.size() > 1 ? words.get(1) : "";
    HallLayout layout = hall.layout();
    Optional<Map.Entry<Owner, Bounds>> found =
        switch (words.isEmpty() ? "" : words.getFirst()) {
          case "hub" -> layout.hub().map(b -> Map.entry(new Owner.Hub(), b));
          case "gallery" ->
              hall.gallery(second)
                  .flatMap(Gallery::bounds)
                  .map(b -> Map.entry(new Owner.OfGallery(second), b));
          case "cell" ->
              hall.resolve(second) instanceof HallService.Resolved.Found f
                  ? f.ref().cell().bounds().map(b -> Map.entry(new Owner.OfCell(f.ref().key()), b))
                  : Optional.empty();
          case "link" -> linkArea(second, words.size() > 2 ? number(words.get(2)) : 1);
          case "point" ->
              layout
                  .point(second)
                  .flatMap(HallLayout.Point::area)
                  .map(b -> Map.entry(new Owner.OfPoint(second), b));
          default -> Optional.empty();
        };
    if (found.isEmpty()) {
      source.reply(messages().get("hof.sel.edit-usage"));
      return;
    }
    Edit edit =
        new Edit(found.get().getKey(), found.get().getValue(), found.get().getValue(), "+x");
    editing.put(player.get(), edit);
    wand.setSelection(player.get(), edit.current());
    source.reply(line(player.get(), edit));
  }

  private Optional<Map.Entry<Owner, Bounds>> linkArea(String id, int n) {
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      return Optional.empty();
    }
    int seen = 0;
    List<HallLayout.Trigger> triggers = link.get().triggers();
    for (int i = 0; i < triggers.size(); i++) {
      if (triggers.get(i) instanceof HallLayout.Trigger.Area(Bounds box) && ++seen == n) {
        return Optional.of(Map.entry(new Owner.LinkArea(id, i), box));
      }
    }
    return Optional.empty();
  }

  void faceCommand(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = player(source);
    if (player.isEmpty()) {
      return;
    }
    Edit edit = editing.get(player.get());
    Optional<String> face = face(arguments.string("face"), player.get());
    if (edit == null || face.isEmpty()) {
      source.reply(messages().get(edit == null ? "hof.sel.not-editing" : "hof.sel.bad-face"));
      return;
    }
    Edit next = edit.with(edit.current(), face.get());
    editing.put(player.get(), next);
    source.reply(line(player.get(), next));
  }

  void stepCommand(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = player(source);
    if (player.isEmpty()) {
      return;
    }
    if (arguments.values().containsKey("step")) {
      int wanted = arguments.integer("step");
      List<Integer> all = config.get().config().wand().nudge().steps();
      int index = all.indexOf(wanted);
      if (index < 0) {
        source.reply(messages().get("hof.sel.bad-step", "steps", all.toString()));
        return;
      }
      steps.put(player.get(), index);
    } else {
      cycleStep(player.get());
    }
    source.reply(messages().get("hof.sel.step", "step", Integer.toString(step(player.get()))));
  }

  /** {@code expand|contract <n> [face]} and {@code shift <n> [direction]}. */
  void change(CommandSource source, Arguments arguments, String how) {
    Optional<PlayerId> player = player(source);
    if (player.isEmpty()) {
      return;
    }
    List<String> words = Words.of(arguments.string("rest"));
    int n = words.isEmpty() ? -1 : number(words.getFirst());
    Edit edit = editing.get(player.get());
    Optional<Bounds> box =
        edit != null ? Optional.of(edit.current()) : wand.selection(player.get());
    if (n < 0 || box.isEmpty()) {
      source.reply(
          messages().get(box.isEmpty() ? "hof.wand.no-selection" : "hof.sel.change-usage"));
      return;
    }
    String defaultFace = edit != null ? edit.face() : "look";
    Optional<String> face = face(words.size() > 1 ? words.get(1) : defaultFace, player.get());
    if (face.isEmpty()) {
      source.reply(messages().get("hof.sel.bad-face"));
      return;
    }
    Optional<Bounds> next =
        switch (how) {
          case "expand" -> nudge(box.get(), face.get(), n);
          case "contract" -> nudge(box.get(), face.get(), -n);
          default -> Optional.of(shift(box.get(), face.get(), n));
        };
    if (next.isEmpty()) {
      source.reply(messages().get("hof.sel.crossed"));
      return;
    }
    wand.setSelection(player.get(), next.get());
    if (edit != null) {
      Edit moved = edit.with(next.get(), edit.face());
      editing.put(player.get(), moved);
      source.reply(line(player.get(), moved));
    } else {
      source.reply(selectionLine(next.get()));
    }
  }

  /** {@code /hof sel apply}: the edited box written through the command that owns it. */
  void apply(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = player(source);
    if (player.isEmpty()) {
      return;
    }
    Edit edit = editing.get(player.get());
    if (edit == null) {
      source.reply(messages().get("hof.sel.not-editing"));
      return;
    }
    HallCommands owner = commands;
    Bounds box = edit.current();
    wand.setSelection(player.get(), box);
    String corners = corners(box);
    switch (edit.owner()) {
      case Owner.Hub() -> {
        if (owner != null) {
          owner.hallHub(source, new Arguments(Map.of("corners", corners)));
        }
      }
      case Owner.OfGallery(String id) -> {
        if (owner != null) {
          owner.galleryBounds(source, new Arguments(Map.of("gallery", id, "corners", corners)));
        }
      }
      case Owner.OfCell(String key) -> {
        if (owner != null) {
          owner.cellBounds(source, new Arguments(Map.of("cell", key, "corners", corners)));
        }
      }
      case Owner.LinkArea(String id, int index) -> {
        Optional<HallLayout.Link> link = hall.layout().link(id);
        if (link.isEmpty() || index >= link.get().triggers().size()) {
          source.reply(messages().get("hof.link.unknown", "link", id));
          break;
        }
        try {
          GalleryFiles.checkArea(box, "area");
        } catch (GalleryFiles.Invalid e) {
          source.reply(
              messages().get("hof.area.too-big", "max", Integer.toString(GalleryFiles.MAX_AREA)));
          return;
        }
        List<HallLayout.Trigger> triggers = new ArrayList<>(link.get().triggers());
        triggers.set(index, new HallLayout.Trigger.Area(box));
        edits(
            source,
            hall.layout().withLink(link.get().withTriggers(triggers)),
            messages()
                .get(
                    "hof.sel.link-applied",
                    "link",
                    id,
                    "from",
                    CellEdits.corner(box.min()),
                    "to",
                    CellEdits.corner(box.max())));
      }
      case Owner.OfPoint(String name) -> {
        Optional<HallLayout.Point> point = hall.layout().point(name);
        if (point.isEmpty()) {
          source.reply(messages().get("hof.point.unknown", "point", name));
          break;
        }
        edits(
            source,
            hall.layout()
                .withPoint(
                    new HallLayout.Point(
                        name,
                        new Position(
                            box.dimension(),
                            box.minX(),
                            box.minY(),
                            box.minZ(),
                            point.get().at().yaw(),
                            0f),
                        Optional.of(box))),
            messages()
                .get(
                    "hof.point.added-area",
                    "point",
                    name,
                    "from",
                    CellEdits.corner(box.min()),
                    "to",
                    CellEdits.corner(box.max())));
      }
      case Owner.Selected() -> source.reply(selectionLine(box));
    }
    // Kept for /hof sel cancel while the owner's command refused it.
    if (applied(edit.owner(), box)) {
      editing.remove(player.get());
    }
  }

  /** Whether the box's owner holds it now. */
  private boolean applied(Owner owner, Bounds box) {
    HallLayout layout = hall.layout();
    return switch (owner) {
      case Owner.Hub() -> layout.hub().filter(box::equals).isPresent();
      case Owner.OfGallery(String id) ->
          hall.gallery(id).flatMap(Gallery::bounds).filter(box::equals).isPresent();
      case Owner.OfCell(String key) ->
          hall.resolve(key) instanceof HallService.Resolved.Found f
              && f.ref().cell().bounds().filter(box::equals).isPresent();
      case Owner.LinkArea(String id, int index) ->
          layout
              .link(id)
              .map(l -> l.triggers().contains(new HallLayout.Trigger.Area(box)))
              .orElse(true);
      case Owner.OfPoint(String name) ->
          layout.point(name).flatMap(HallLayout.Point::area).filter(box::equals).isPresent();
      case Owner.Selected() -> true;
    };
  }

  private void edits(CommandSource source, HallLayout next, Message reply) {
    if (hall.hallLeftOut()) {
      source.reply(messages().get("hof.hall.left-out"));
      return;
    }
    CellEdits cellEdits = new CellEdits(platform, config, hall);
    cellEdits.saved(source, hall.saveHall(next), reply);
  }

  /** {@code /hof sel cancel}: the box as it was, nothing written. */
  void cancel(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = player(source);
    if (player.isEmpty()) {
      return;
    }
    Edit edit = editing.remove(player.get());
    if (edit == null) {
      source.reply(messages().get("hof.sel.not-editing"));
      return;
    }
    wand.setSelection(player.get(), edit.original());
    source.reply(messages().get("hof.sel.cancelled", "what", what(edit.owner())));
  }

  // Geometry

  /**
   * The box with one face moved by {@code by} blocks outwards; empty if it would pass the other.
   */
  static Optional<Bounds> nudge(Bounds b, String face, int by) {
    int[] min = {b.minX(), b.minY(), b.minZ()};
    int[] max = {b.maxX(), b.maxY(), b.maxZ()};
    int axis = axis(face);
    if (face.startsWith("+")) {
      max[axis] += by;
    } else {
      min[axis] -= by;
    }
    if (min[axis] > max[axis]) {
      return Optional.empty();
    }
    return Optional.of(new Bounds(b.dimension(), min[0], min[1], min[2], max[0], max[1], max[2]));
  }

  /** The whole box moved {@code by} blocks towards a face. */
  static Bounds shift(Bounds b, String face, int by) {
    int sign = face.startsWith("+") ? by : -by;
    int axis = axis(face);
    int dx = axis == 0 ? sign : 0;
    int dy = axis == 1 ? sign : 0;
    int dz = axis == 2 ? sign : 0;
    return new Bounds(
        b.dimension(),
        b.minX() + dx,
        b.minY() + dy,
        b.minZ() + dz,
        b.maxX() + dx,
        b.maxY() + dy,
        b.maxZ() + dz);
  }

  private static int axis(String face) {
    return switch (face.charAt(1)) {
      case 'x' -> 0;
      case 'y' -> 1;
      default -> 2;
    };
  }

  /** A face from a word: +x and the like, up, down, a compass side, or relative to the facing. */
  private Optional<String> face(String word, PlayerId player) {
    float yaw = platform.players().position(player).yaw();
    float pitch = platform.players().position(player).pitch();
    String facing = horizontal(yaw);
    return switch (word) {
      case "+x", "-x", "+y", "-y", "+z", "-z" -> Optional.of(word);
      case "up" -> Optional.of("+y");
      case "down" -> Optional.of("-y");
      case "east" -> Optional.of("+x");
      case "west" -> Optional.of("-x");
      case "south" -> Optional.of("+z");
      case "north" -> Optional.of("-z");
      case "forward" -> Optional.of(facing);
      case "back" -> Optional.of(opposite(facing));
      case "right" -> Optional.of(horizontal(yaw + 90));
      case "left" -> Optional.of(horizontal(yaw - 90));
      case "look" -> Optional.of(pitch < -45 ? "+y" : pitch > 45 ? "-y" : facing);
      default -> Optional.empty();
    };
  }

  /** The horizontal face a yaw looks towards: south is 0, west 90, north 180, east 270. */
  private static String horizontal(float yaw) {
    int quarter = Math.floorMod(Math.round(yaw / 90f), 4);
    return switch (quarter) {
      case 0 -> "+z";
      case 1 -> "-x";
      case 2 -> "-z";
      default -> "+x";
    };
  }

  private static String opposite(String face) {
    return (face.startsWith("+") ? "-" : "+") + face.charAt(1);
  }

  /** A clicked block's face as a sign and an axis. */
  private static String sign(String face) {
    return switch (face) {
      case "down" -> "-y";
      case "north" -> "-z";
      case "south" -> "+z";
      case "east" -> "+x";
      case "west" -> "-x";
      default -> "+y";
    };
  }

  /** The smallest box holding a block: a cell, a link's area, a point, a gallery, the hub. */
  private Optional<Map.Entry<Owner, Bounds>> smallest(Position block) {
    List<Map.Entry<Owner, Bounds>> all = new ArrayList<>();
    for (Gallery gallery : hall.galleries()) {
      gallery.bounds().ifPresent(b -> all.add(Map.entry(new Owner.OfGallery(gallery.id()), b)));
      for (Cell cell : gallery.cells()) {
        cell.bounds()
            .ifPresent(
                b -> all.add(Map.entry(new Owner.OfCell(gallery.id() + ":" + cell.id()), b)));
      }
    }
    HallLayout layout = hall.layout();
    layout.hub().ifPresent(b -> all.add(Map.entry(new Owner.Hub(), b)));
    for (HallLayout.Link link : layout.links().values()) {
      for (int i = 0; i < link.triggers().size(); i++) {
        if (link.triggers().get(i) instanceof HallLayout.Trigger.Area(Bounds b)) {
          all.add(Map.entry(new Owner.LinkArea(link.id(), i), b));
        }
      }
    }
    for (HallLayout.Point point : layout.points().values()) {
      point.area().ifPresent(b -> all.add(Map.entry(new Owner.OfPoint(point.name()), b)));
    }
    Optional<Map.Entry<Owner, Bounds>> best =
        all.stream()
            .filter(e -> e.getValue().contains(block))
            .min(java.util.Comparator.comparingLong(e -> volume(e.getValue())));
    return best;
  }

  private static long volume(Bounds b) {
    return (long) (b.maxX() - b.minX() + 1) * (b.maxY() - b.minY() + 1) * (b.maxZ() - b.minZ() + 1);
  }

  private Message line(PlayerId player, Edit edit) {
    Bounds b = edit.current();
    return messages()
        .get(
            "hof.sel.editing",
            "what",
            what(edit.owner()),
            "from",
            CellEdits.corner(b.min()),
            "to",
            CellEdits.corner(b.max()),
            "size",
            size(b),
            "face",
            edit.face(),
            "step",
            Integer.toString(step(player)));
  }

  private Message selectionLine(Bounds b) {
    return messages()
        .get(
            "hof.sel.now",
            "from",
            CellEdits.corner(b.min()),
            "to",
            CellEdits.corner(b.max()),
            "size",
            size(b));
  }

  private static String size(Bounds b) {
    return (b.maxX() - b.minX() + 1)
        + " × "
        + (b.maxY() - b.minY() + 1)
        + " × "
        + (b.maxZ() - b.minZ() + 1);
  }

  private String what(Owner owner) {
    return switch (owner) {
      case Owner.Hub() -> messages().text("hof.sel.what-hub");
      case Owner.OfGallery(String id) -> messages().text("hof.sel.what-gallery", "id", id);
      case Owner.OfCell(String key) -> messages().text("hof.sel.what-cell", "id", key);
      case Owner.LinkArea(String id, int index) -> messages().text("hof.sel.what-link", "id", id);
      case Owner.OfPoint(String name) -> messages().text("hof.sel.what-point", "id", name);
      case Owner.Selected() -> messages().text("hof.sel.what-selection");
    };
  }

  private static String corners(Bounds b) {
    return b.minX() + " " + b.minY() + " " + b.minZ() + " " + b.maxX() + " " + b.maxY() + " "
        + b.maxZ();
  }

  private static int number(String word) {
    try {
      return Integer.parseInt(word);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private Optional<PlayerId> player(CommandSource source) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
    }
    return source.player();
  }

  private Messages messages() {
    return config.get().messages();
  }
}
