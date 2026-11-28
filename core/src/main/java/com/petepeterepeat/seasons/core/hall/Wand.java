// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Positions;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Menus;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The Hall admins' wand (questions 226 to 229 and 236): two corners make a selection, which a named
 * cell takes as its bounds and the bounds and zone commands take when given no corners; a plain
 * right click on a block in a cell opens a menu of anchors to put there. Every wand click on a
 * block is Seasons', so it never breaks, opens or uses the block. Runs on the server thread.
 */
public final class Wand {
  /** Selecting corners for zones needs this, or the Hall admin node. */
  public static final String PERMISSION_ZONES = "seasons.admin.config";

  /** How many egg plinth seasons the museum's menu offers at most. */
  private static final int EGG_CHOICES = 3;

  private static final List<AnchorType> TYPES =
      List.of(
          AnchorType.FIGURE,
          AnchorType.ARCHIVE,
          AnchorType.PLAQUE,
          AnchorType.TROPHY,
          AnchorType.ENTRY,
          AnchorType.GATE);

  private static final Map<AnchorType, String> ICONS =
      Map.of(
          AnchorType.FIGURE, "minecraft:armor_stand",
          AnchorType.ARCHIVE, "minecraft:chest",
          AnchorType.PLAQUE, "minecraft:lectern",
          AnchorType.TROPHY, "minecraft:item_frame",
          AnchorType.ENTRY, "minecraft:oak_door",
          AnchorType.GATE, "minecraft:iron_bars");

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final CellEdits edits;
  private final Supplier<OptionalInt> season;
  private final Map<PlayerId, Selection> selections = new HashMap<>();

  /** A block each admin put aside with the wand for {@code /hof link add} to take (M8). */
  private final Map<PlayerId, Position> pending = new HashMap<>();

  private final Map<PlayerId, String> cells = new HashMap<>();

  /** Admins whose corners take the block against the face clicked (questions 272). */
  private final Set<PlayerId> placing = new HashSet<>();

