// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Words;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Putting cells right (questions 273 and 274): {@code /hof cell swap}, {@code move}, {@code rename}
 * and {@code redefine}. Merged into the {@code /hof} tree.
 */
public final class CellFixes {
  private static final String FORCE = "--force";

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final PlayerStore players;
  private final CellEdits edits;
  private final Wand wand;
  private final Reconciler reconciler;

  public CellFixes(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      PlayerStore players,
      CellEdits edits,
      Wand wand,
      Reconciler reconciler) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.players = players;
    this.edits = edits;
    this.wand = wand;
    this.reconciler = reconciler;
  }

  public CommandNode root() {
    return CommandNode.literal("hof")
        .then(
            CommandNode.literal("cell")
                .permission(HallCommands.PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("swap")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.argument("other", ArgumentType.id())
                                        .executes(this::swap)
                                        .then(
                                            CommandNode.argument("options", ArgumentType.greedy())
                                                .suggests((s, t) -> List.of(FORCE))
                                                .executes(this::swap)))))
                .then(
                    CommandNode.literal("move")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.argument("offset", ArgumentType.greedy())
                                        .executes(this::move))))
                .then(
                    CommandNode.literal("rename")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.argument("id", ArgumentType.word())
                                        .executes(this::rename))))
                .then(
                    CommandNode.literal("redefine")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .executes(this::redefine)
                                .then(
                                    CommandNode.argument("options", ArgumentType.greedy())
                                        .suggests((s, t) -> List.of(FORCE))
                                        .executes(this::redefine)))))
        .build();
  }

  void swap(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> a = cell(source, arguments.string("cell"));
    Optional<HallService.CellRef> b =
        a.isEmpty() ? Optional.empty() : cell(source, arguments.string("other"));
    if (b.isEmpty()) {
      return;
    }
    if (!a.get().gallery().id().equals(b.get().gallery().id())) {
      source.reply(messages().get("hof.fix.other-gallery"));
      return;
    }
    if (a.get().cell().id().equals(b.get().cell().id())) {
      source.reply(messages().get("hof.fix.same"));
      return;
    }
    boolean force = Words.of(arguments.optionalString("options").orElse("")).contains(FORCE);
    if (!force && (refusedForOwner(source, a.get()) || refusedForOwner(source, b.get()))) {
      return;
    }
    Gallery gallery = a.get().gallery();
    if (edits.held(source, gallery)) {
      return;
    }
    Cell one = a.get().cell();
    Cell two = b.get().cell();
    Cell nextOne = place(one, two);
    Cell nextTwo = place(two, one);
    Gallery next = gallery.withCell(nextOne).withCell(nextTwo);
    save(
        source,
        next,
        "hof.fix.swapped",
        "swap",
        a.get().key() + " " + b.get().key(),
        "cell",
        a.get().key(),
        "other",
        b.get().key());
  }

  /** A cell with another's place: its bounds, anchors and gate. */
  private static Cell place(Cell cell, Cell from) {
    return new Cell(
        cell.id(),
        from.bounds(),
        from.anchors(),
        cell.owners(),
        cell.imported(),
        cell.pose(),
        cell.gateShut());
  }

  void move(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments.string("cell"));
    if (found.isEmpty()) {
      return;
    }
    List<String> words = new ArrayList<>(Words.of(arguments.string("offset")));
    boolean force = words.remove(FORCE);
    int[] by = new int[3];
    try {
      if (words.size() != 3) {
        throw new NumberFormatException();
      }
      for (int i = 0; i < 3; i++) {
        by[i] = Integer.parseInt(words.get(i));
      }
    } catch (NumberFormatException e) {
      source.reply(messages().get("hof.fix.offset"));
      return;
    }
    HallService.CellRef ref = found.get();
    if (ref.cell().bounds().isEmpty()) {
      source.reply(messages().get("hof.cell.anchor-no-bounds", "cell", ref.key()));
      return;
    }
    if (!force && refusedForOwner(source, ref)) {
      return;
    }
    Gallery gallery = ref.gallery();
    if (edits.held(source, gallery)) {
      return;
    }
    Bounds b = ref.cell().bounds().get();
    Bounds moved =
        new Bounds(
            b.dimension(),
            b.minX() + by[0],
            b.minY() + by[1],
            b.minZ() + by[2],
            b.maxX() + by[0],
            b.maxY() + by[1],
            b.maxZ() + by[2]);
    if (gallery.bounds().isEmpty() || !gallery.bounds().get().contains(moved)) {
      source.reply(messages().get("hof.fix.outside", "cell", ref.key(), "gallery", gallery.id()));
      return;
    }
    List<Anchor> anchors = new ArrayList<>();
    for (Anchor anchor : ref.cell().anchors()) {
      Position at = anchor.at();
      anchors.add(
          new Anchor(
              anchor.type(),
              new Position(
                  at.dimension(),
                  at.x() + by[0],
                  at.y() + by[1],
                  at.z() + by[2],
                  at.yaw(),
                  at.pitch()),
              anchor.page(),
              anchor.egg()));
    }
    Cell cell = ref.cell();
    Cell next =
        new Cell(
            cell.id(),
            Optional.of(moved),
            anchors,
            cell.owners(),
            cell.imported(),
            cell.pose(),
            cell.gateShut());
    save(
        source,
        gallery.withCell(next),
        "hof.fix.moved",
        "move",
        ref.key(),
        "cell",
        ref.key(),
        "by",
        by[0] + ", " + by[1] + ", " + by[2]);
  }

  void rename(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments.string("cell"));
    if (found.isEmpty()) {
      return;
    }
    String id = arguments.string("id");
    HallService.CellRef ref = found.get();
    Gallery gallery = ref.gallery();
    if (!GalleryFiles.ID.matcher(id).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    if (gallery.cell(id).isPresent()) {
      source.reply(messages().get("hof.cell.exists", "cell", gallery.id() + ":" + id));
      return;
    }
    if (edits.held(source, gallery)) {
      return;
    }
    Cell cell = ref.cell();
    Cell renamed =
        new Cell(
            id,
            cell.bounds(),
            cell.anchors(),
            cell.owners(),
            cell.imported(),
            cell.pose(),
            cell.gateShut());
    // In its place in the file, so ordered assignment's order stays.
    List<Cell> cells = new ArrayList<>();
    for (Cell each : gallery.cells()) {
      cells.add(each.id().equals(cell.id()) ? renamed : each);
    }
    Gallery next =
        new Gallery(
            gallery.id(),
            gallery.kind(),
            gallery.season(),
            gallery.dimension(),
            gallery.bounds(),
            gallery.entry(),
            gallery.exits(),
            cells,
            gallery.settings(),
            gallery.launch());
    save(
        source,
        next,
        "hof.fix.renamed",
        "rename",
        ref.key(),
        "cell",
        ref.key(),
        "to",
        gallery.id() + ":" + id);
  }

  void redefine(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    Optional<HallService.CellRef> found = cell(source, arguments.string("cell"));
    if (found.isEmpty()) {
      return;
    }
    boolean force = Words.of(arguments.optionalString("options").orElse("")).contains(FORCE);
    if (!force && refusedForOwner(source, found.get())) {
      return;
    }
    wand.redefine(source.player().get(), found.get());
    source.reply(messages().get("hof.fix.redefine", "cell", found.get().key()));
  }

  /**
   * Whether a cell's owner stops a change without {@code --force}: archived into it, or decorating
   * it, since their figure and decoration stay where they are (questions 274). Says why.
   */
  private boolean refusedForOwner(CommandSource source, HallService.CellRef ref) {
    for (Map.Entry<Integer, PlayerId> owner : ref.cell().owners().entrySet()) {
      Optional<PlayerRecord> record = players.find(owner.getValue());
      if (ref.cell().imported()
          || record.map(r -> r.archivedThrough() >= owner.getKey()).orElse(false)) {
        source.reply(
            messages()
                .get(
                    "hof.fix.archived",
                    "cell",
                    ref.key(),
                    "name",
                    name(owner.getValue()),
                    "season",
                    Integer.toString(owner.getKey())));
        return true;
      }
    }
    return false;
  }

  private void save(
      CommandSource source,
      Gallery next,
      String key,
      String action,
      String target,
      String... placeholders) {
    List<GalleryRules.Problem> problems = GalleryRules.check(next);
    if (!problems.isEmpty()) {
      GalleryRules.Problem first = problems.getFirst();
      source.reply(
          messages().get("hof.fix.breaks", "path", first.path(), "problem", first.message()));
      return;
    }
    edits.saved(
        source,
        hall.save(next),
        messages().get(key, placeholders),
        () -> {
          hall.audited(actor(source), "cell-" + action, target, "");
          source.reply(messages().get("hof.undo.offer"));
          // Displays follow their cells (questions 274).
          hall.gallery(next.id())
              .ifPresent(g -> {
                var _ = reconciler.reconcile(g);
              });
        });
  }

  private Optional<HallService.CellRef> cell(CommandSource source, String name) {
    return switch (hall.resolve(name)) {
      case HallService.Resolved.Found found -> Optional.of(found.ref());
      case HallService.Resolved.NotFound notFound -> {
        source.reply(messages().get("hof.no-cell", "cell", name));
        yield Optional.empty();
      }
      case HallService.Resolved.Ambiguous ambiguous -> {
        source.reply(messages().get("hof.ambiguous-cell", "cell", name));
        yield Optional.empty();
      }
    };
  }

  private String name(PlayerId id) {
    return players
        .find(id)
        .map(PlayerRecord::lastName)
        .or(() -> platform.players().knownName(id))
        .orElse(id.toString());
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
