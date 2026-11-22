// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandSource;
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
      setMode(swap.player(), !placing.contains(swap.player()));
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (!(intent instanceof Intent.UseWand use)) {
      return Optional.empty();
    }
    PlayerId player = use.player();
    boolean hallAdmin = platform.permissions().has(player, HallCommands.PERMISSION_ADMIN);
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
    if (!hallAdmin) {
      return Optional.of(Decision.allow());
    }
    if (!use.left()) {
      menu(player, block(use.block()), use.face());
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
              .ifPresent(
                  now -> edits.anchor(source(player), now, Anchor.of(type, withYaw(spot, yaw)))));
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
   * A click in the Hall but in no cell (questions 253): a link on the block, and a gate corner in
   * front of it when that is beside exactly one cell. Outside the Hall, refused.
   */
  private void hallMenu(PlayerId player, Position block, Position front) {
    if (!hall.inHall(block) && !hall.inHall(front)) {
      platform.players().sendMessage(player, messages().get("hof.wand.no-cell"));
      return;
    }
    List<Menus.Choice> choices = new ArrayList<>();
    List<Runnable> actions = new ArrayList<>();
    choices.add(new Menus.Choice("minecraft:lodestone", messages().get("hof.wand.link")));
    actions.add(
        () -> links.add(source(player), hall.layout().nextLinkId(), block, Optional.empty()));
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
    platform
        .menus()
        .openChoice(
            player,
            messages().get("hof.wand.menu-hall"),
            choices,
            index -> {
              platform.menus().close(player);
              if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
              }
            });
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

  /** Makes a block a link, as {@code /hof link add} does. */
  public interface Links {
    void add(CommandSource source, String id, Position block, Optional<HallLayout.Target> to);
  }

  private Links links = (source, id, block, to) -> {};

  /** Connects the link command, once it exists. */
  public void bindLinks(Links links) {
    this.links = links;
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