  /**
   * @param season the season being played, if one is recorded
   */
  public Wand(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      CellEdits edits,
      Supplier<OptionalInt> season) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.edits = edits;
    this.season = season;
  }

  /** {@code /hof wand}. */
  public void give(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    boolean given =
        platform
            .inventories()
            .giveWand(
                source.player().get(),
                namespaced(config.get().config().wand().item()),
                messages().get("hof.wand.name"),
                messages().get("hof.wand.lore"));
    source.reply(messages().get(given ? "hof.wand.given" : "hof.wand.full"));
  }

  /**
   * The cell just made by {@code /hof cell new}, whose bounds the admin's next selection inside its
   * gallery becomes, once, while it has none (questions 228).
   */
  public void focus(PlayerId player, HallService.CellRef ref) {
    cells.put(player, ref.key());
  }

  /** Puts a block aside for the admin's next {@code /hof link add} with no {@code from} (M8). */
  public void putAside(PlayerId player, Position block) {
    pending.put(player, block);
  }

  /** The block the admin put aside, taken: once only. */
  public Optional<Position> takePending(PlayerId player) {
    return Optional.ofNullable(pending.remove(player));
  }

  /** Sets an admin's selection to a whole box, as nudging a face does (M8). */
  public void setSelection(PlayerId player, Bounds box) {
    selections.put(player, Selection.startingAt(box.min()).withSecond(box.max()));
  }

  private @Nullable BoxEdits boxes;
  private @Nullable AnchorDrag drag;

  /** Connects nudging faces and dragging anchors (M8). */
  public void bindEdits(BoxEdits nextBoxes, AnchorDrag nextDrag) {
    this.boxes = nextBoxes;
    this.drag = nextDrag;
  }

  /** An admin's complete selection, if they have one. */
  public Optional<Bounds> selection(PlayerId player) {
    return Optional.ofNullable(selections.get(player)).flatMap(Selection::bounds);
  }

  /** Forgets a player's selection when they leave. */
  public void left(PlayerId player) {
    selections.remove(player);
    cells.remove(player);
    placing.remove(player);
  }

  /** Whether an admin's corners take the block against the face clicked. */
  public boolean placeMode(PlayerId player) {
    return placing.contains(player);
  }

  /** An admin's selection as it stands, half made or whole. */
  public Optional<Selection> rawSelection(PlayerId player) {
    return Optional.ofNullable(selections.get(player));
  }

  /** The cell an admin's next finished selection sets the bounds of, if any (questions 273). */
  public Optional<HallService.CellRef> wandCell(PlayerId player) {
    Optional<HallService.CellRef> ref = focused(player);
    return ref.filter(r -> r.cell().bounds().isEmpty() || redefining.contains(player));
  }

  /** Admins whose wand cell is being redefined: its next selection replaces its bounds. */
  private final Set<PlayerId> redefining = new HashSet<>();

  /** {@code /hof cell redefine}: the cell's next selection replaces its bounds (questions 273). */
  public void redefine(PlayerId player, HallService.CellRef ref) {
    cells.put(player, ref.key());
    redefining.add(player);
  }

  /** {@code /hof wand mode block|place}. */
  public void mode(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    String mode = arguments.string("mode");
    if (!mode.equals("block") && !mode.equals("place")) {
      source.reply(messages().get("hof.wand.bad-mode"));
      return;
    }
    setMode(source.player().get(), mode.equals("place"));
  }

  private void setMode(PlayerId player, boolean place) {
    if (place) {
      placing.add(player);
    } else {
      placing.remove(player);
    }
    platform
        .players()
        .sendMessage(player, messages().get(place ? "hof.wand.place-mode" : "hof.wand.block-mode"));
  }

  /** {@code /hof sel pos1|pos2 [position]}: a corner by command, as the wand sets one. */
  public void selCorner(CommandSource source, Arguments arguments, int corner) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    PlayerId player = source.player().get();
    Position standing = platform.players().position(player);
    Position at;
    Optional<String> typed = arguments.optionalString("at");
    if (typed.isPresent()) {
      com.petepeterepeat.seasons.core.command.Positions.Read read =
          com.petepeterepeat.seasons.core.command.Positions.typed(
              typed.get(), Optional.of(standing), standing.dimension(), 0f);
      if (read instanceof com.petepeterepeat.seasons.core.command.Positions.Read.Failed failed) {
        source.reply(messages().get(failed.messageKey()));
        return;
      }
      at = block(((com.petepeterepeat.seasons.core.command.Positions.Read.Block) read).block());
    } else {
      at = block(standing);
    }
    corner(player, corner, at, true);
  }

  /** {@code /hof sel info}. */
  public void selInfo(CommandSource source, Arguments arguments) {
    Optional<Message> editing =
        source.player().flatMap(p -> boxes == null ? Optional.empty() : boxes.describe(p));
    if (editing.isPresent()) {
      source.reply(editing.get());
      return;
    }
    Optional<Bounds> box = source.player().flatMap(this::selection);
    if (box.isEmpty()) {
      source.reply(messages().get("hof.wand.no-selection"));
      return;
    }
    source.reply(selected("hof.wand.selection", box.get()));
  }

  /** {@code /hof sel clear}. */
  public void selClear(CommandSource source, Arguments arguments) {
    source.player().ifPresent(selections::remove);
    source.reply(messages().get("hof.wand.cleared"));
  }

  /**
   * The wand's say on a wand click: denied (taken over) when it was Seasons', allowed when the
   * player may not use the wand, so the click goes on as if no wand were held.
   */
  public Optional<Decision> decide(Intent intent) {
    if (intent instanceof Intent.WandSwap swap) {
      boolean admin =
          platform.permissions().has(swap.player(), HallCommands.PERMISSION_ADMIN)
              || platform.permissions().has(swap.player(), PERMISSION_ZONES);
      if (!admin) {
        return Optional.of(Decision.allow());
      }
      if (boxes != null
          && boxes.editing(swap.player())
          && config.get().config().wand().nudge().swapHandsApplies()) {
        // Swap-hands applies an edited box (M8), as /hof sel apply does.
        boxes.apply(source(swap.player()), com.petepeterepeat.seasons.core.command.Arguments.NONE);
        return Optional.of(new Decision.Deny(Optional.empty()));
      }
      setMode(swap.player(), !placing.contains(swap.player()));
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (intent instanceof Intent.WandScroll scroll && scroll.sneaking()) {
      // Sneak and scroll: a held anchor turns, an edited box's face moves (M8). A scroll up is a
      // step back in the hotbar.
      int back = Math.floorMod(scroll.from() - scroll.to(), 9);
      boolean up = back > 0 && back < 5;
      if (drag != null && drag.holding(scroll.player())) {
        drag.turn(scroll.player(), up ? 1 : -1);
        return Optional.of(new Decision.Deny(Optional.empty()));
      }
      if (boxes != null && boxes.editing(scroll.player())) {
        boxes.scroll(scroll.player(), up);
        return Optional.of(new Decision.Deny(Optional.empty()));
      }
      return Optional.empty();
    }
    if (intent instanceof Intent.WandDrop drop) {
      if (boxes != null) {
        boxes.cycleStep(drop.player());
      }
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (!(intent instanceof Intent.UseWand use)) {
      return Optional.empty();
    }
    PlayerId player = use.player();
    boolean hallAdmin = platform.permissions().has(player, HallCommands.PERMISSION_ADMIN);
    boolean linkAdmin =
        hallAdmin || platform.permissions().has(player, LinkService.PERMISSION_ADMIN);
    boolean zoneAdmin = hallAdmin || platform.permissions().has(player, PERMISSION_ZONES);
    if (use.sneaking() && zoneAdmin) {
      Position clicked = block(use.block());
      corner(
          player,
          use.left() ? 1 : 2,
          placing.contains(player) ? offset(clicked, use.face()) : clicked,
          false);
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (!linkAdmin) {
      return Optional.of(Decision.allow());
    }
    if (hallAdmin && drag != null && drag.holding(player)) {
      // A held anchor: a right-click puts it down, a left-click puts it back (M8).
      if (use.left()) {
        drag.cancel(player);
      } else {
        drag.place(player, block(use.block()), use.face());
      }
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (use.left() && hallAdmin && boxes != null) {
      // A left-click takes the box it is in to edit, the face clicked picked (M8).
      boxes.pick(player, block(use.block()), use.face());
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (!use.left()) {
      Position clicked = block(use.block());
      Optional<HallLayout.Link> trigger = hall.layout().linkAt(clicked);
      if (trigger.isPresent() && linkActions != null) {
        // A block that is a trigger already: its link's editor, not a second link (M8).
        linkActions.edit(player, trigger.get());
      } else if (hallAdmin) {
        menu(player, clicked, use.face());
      } else {
        linkMenu(player, clicked, offset(clicked, use.face()), false);
      }
    }
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  /**
   * @param typed set by command, so said in chat rather than on the action bar
   */
  private void corner(PlayerId player, int corner, Position at, boolean typed) {
    Selection current = selections.get(player);
    Selection next =
        corner == 1 || current == null
            ? (corner == 1
                ? Selection.startingAt(at)
                : new Selection(at.dimension(), Optional.empty(), Optional.of(at)))
            : current.withSecond(at);
    selections.put(player, next);
    Optional<Bounds> bounds = next.bounds();
    if (bounds.isEmpty()) {
      Message line =
          messages().get("hof.wand.corner", CellEdits.place("n", Integer.toString(corner), at));
      if (typed) {
        platform.players().sendMessage(player, line);
      } else {
        platform.players().actionBar(player, line);
      }
      return;
    }
    Bounds box = bounds.get();
    platform.players().sendMessage(player, selected("hof.wand.selected", box));
    Optional<HallService.CellRef> focused = focused(player);
    if (focused.isEmpty() || !platform.permissions().has(player, HallCommands.PERMISSION_ADMIN)) {
      return;
    }
    if (redefining.contains(player)) {
      // A cell being redefined takes the selection whatever its bounds (questions 273).
      Gallery gallery = focused.get().gallery();
      if (gallery.bounds().map(g -> g.contains(box.min()) && g.contains(box.max())).orElse(true)
          && edits.redefine(source(player), focused.get(), box)) {
        cells.remove(player);
        redefining.remove(player);
      }
      return;
    }
    if (focused.get().cell().bounds().isPresent()) {
      // Bounds set some other way since /hof cell new: the wand never moves them.
      cells.remove(player);
      return;
    }
    Gallery gallery = focused.get().gallery();
    Optional<Bounds> galleryBounds = gallery.bounds();
    if (galleryBounds.isPresent()
        && galleryBounds.get().contains(box.min())
        && galleryBounds.get().contains(box.max())
        && edits.cellBounds(source(player), focused.get(), box)) {
      // Once only: later selections are only selections (questions 228).
      cells.remove(player);
    }
  }

  private Message selected(String key, Bounds box) {
    return messages()
        .get(
            key,
            "from",
            CellEdits.corner(box.min()),
            "to",
            CellEdits.corner(box.max()),
            "dimension",
            box.dimension(),
            "sx",
            Integer.toString(box.maxX() - box.minX() + 1),
            "sy",
            Integer.toString(box.maxY() - box.minY() + 1),
            "sz",
            Integer.toString(box.maxZ() - box.minZ() + 1));
  }

  private Optional<HallService.CellRef> focused(PlayerId player) {
    String key = cells.get(player);
    if (key == null) {
      return Optional.empty();
    }
    return hall.resolve(key) instanceof HallService.Resolved.Found found
        ? Optional.of(found.ref())
        : Optional.empty();
  }

  private void menu(PlayerId player, Position block, String face) {
    Position front = offset(block, face);
    // The block clicked, or the one in front of it: a cell's floor isn't in its bounds.
    Optional<HallService.CellRef> found = cellAt(block).or(() -> cellAt(front));
    if (found.isEmpty()) {
      hallMenu(player, block, front);
      return;
    }
    HallService.CellRef ref = found.get();
    float yaw = facing(platform.players().position(player).yaw() + 180f);
    List<Menus.Choice> choices = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    for (AnchorType type : TYPES) {
      Position spot = type == AnchorType.ARCHIVE || type == AnchorType.PLAQUE ? block : front;
      choices.add(
          new Menus.Choice(
              ICONS.getOrDefault(type, "minecraft:stone"),
              messages().get("hof.wand.types." + type.id())));
      actions.add(() ->
          current(ref)
              .ifPresent(now ->
                  edits.anchor(
                      source(player),
                      now,
                      type == AnchorType.PLAQUE
                          ? Anchor.of(type, withYaw(spot, yaw))
                              .withFacing(PlaqueFit.Facing.of(face))
                          : Anchor.of(type, withYaw(spot, yaw)))));
    }
    if (ref.gallery().kind() == Gallery.Kind.MUSEUM) {
      for (int egg : eggSeasons()) {
        choices.add(
            new Menus.Choice(
                "minecraft:dragon_egg",
                messages().get("hof.wand.egg", "season", Integer.toString(egg))));
        actions.add(() ->
            current(ref)
                .ifPresent(now ->
                    edits.anchor(
                        source(player),
                        now,
                        new Anchor(
                            AnchorType.TROPHY,
                            withYaw(front, yaw),
                            OptionalInt.empty(),
                            OptionalInt.of(egg)))));
      }
    }
    // A link on the block, from anywhere (M8).
    choices.add(new Menus.Choice("minecraft:lodestone", messages().get("hof.wand.link")));
    actions.add(() -> later(() -> linkMenu(player, block, front, false)));
    // Move: pick the anchor on the block, or in front of it, up (M8).
    Optional<Anchor> movable =
        ref.cell().anchors().stream()
            .filter(a -> a.type() != AnchorType.GATE && (a.isAt(block) || a.isAt(front)))
            .findFirst();
    if (movable.isPresent() && drag != null) {
      AnchorDrag dragging = drag;
      choices.add(new Menus.Choice("minecraft:piston", messages().get("hof.wand.move")));
      actions.add(() -> current(ref).ifPresent(now -> dragging.pickUp(player, now, movable.get())));
    }
    boolean onBlock = ref.cell().anchors().stream().anyMatch(a -> a.isAt(block));
    boolean inFront = ref.cell().anchors().stream().anyMatch(a -> a.isAt(front));
    if (onBlock || inFront) {
      Position spot = onBlock ? block : front;
      choices.add(new Menus.Choice("minecraft:barrier", messages().get("hof.wand.remove")));
      actions.add(() -> current(ref).ifPresent(now -> edits.removeAt(source(player), now, spot)));
    }
    platform
        .menus()
        .openChoice(
            player,
            messages().get("hof.wand.menu", "cell", ref.key()),
            choices,
            index -> {
              platform.menus().close(player);
              if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
              }
            });
  }

  /**
   * A click in the Hall but in no cell (questions 253), or anywhere at all for a link admin (M8):
   * the link menu, and in the Hall a gate corner in front of the block when that is beside exactly
   * one cell.
   */
  private void hallMenu(PlayerId player, Position block, Position front) {
    linkMenu(player, block, front, true);
  }

  /**
   * The link menu (M8): the block as a link to the Hall, a season's spawn, a point or another link,
   * one way or two, or put aside for {@code /hof link add}; and for a Hall admin in the Hall, a
   * gate corner, the Hall spawn, a gallery's entry or an exit there.
   */
  private void linkMenu(PlayerId player, Position block, Position front, boolean hallAdmin) {
    CommandSource source = source(player);
    List<Menus.Choice> choices = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    choices.add(new Menus.Choice("minecraft:lodestone", messages().get("hof.wand.link-hall")));
    actions.add(() -> make(source, block, new HallLayout.Target.Hall(), false));
    List<Integer> seasons = seasonsWithSpawn();
    if (!seasons.isEmpty()) {
      choices.add(
          new Menus.Choice("minecraft:grass_block", messages().get("hof.wand.link-season")));
      actions.add(() -> later(() -> seasonMenu(player, block, false)));
    }
    if (!hall.layout().points().isEmpty()) {
      choices.add(new Menus.Choice("minecraft:compass", messages().get("hof.wand.link-point")));
      actions.add(() -> later(() -> pointMenu(player, block)));
    }
    if (!hall.layout().links().isEmpty()) {
      choices.add(new Menus.Choice("minecraft:lead", messages().get("hof.wand.link-link")));
      actions.add(() -> later(() -> linkToLinkMenu(player, block)));
    }
    choices.add(new Menus.Choice("minecraft:ender_eye", messages().get("hof.wand.link-two-way")));
    actions.add(() -> later(() -> seasonMenu(player, block, true)));
    choices.add(new Menus.Choice("minecraft:paper", messages().get("hof.wand.put-aside")));
    actions.add(() -> {
      putAside(player, block);
      platform
          .players()
          .sendMessage(
              player, messages().get("hof.wand.put-aside-done", CellEdits.place("n", "", block)));
    });
    boolean inHall = hall.inHall(block) || hall.inHall(front);
    if (hallAdmin && inHall) {
      List<HallService.CellRef> beside = besideCells(front);
      if (beside.size() == 1) {
        HallService.CellRef ref = beside.getFirst();
        choices.add(
            new Menus.Choice(
                "minecraft:iron_bars", messages().get("hof.wand.gate-for", "cell", ref.key())));
        float yaw = facing(platform.players().position(player).yaw() + 180f);
        actions.add(() ->
            current(ref)
                .ifPresent(now ->
                    edits.anchor(
                        source(player), now, Anchor.of(AnchorType.GATE, withYaw(front, yaw)))));
      }
      setAsChoices(player, front, choices, actions);
    }
    platform
        .menus()
        .openChoice(
            player,
            messages().get(inHall ? "hof.wand.menu-hall" : "hof.wand.menu-link"),
            choices,
            index -> {
              platform.menus().close(player);
              if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
              }
            });
  }

  /**
   * The Hall's own spots set from the wand (M8): the Hall spawn in the hub, a gallery's entry, an
   * exit; each from the block in front of the face clicked, or from the selection when there is
   * one.
   */
  private void setAsChoices(
      PlayerId player, Position front, List<Menus.Choice> choices, List<Runnable> actions) {
    if (hallCommands == null) {
      return;
    }
    HallCommands commands = hallCommands;
    CommandSource source = source(player);
    float yaw = facing(platform.players().position(player).yaw() + 180f);
    String spot = front.blockX() + " " + front.blockY() + " " + front.blockZ();
    boolean selection = selection(player).isPresent();
    if (hall.layout().inHub(front)) {
      choices.add(
          new Menus.Choice("minecraft:respawn_anchor", messages().get("hof.wand.set-spawn")));
      actions.add(() ->
          commands.hallSpawn(source, new Arguments(Map.of("at", spot + " " + Math.round(yaw)))));
      choices.add(new Menus.Choice("minecraft:iron_door", messages().get("hof.wand.set-exit")));
      actions.add(() -> commands.hallExit(source, new Arguments(Map.of("at", spot))));
      if (selection) {
        choices.add(
            new Menus.Choice(
                "minecraft:crying_obsidian", messages().get("hof.wand.set-spawn-sel")));
        actions.add(
            () -> commands.hallSpawn(source, new Arguments(Map.of("at", Positions.SELECTION))));
        choices.add(
            new Menus.Choice("minecraft:iron_trapdoor", messages().get("hof.wand.set-exit-sel")));
        actions.add(
            () -> commands.hallExit(source, new Arguments(Map.of("at", Positions.SELECTION))));
      }
    }
    for (Gallery gallery : hall.galleries()) {
      if (!gallery.contains(front)) {
        continue;
      }
      String id = gallery.id();
      choices.add(
          new Menus.Choice(
              "minecraft:oak_door", messages().get("hof.wand.set-entry", "gallery", id)));
      actions.add(() ->
          commands.galleryEntry(
              source, new Arguments(Map.of("gallery", id, "at", spot + " " + Math.round(yaw)))));
      choices.add(
          new Menus.Choice(
              "minecraft:birch_door", messages().get("hof.wand.set-gallery-exit", "gallery", id)));
      actions.add(
          () -> commands.galleryExit(source, new Arguments(Map.of("gallery", id, "at", spot))));
      if (selection) {
        choices.add(
            new Menus.Choice(
                "minecraft:spruce_door", messages().get("hof.wand.set-entry-sel", "gallery", id)));
        actions.add(() ->
            commands.galleryEntry(
                source, new Arguments(Map.of("gallery", id, "at", Positions.SELECTION))));
      }
    }
  }

  /** The seasons whose spawn is recorded, for links to them. */
  private List<Integer> seasonsWithSpawn() {
    return config.get().seasons().values().stream()
        .filter(f -> f.spawn().isPresent())
        .map(com.petepeterepeat.seasons.core.config.SeasonFile::season)
        .sorted()
        .toList();
  }

  /** Each season with a spawn, and the Hall for a two-way link, as choices. */
  private void seasonMenu(PlayerId player, Position block, boolean twoWay) {
    CommandSource source = source(player);
    List<Menus.Choice> choices = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    if (twoWay) {
      choices.add(new Menus.Choice("minecraft:lodestone", messages().get("hof.wand.link-hall")));
      actions.add(() -> make(source, block, new HallLayout.Target.Hall(), true));
    }
    for (int season : seasonsWithSpawn()) {
      choices.add(
          new Menus.Choice(
              "minecraft:grass_block",
              messages().get("hof.wand.season", "season", Integer.toString(season))));
      actions.add(() -> make(source, block, new HallLayout.Target.SeasonSpawn(season), twoWay));
    }
    choose(
        player,
        messages().get(twoWay ? "hof.wand.menu-two-way" : "hof.wand.menu-season"),
        choices,
        actions);
  }

  private void pointMenu(PlayerId player, Position block) {
    CommandSource source = source(player);
    List<Menus.Choice> choices = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    for (String name : hall.layout().points().keySet()) {
      choices.add(
          new Menus.Choice("minecraft:compass", messages().get("hof.wand.named", "name", name)));
      actions.add(() -> make(source, block, new HallLayout.Target.AtPoint(name), false));
    }
    choose(player, messages().get("hof.wand.menu-point"), choices, actions);
  }

  private void linkToLinkMenu(PlayerId player, Position block) {
    CommandSource source = source(player);
    List<Menus.Choice> choices = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    for (String id : hall.layout().links().keySet()) {
      choices.add(new Menus.Choice("minecraft:lead", messages().get("hof.wand.named", "name", id)));
      actions.add(() -> make(source, block, new HallLayout.Target.ToLink(id), false));
    }
    choose(player, messages().get("hof.wand.menu-link-link"), choices, actions);
  }

  private void choose(
      PlayerId player, Message title, List<Menus.Choice> choices, List<Runnable> actions) {
    platform
        .menus()
        .openChoice(
            player,
            title,
            choices,
            index -> {
              platform.menus().close(player);
              if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
              }
            });
  }

  private void make(CommandSource source, Position block, HallLayout.Target to, boolean twoWay) {
    if (linkActions != null) {
      linkActions.make(source, block, to, twoWay);
    }
  }

  private void later(Runnable task) {
    platform.scheduler().runGlobal(task);
  }

  /** The cells a block is right beside, in their doorway (questions 250). */
  private List<HallService.CellRef> besideCells(Position block) {
    List<HallService.CellRef> found = new ArrayList<>();
    for (Gallery gallery : hall.galleries()) {
      for (Cell cell : gallery.cells()) {
        if (cell.bounds().isPresent()
            && !cell.contains(block)
            && cell.bounds().get().grown(1).contains(block)) {
          found.add(new HallService.CellRef(gallery, cell));
        }
      }
    }
    return found;
  }

  /** What the wand's link menu does with a block (M8), from the link commands. */
  public interface LinkActions {
    /** Makes the block a link with the next free id, one way or two. */
    void make(CommandSource source, Position block, HallLayout.Target to, boolean twoWay);

    /** Opens a link's editor for an admin. */
    void edit(PlayerId player, HallLayout.Link link);
  }

  private @Nullable LinkActions linkActions;
  private @Nullable HallCommands hallCommands;

  /** Connects the link commands, once they exist. */
  public void bindLinks(LinkActions actions, HallCommands commands) {
    this.linkActions = actions;
    this.hallCommands = commands;
  }

  /** The cell as it is now, which the menu's choice acts on. */
  private Optional<HallService.CellRef> current(HallService.CellRef ref) {
    return hall.resolve(ref.key()) instanceof HallService.Resolved.Found found
        ? Optional.of(found.ref())
        : Optional.empty();
  }

  /** The newest seasons, up to the next one, that have no egg plinth in any museum. */
  private List<Integer> eggSeasons() {
    Set<Integer> taken = new HashSet<>();
    for (Gallery gallery : hall.galleries()) {
      for (Cell cell : gallery.cells()) {
        for (Anchor anchor : cell.anchors()) {
          anchor.egg().ifPresent(taken::add);
        }
      }
    }
    int newest = season.get().orElse(0) + 1;
    List<Integer> free = new ArrayList<>();
    for (int s = newest; s >= 1 && free.size() < EGG_CHOICES; s--) {
      if (!taken.contains(s)) {
        free.add(s);
      }
    }
    return free;
  }

  private Optional<HallService.CellRef> cellAt(Position block) {
    for (Gallery gallery : hall.galleries()) {
      if (!gallery.dimension().equals(block.dimension())) {
        continue;
      }
      for (Cell cell : gallery.cells()) {
        if (cell.contains(block)) {
          return Optional.of(new HallService.CellRef(gallery, cell));
        }
      }
    }
    return Optional.empty();
  }

  /** The block next to one on a side. */
  static Position offset(Position block, String face) {
    int dx = 0;
    int dy = 0;
    int dz = 0;
    switch (face) {
      case "up" -> dy = 1;
      case "down" -> dy = -1;
      case "north" -> dz = -1;
      case "south" -> dz = 1;
      case "east" -> dx = 1;
      case "west" -> dx = -1;
      default -> {}
    }
    return Position.of(
        block.dimension(), block.blockX() + dx, block.blockY() + dy, block.blockZ() + dz);
  }

  /** A yaw rounded to 45°, between -180 (exclusive) and 180. */
  static float facing(float yaw) {
    float snapped = Math.round(yaw / 45f) * 45f;
    float normal = ((snapped % 360f) + 360f) % 360f;
    return normal > 180f ? normal - 360f : normal;
  }

  private static Position block(Position at) {
    return Position.of(at.dimension(), at.blockX(), at.blockY(), at.blockZ());
  }

  private static Position withYaw(Position at, float yaw) {
    return new Position(at.dimension(), at.x(), at.y(), at.z(), yaw, 0f);
  }

  private CommandSource source(PlayerId player) {
    return new CommandSource() {
      @Override
      public Optional<PlayerId> player() {
        return Optional.of(player);
      }

      @Override
      public void reply(Message message) {
        platform.players().sendMessage(player, message);
      }
    };
  }

  private static String namespaced(String id) {
    return id.indexOf(':') < 0 ? "minecraft:" + id : id;
  }

  private Messages messages() {
    return config.get().messages();
  }
}
