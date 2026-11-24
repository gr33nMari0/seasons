// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Positions;
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
 * Moving anchors (M8, Moving anchors by dragging): the wand's menu on an anchor picks it up; sneak
 * and scroll turn it; a right-click puts it down where 227's rule puts a new one, inside its own
 * cell, as one edit {@code /hof undo} takes back; a left-click puts it back. And the same by
 * command: {@code /hof cell anchors}, {@code /hof cell anchor move}. Runs on the server thread.
 */
public final class AnchorDrag {
  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final CellEdits edits;

  /** The anchor each admin holds: its cell, the anchor as it was, and how far it is turned. */
  private final Map<PlayerId, Held> held = new HashMap<>();

  private record Held(String cell, Anchor anchor, float turn) {
    Held turned(float by) {
      return new Held(cell, anchor, turn + by);
    }
  }

  public AnchorDrag(Platform platform, ConfigHolder config, HallService hall, CellEdits edits) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.edits = edits;
  }

  /** Whether an admin holds an anchor. */
  public boolean holding(PlayerId player) {
    return held.containsKey(player);
  }

  /**
   * Where the held anchor would go now, as a ghost for the outlines: the block looked at, or the
   * block above it for anchors that stand, white where it may go and red where it may not.
   */
  public List<com.petepeterepeat.seasons.core.port.Displays.Outline> outlines(PlayerId player) {
    Held now = held.get(player);
    if (now == null) {
      return List.of();
    }
    Optional<Position> looked = platform.players().targetBlock(player, 8);
    if (looked.isEmpty()) {
      return List.of();
    }
    AnchorType type = now.anchor().type();
    Position spot =
        type == AnchorType.ARCHIVE || type == AnchorType.PLAQUE
            ? looked.get()
            : Wand.offset(looked.get(), "up");
    boolean fits =
        hall.resolve(now.cell()) instanceof HallService.Resolved.Found found
            && found.ref().cell().bounds().map(b -> b.contains(spot)).orElse(false);
    Position top =
        Position.of(
            spot.dimension(),
            spot.blockX(),
            spot.blockY() + (type == AnchorType.FIGURE ? 1 : 0),
            spot.blockZ());
    return List.of(
        new com.petepeterepeat.seasons.core.port.Displays.Outline(
            Position.of(spot.dimension(), spot.blockX(), spot.blockY(), spot.blockZ()),
            top,
            fits ? "ghost" : "bad",
            Optional.of(type.id())));
  }

  /** Picks an anchor up. */
  public void pickUp(PlayerId player, HallService.CellRef ref, Anchor anchor) {
    held.put(player, new Held(ref.key(), anchor, 0f));
    tell(player, messages().get("hof.drag.holding", "cell", ref.key(), "type", anchor.type().id()));
  }

  /** Turns the held anchor by 45° a step. */
  public void turn(PlayerId player, int steps) {
    Held now = held.get(player);
    if (now == null) {
      return;
    }
    Held next = now.turned(45f * steps);
    held.put(player, next);
    platform
        .players()
        .actionBar(
            player,
            messages()
                .get(
                    "hof.drag.turned",
                    "type",
                    now.anchor().type().id(),
                    "yaw",
                    Integer.toString(
                        Math.round(Wand.facing(now.anchor().at().yaw() + next.turn())))));
  }

  /** Puts the held anchor back where it was. */
  public void cancel(PlayerId player) {
    Held now = held.remove(player);
    if (now != null) {
      tell(
          player,
          messages().get("hof.drag.back", "cell", now.cell(), "type", now.anchor().type().id()));
    }
  }

  /** Puts the held anchor down on a block clicked, by 227's rule for where an anchor goes. */
  public void place(PlayerId player, Position block, String face) {
    Held now = held.get(player);
    if (now == null) {
      return;
    }
    AnchorType type = now.anchor().type();
    Position spot =
        type == AnchorType.ARCHIVE || type == AnchorType.PLAQUE ? block : Wand.offset(block, face);
    if (move(
        source(player), now.cell(), now.anchor(), spot, now.anchor().at().yaw() + now.turn())) {
      held.remove(player);
    }
  }

  /**
   * Moves one anchor of a cell to a spot in the same cell, as one edit; false, saying why, if it
   * can't go there.
   */
  boolean move(CommandSource source, String key, Anchor anchor, Position spot, float yaw) {
    Optional<HallService.CellRef> ref =
        hall.resolve(key) instanceof HallService.Resolved.Found found
            ? Optional.of(found.ref())
            : Optional.empty();
    if (ref.isEmpty()) {
      source.reply(messages().get("hof.no-cell", "cell", key));
      return false;
    }
    Cell cell = ref.get().cell();
    int index = cell.anchors().indexOf(anchor);
    if (index < 0) {
      source.reply(messages().get("hof.drag.gone", "cell", key));
      return true;
    }
    Position at =
        new Position(
            spot.dimension(), spot.blockX(), spot.blockY(), spot.blockZ(), Wand.facing(yaw), 0f);
    Anchor moved =
        anchor
            .movedBy(
                at.blockX() - anchor.at().blockX(),
                at.blockY() - anchor.at().blockY(),
                at.blockZ() - anchor.at().blockZ())
            .withAt(at);
    if (cell.bounds().isEmpty() || !GalleryRules.fits(cell.bounds().get(), moved)) {
      source.reply(messages().get("hof.drag.outside", "cell", key));
      return false;
    }
    List<Anchor> anchors = new ArrayList<>(cell.anchors());
    anchors.set(index, moved);
    Gallery next = ref.get().gallery().withCell(cell.withAnchors(anchors));
    if (!GalleryRules.check(next).stream()
        .allMatch(GalleryRules.check(ref.get().gallery())::contains)) {
      source.reply(messages().get("hof.drag.outside", "cell", key));
      return false;
    }
    edits.saved(
        source,
        hall.save(next),
        messages()
            .get(
                "hof.drag.moved",
                "cell",
                key,
                "type",
                anchor.type().id(),
                "x",
                Integer.toString(at.blockX()),
                "y",
                Integer.toString(at.blockY()),
                "z",
                Integer.toString(at.blockZ())));
    return true;
  }

  // Commands

  /** The {@code /hof cell} words this class adds. */
  public CommandNode root() {
    return CommandNode.literal("hof")
        .then(
            CommandNode.literal("cell")
                .permission(HallCommands.PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("anchors")
                        .then(CommandNode.argument("cell", ArgumentType.id()).executes(this::list)))
                .then(
                    CommandNode.literal("anchor")
                        .then(
                            CommandNode.literal("move")
                                .then(
                                    CommandNode.argument("cell", ArgumentType.id())
                                        .then(
                                            CommandNode.argument("rest", ArgumentType.greedy())
                                                .executes(this::moveCommand))))))
        .build();
  }

  /**
   * {@code /hof cell anchors <cell>}: every anchor, numbered, archives and trophies by kind too.
   */
  void list(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> ref = cell(source, arguments.string("cell"));
    if (ref.isEmpty()) {
      return;
    }
    List<Anchor> anchors = ref.get().cell().anchors();
    if (anchors.isEmpty()) {
      source.reply(messages().get("hof.drag.none", "cell", ref.get().key()));
      return;
    }
    Map<AnchorType, Integer> counts = new HashMap<>();
    for (int i = 0; i < anchors.size(); i++) {
      Anchor anchor = anchors.get(i);
      int n = counts.merge(anchor.type(), 1, Integer::sum);
      boolean many = anchor.type() == AnchorType.ARCHIVE || anchor.type() == AnchorType.TROPHY;
      source.reply(
          messages()
              .get(
                  "hof.drag.line",
                  "n",
                  Integer.toString(i + 1),
                  "type",
                  anchor.type().id() + (many ? " #" + n : ""),
                  "x",
                  Integer.toString(anchor.at().blockX()),
                  "y",
                  Integer.toString(anchor.at().blockY()),
                  "z",
                  Integer.toString(anchor.at().blockZ())));
    }
  }

  /** {@code /hof cell anchor move <cell> <type> [#n] <position> [yaw]}. */
  void moveCommand(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> ref = cell(source, arguments.string("cell"));
    if (ref.isEmpty()) {
      return;
    }
    List<String> words = Words.of(arguments.string("rest"));
    Optional<AnchorType> type =
        words.isEmpty() ? Optional.empty() : AnchorType.of(words.getFirst());
    if (type.isEmpty()) {
      source.reply(messages().get("hof.cell.unknown-anchor"));
      return;
    }
    int skip = 1;
    int which = 1;
    if (words.size() > 1 && words.get(1).startsWith("#")) {
      which = number(words.get(1).substring(1));
      skip = 2;
    }
    Optional<Anchor> anchor = nth(ref.get().cell(), type.get(), which);
    if (anchor.isEmpty()) {
      source.reply(
          messages()
              .get(
                  "hof.drag.no-such",
                  "cell",
                  ref.get().key(),
                  "type",
                  type.get().id(),
                  "n",
                  Integer.toString(which)));
      return;
    }
    Optional<Position> origin = source.player().map(p -> platform.players().position(p));
    Positions.Read read =
        Positions.typed(
            String.join(" ", words.subList(skip, words.size())),
            origin,
            ref.get().gallery().dimension(),
            anchor.get().at().yaw());
    if (!(read instanceof Positions.Read.Block found)) {
      source.reply(messages().get(((Positions.Read.Failed) read).messageKey()));
      return;
    }
    move(source, ref.get().key(), anchor.get(), found.block(), found.block().yaw());
  }

  /** The {@code n}th anchor of a type, from 1. */
  static Optional<Anchor> nth(Cell cell, AnchorType type, int n) {
    List<Anchor> of = cell.anchors().stream().filter(a -> a.type() == type).toList();
    return n >= 1 && n <= of.size() ? Optional.of(of.get(n - 1)) : Optional.empty();
  }

  private Optional<HallService.CellRef> cell(CommandSource source, String key) {
    if (hall.resolve(key) instanceof HallService.Resolved.Found found) {
      return Optional.of(found.ref());
    }
    source.reply(messages().get("hof.no-cell", "cell", key));
    return Optional.empty();
  }

  private static int number(String word) {
    try {
      return Integer.parseInt(word);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private CommandSource source(PlayerId player) {
    return new CommandSource() {
      @Override
      public Optional<PlayerId> player() {
        return Optional.of(player);
      }

      @Override
      public void reply(Message message) {
        tell(player, message);
      }
    };
  }

  private void tell(PlayerId player, Message message) {
    platform.players().sendMessage(player, message);
  }

  private Messages messages() {
    return config.get().messages();
  }
}
