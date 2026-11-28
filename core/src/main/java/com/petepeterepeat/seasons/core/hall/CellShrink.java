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
import com.petepeterepeat.seasons.core.port.Displays.Outline;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /hof cell shrink} and {@code /hof gallery shrink} (1.4.1 plan, H1; questions 343): the
 * faces of a cell's bounds that are walls moved in, so its bounds become the space inside, shown as
 * outlines until confirmed and undone with {@code /hof undo}. Merged into the {@code /hof} tree.
 */
public final class CellShrink {
  private static final int MAX = 4;

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final CellEdits edits;
  private final Clock clock;

  /** Shrinks waiting to be confirmed, by who asked (empty for the console). */
  private final Map<Optional<PlayerId>, Pending> waiting = new HashMap<>();

  /**
   * A shrink waiting to be confirmed.
   *
   * @param cells each cell's id with the bounds it had and the bounds it gets, in id order
   * @param one whether it was asked for one cell, which answers as {@code /hof cell bounds} does
   */
  private record Pending(
      String gallery, Map<String, Bounds[]> cells, boolean one, Instant expires) {}

  public CellShrink(
      Platform platform, ConfigHolder config, HallService hall, CellEdits edits, Clock clock) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.edits = edits;
    this.clock = clock;
  }

  public CommandNode root() {
    return CommandNode.literal("hof")
        .then(
            CommandNode.literal("cell")
                .permission(HallCommands.PERMISSION_ADMIN)
                .then(tree("cell", this::cell)))
        .then(
            CommandNode.literal("gallery")
                .permission(HallCommands.PERMISSION_ADMIN)
                .then(tree("gallery", this::gallery)))
        .build();
  }

  private CommandNode.Builder tree(
      String what, com.petepeterepeat.seasons.core.command.CommandAction run) {
    return CommandNode.literal("shrink")
        .then(CommandNode.literal("confirm").executes(this::confirm))
        .then(CommandNode.literal("cancel").executes(this::cancel))
        .then(
            CommandNode.argument(
                    what, what.equals("cell") ? ArgumentType.id() : ArgumentType.word())
                .executes(run)
                .then(
                    CommandNode.argument("options", ArgumentType.greedy())
                        .suggests((s, t) -> List.of("1", "2", "--all"))
                        .executes(run)));
  }

  /** The boxes waiting to be confirmed, as outlines for the admin who asked. */
  public List<Outline> previews(PlayerId player) {
    Pending pending = waiting.get(Optional.of(player));
    if (pending == null) {
      return List.of();
    }
    if (!clock.instant().isBefore(pending.expires())) {
      waiting.remove(Optional.of(player));
      return List.of();
    }
    List<Outline> boxes = new ArrayList<>();
    pending
        .cells()
        .forEach(
            (id, b) -> boxes.add(new Outline(b[1].min(), b[1].max(), "preview", Optional.of(id))));
    return boxes;
  }

  /** What follows the cell or gallery: how far, and whether every face goes. */
  private record Options(int by, boolean all) {}

  private Optional<Options> options(CommandSource source, Arguments arguments) {
    int by = 1;
    boolean all = false;
    for (String word : Words.of(arguments.optionalString("options").orElse(""))) {
      if (word.equals("--all")) {
        all = true;
        continue;
      }
      try {
        by = Integer.parseInt(word);
      } catch (NumberFormatException e) {
        by = 0;
      }
      if (by < 1 || by > MAX) {
        source.reply(messages().get("hof.shrink.usage"));
        return Optional.empty();
      }
    }
    return Optional.of(new Options(by, all));
  }

  /** What shrinking one cell came to: its new bounds, or why not. */
  private sealed interface Planned {
    record To(Bounds from, Bounds to, List<CellWalls.Face> faces) implements Planned {}

    record No(Message why) implements Planned {}

    /** Nothing about it looks like walls. */
    record Clear() implements Planned {}
  }

  private Planned plan(String key, Cell cell, Options options) {
    if (cell.bounds().isEmpty()) {
      return new Planned.No(messages().get("hof.check.cell-no-bounds", "cell", key));
    }
    Bounds from = cell.bounds().get();
    List<CellWalls.Face> faces;
    if (options.all()) {
      faces = List.of(CellWalls.Face.values());
    } else {
      if (!platform.world().isLoaded(from.min()) || !platform.world().isLoaded(from.max())) {
        return new Planned.No(messages().get("hof.shrink.not-loaded", "cell", key));
      }
      faces = hall.walls().walls(from);
      if (faces.size() < CellWalls.LOOKS_WALLED) {
        return new Planned.Clear();
      }
    }
    Bounds to;
    try {
      Set<CellWalls.Face> set = EnumSet.copyOf(faces);
      to = CellWalls.shrunk(from, set, options.by());
    } catch (IllegalArgumentException e) {
      return new Planned.No(messages().get("hof.shrink.too-small", "cell", key));
    }
    for (Anchor anchor : cell.anchors()) {
      if (!GalleryRules.fits(to, anchor)) {
        return new Planned.No(
            messages()
                .get(
                    "hof.shrink.anchor-outside",
                    "cell",
                    key,
                    "type",
                    anchor.type().id(),
                    "x",
                    Integer.toString(anchor.at().blockX()),
                    "y",
                    Integer.toString(anchor.at().blockY()),
                    "z",
                    Integer.toString(anchor.at().blockZ())));
      }
    }
    return new Planned.To(from, to, faces);
  }

  void cell(CommandSource source, Arguments arguments) {
    Optional<Options> options = options(source, arguments);
    if (options.isEmpty()) {
      return;
    }
    String name = arguments.string("cell");
    if (!(hall.resolve(name) instanceof HallService.Resolved.Found found)) {
      source.reply(messages().get("hof.no-cell", "cell", name));
      return;
    }
    HallService.CellRef ref = found.ref();
    switch (plan(ref.key(), ref.cell(), options.get())) {
      case Planned.No no -> source.reply(no.why());
      case Planned.Clear() ->
          source.reply(messages().get("hof.shrink.no-walls", "cell", ref.key()));
      case Planned.To to -> {
        Map<String, Bounds[]> cells = new LinkedHashMap<>();
        cells.put(ref.cell().id(), new Bounds[] {to.from(), to.to()});
        waiting.put(
            source.player(),
            new Pending(ref.gallery().id(), cells, true, clock.instant().plus(CellRows.PREVIEW)));
        source.reply(
            messages()
                .get(
                    "hof.shrink.preview",
                    "cell",
                    ref.key(),
                    "from",
                    CellEdits.corner(to.to().min()),
                    "to",
                    CellEdits.corner(to.to().max()),
                    "faces",
                    CellWalls.names(to.faces())));
      }
    }
  }

  void gallery(CommandSource source, Arguments arguments) {
    Optional<Options> options = options(source, arguments);
    if (options.isEmpty()) {
      return;
    }
    String id = arguments.string("gallery");
    Optional<Gallery> found = hall.gallery(id);
    if (found.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", id));
      return;
    }
    Gallery gallery = found.get();
    Map<String, Bounds[]> cells = new LinkedHashMap<>();
    for (Cell cell : gallery.cells()) {
      if (cell.bounds().isEmpty()) {
        continue;
      }
      switch (plan(id + ":" + cell.id(), cell, options.get())) {
        case Planned.No no -> {
          // The whole gallery or nothing, as a row is.
          source.reply(no.why());
          return;
        }
        case Planned.Clear() -> {}
        case Planned.To to -> cells.put(cell.id(), new Bounds[] {to.from(), to.to()});
      }
    }
    if (cells.isEmpty()) {
      source.reply(messages().get("hof.shrink.no-walls-gallery", "gallery", id));
      return;
    }
    waiting.put(
        source.player(), new Pending(id, cells, false, clock.instant().plus(CellRows.PREVIEW)));
    source.reply(
        messages()
            .get(
                cells.size() == 1 ? "hof.shrink.preview-gallery-one" : "hof.shrink.preview-gallery",
                "count",
                Integer.toString(cells.size()),
                "gallery",
                id,
                "cells",
                String.join(", ", cells.keySet())));
  }

  void confirm(CommandSource source, Arguments arguments) {
    Pending pending = waiting.remove(source.player());
    if (pending == null || !clock.instant().isBefore(pending.expires())) {
      source.reply(messages().get("hof.shrink.none"));
      return;
    }
    Optional<Gallery> found = hall.gallery(pending.gallery());
    if (found.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", pending.gallery()));
      return;
    }
    Gallery next = found.get();
    for (Map.Entry<String, Bounds[]> entry : pending.cells().entrySet()) {
      Optional<Cell> cell = next.cell(entry.getKey());
      if (cell.isEmpty() || !cell.get().bounds().equals(Optional.of(entry.getValue()[0]))) {
        source.reply(messages().get("hof.shrink.changed", "gallery", pending.gallery()));
        return;
      }
      next = next.withCell(cell.get().withBounds(entry.getValue()[1]));
    }
    if (edits.held(source, next)) {
      return;
    }
    Message done;
    if (pending.one()) {
      Map.Entry<String, Bounds[]> only = pending.cells().entrySet().iterator().next();
      done =
          messages()
              .get(
                  "hof.cell.bounds",
                  "cell",
                  pending.gallery() + ":" + only.getKey(),
                  "from",
                  CellEdits.corner(only.getValue()[1].min()),
                  "to",
                  CellEdits.corner(only.getValue()[1].max()));
    } else {
      done =
          messages()
              .get(
                  pending.cells().size() == 1
                      ? "hof.shrink.done-gallery-one"
                      : "hof.shrink.done-gallery",
                  "count",
                  Integer.toString(pending.cells().size()),
                  "gallery",
                  pending.gallery());
    }
    edits.saved(
        source,
        hall.save(next),
        done,
        () -> {
          hall.audited(
              actor(source),
              "cell-shrink",
              pending.gallery(),
              String.join(", ", pending.cells().keySet()));
          source.reply(messages().get("hof.undo.offer"));
        });
  }

  void cancel(CommandSource source, Arguments arguments) {
    waiting.remove(source.player());
    source.reply(messages().get("hof.shrink.cancelled"));
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
