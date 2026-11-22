// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Positions;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code /hof} command tree: tagging galleries and cells by command or with the wand, stamping
 * templates, assigning and swapping cells, redrawing displays, and the Season 1 import.
 */
public final class HallCommands {
  /** Permission for the admin commands: galleries, cells, import, reconcile. */
  public static final String PERMISSION_ADMIN = "seasons.admin.hof";

  /** Permission for {@code /hof swap}. */
  public static final String PERMISSION_SWAP = "seasons.hof.swap";

  /** {@code /hof claim}. */
  public static final String PERMISSION_CLAIM = "seasons.hof.claim";

  /** {@code /hof gate}: an owner opens or shuts their own cell's gate (questions 250). */
  public static final String PERMISSION_GATE = "seasons.hof.gate";

  private static final String OPEN = "open";
  private static final String SHUT = "shut";

  private static final Pattern WORDS = Pattern.compile("\\s+");

  /** Splits on runs of whitespace, dropping empty words. */
  private static final class Splitter {
    static String[] split(String text) {
      return WORDS.splitAsStream(text.trim()).filter(w -> !w.isEmpty()).toArray(String[]::new);
    }
  }

  private static final String DRY_RUN = "--dry-run";
  private static final String OVERWORLD = "minecraft:overworld";

  /** How far away a looked-at block can be to take an anchor. */
  private static final int REACH = 6;

  private static final Logger LOG = LoggerFactory.getLogger(HallCommands.class);

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final HallVisits visits;
  private final HallRenderer renderer;
  private final Reconciler reconciler;
  private final Optional<HallImporter> importer;
  private final PlayerStore players;
  private final CellEdits edits;
  private final Wand wand;
  private final Optional<Stamper> stamper;
  private final CommandHelp help;

  /**
   * @param importer empty when the journal couldn't be opened, so imports are refused
   */
  public HallCommands(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      HallVisits visits,
      HallRenderer renderer,
      Reconciler reconciler,
      Optional<HallImporter> importer,
      PlayerStore players,
      CellEdits edits,
      Wand wand,
      Optional<Stamper> stamper,
      CommandHelp help) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.visits = visits;
    this.renderer = renderer;
    this.reconciler = reconciler;
    this.importer = importer;
    this.players = players;
    this.edits = edits;
    this.wand = wand;
    this.stamper = stamper;
    this.help = help;
  }

  public CommandNode root() {
    return CommandNode.literal("hof")
        .executes(this::visit)
        .then(CommandNode.literal("leave").executes(this::leave))
        .then(
            CommandNode.literal("gate")
                .permission(PERMISSION_GATE)
                .then(CommandNode.argument("state", ArgumentType.word()).executes(this::ownerGate)))
        .then(
            CommandNode.literal("hall")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("hub")
                        .executes(this::hallHub)
                        .then(
                            CommandNode.argument("corners", ArgumentType.greedy())
                                .executes(this::hallHub)))
                .then(
                    CommandNode.literal("spawn")
                        .executes(this::hallSpawn)
                        .then(
                            CommandNode.argument("at", ArgumentType.greedy())
                                .executes(this::hallSpawn)))
                .then(
                    CommandNode.literal("exit")
                        .executes(this::hallExit)
                        .then(
                            CommandNode.argument("at", ArgumentType.greedy())
                                .executes(this::hallExit)))
                .then(CommandNode.literal("check").executes(this::hallCheck)))
        .then(
            CommandNode.literal("link")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("add")
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::linkAdd)
                                .then(
                                    CommandNode.argument("rest", ArgumentType.greedy())
                                        .executes(this::linkAdd))))
                .then(
                    CommandNode.literal("to")
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::linkTo)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::linkTo))))
                .then(
                    CommandNode.literal("remove")
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::linkRemove)))
                .then(CommandNode.literal("list").executes(this::linkList)))
        .then(CommandNode.argument("target", ArgumentType.word()).executes(this::visit))
        .then(
            CommandNode.literal("gallery")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("create")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .then(
                                    CommandNode.argument(
                                            "season", ArgumentType.integer(1, Integer.MAX_VALUE))
                                        .executes(this::galleryCreate))))
                .then(
                    CommandNode.literal("bounds")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::galleryBounds)
                                .then(
                                    CommandNode.argument("corners", ArgumentType.greedy())
                                        .executes(this::galleryBounds))))
                .then(
                    CommandNode.literal("entry")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::galleryEntry)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::galleryEntry))))
                .then(
                    CommandNode.literal("exit")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::galleryExit)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::galleryExit))))
                .then(
                    CommandNode.literal("launch")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::galleryLaunch)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::galleryLaunch))))
                .then(
                    CommandNode.literal("gate")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .then(
                                    CommandNode.argument("state", ArgumentType.word())
                                        .executes(this::galleryGate))))
                .then(
                    CommandNode.literal("info")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::galleryInfo))))
        .then(
            CommandNode.literal("cell")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("new")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::cellNew)
                                .then(
                                    CommandNode.argument("id", ArgumentType.word())
                                        .executes(this::cellNew))))
                .then(
                    CommandNode.literal("gate")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.argument("state", ArgumentType.word())
                                        .executes(this::cellGate))))
                .then(
                    CommandNode.literal("bounds")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .executes(this::cellBounds)
                                .then(
                                    CommandNode.argument("corners", ArgumentType.greedy())
                                        .executes(this::cellBounds))))
                .then(
                    CommandNode.literal("anchor")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.argument("type", ArgumentType.word())
                                        .executes(this::cellAnchor)
                                        .then(
                                            CommandNode.argument("at", ArgumentType.greedy())
                                                .executes(this::cellAnchor)))))
                .then(
                    CommandNode.literal("unanchor")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.argument("type", ArgumentType.word())
                                        .executes(this::cellUnanchor))))
                .then(
                    CommandNode.literal("assign")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.argument("player", ArgumentType.word())
                                        .executes(this::cellAssign)
                                        .then(
                                            CommandNode.argument(
                                                    "season",
                                                    ArgumentType.integer(1, Integer.MAX_VALUE))
                                                .executes(this::cellAssign)))))
                .then(
                    CommandNode.literal("unassign")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .executes(this::cellUnassign)
                                .then(
                                    CommandNode.argument(
                                            "season", ArgumentType.integer(1, Integer.MAX_VALUE))
                                        .executes(this::cellUnassign))))
                .then(
                    CommandNode.literal("info")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .executes(this::cellInfo))))
        .then(
            CommandNode.literal("tp")
                .permission(PERMISSION_ADMIN)
                .then(CommandNode.literal("hub").executes(this::tpHub))
                .then(
                    CommandNode.literal("gallery")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::tpGallery)))
                .then(
                    CommandNode.literal("cell")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id()).executes(this::tpCell)))
                .then(
                    CommandNode.literal("player")
                        .then(
                            CommandNode.argument("player", ArgumentType.word())
                                .executes(this::tpPlayer)))
                .then(
                    CommandNode.literal("link")
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::tpLink))))
        .then(
            CommandNode.literal("wand")
                .permission(PERMISSION_ADMIN)
                .executes(wand::give)
                .then(
                    CommandNode.literal("mode")
                        .then(
                            CommandNode.argument("mode", ArgumentType.word())
                                .executes(wand::mode))))
        .then(
            CommandNode.literal("sel")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("pos1")
                        .executes((source, args) -> wand.selCorner(source, args, 1))
                        .then(
                            CommandNode.argument("at", ArgumentType.greedy())
                                .executes((source, args) -> wand.selCorner(source, args, 1))))
                .then(
                    CommandNode.literal("pos2")
                        .executes((source, args) -> wand.selCorner(source, args, 2))
                        .then(
                            CommandNode.argument("at", ArgumentType.greedy())
                                .executes((source, args) -> wand.selCorner(source, args, 2))))
                .then(CommandNode.literal("info").executes(wand::selInfo))
                .then(CommandNode.literal("clear").executes(wand::selClear)))
        .then(
            CommandNode.literal("show")
                .permission(PERMISSION_ADMIN)
                .then(CommandNode.literal("on").executes((source, args) -> show(source, true)))
                .then(CommandNode.literal("off").executes((source, args) -> show(source, false))))
        .then(CommandNode.literal("undo").permission(PERMISSION_ADMIN).executes(this::undo))
        .then(
            CommandNode.literal("stamp")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.argument("template", ArgumentType.word())
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .then(
                                    CommandNode.argument("count", ArgumentType.integer(1, 64))
                                        .executes(this::stamp)
                                        .then(
                                            CommandNode.argument("options", ArgumentType.greedy())
                                                .executes(this::stamp))))))
        .then(
            CommandNode.literal("claim")
                .permission(PERMISSION_CLAIM)
                .executes(this::claim)
                .then(CommandNode.argument("cell", ArgumentType.id()).executes(this::claim)))
        .then(
            CommandNode.literal("swap")
                .permission(PERMISSION_SWAP)
                .then(CommandNode.argument("player", ArgumentType.word()).executes(this::swap)))
        .then(
            CommandNode.literal("reconcile")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.argument("gallery", ArgumentType.word()).executes(this::reconcile)))
        .then(
            CommandNode.literal("restore")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.argument("player", ArgumentType.word())
                        .then(
                            CommandNode.argument(
                                    "season", ArgumentType.integer(1, Integer.MAX_VALUE))
                                .executes(this::restore))))
        .then(
            CommandNode.literal("import")
                .permission(PERMISSION_ADMIN)
                .then(
                    CommandNode.literal("undo")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::importUndo)
                                .then(
                                    CommandNode.argument("cell", ArgumentType.id())
                                        .executes(this::importUndo))))
                .then(
                    CommandNode.argument("gallery", ArgumentType.word())
                        .executes(this::importCells)
                        .then(
                            CommandNode.argument("options", ArgumentType.greedy())
                                .executes(this::importCells))))
        .build();
  }

  // Galleries

  void galleryCreate(CommandSource source, Arguments arguments) {
    String id = arguments.string("gallery");
    int season = arguments.integer("season");
    if (!GalleryFiles.ID.matcher(id).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    if (hall.gallery(id).isPresent()) {
      source.reply(messages().get("hof.gallery.exists", "gallery", id));
      return;
    }
    if (config.get().galleriesLeftOut().containsKey(id)) {
      // Its file is there but failed to load; a new one would write over it.
      source.reply(messages().get("hof.gallery.left-out", "gallery", id));
      return;
    }
    String dimension =
        source.player().map(p -> platform.players().position(p).dimension()).orElse(OVERWORLD);
    saved(
        source,
        hall.save(Gallery.create(id, season, dimension)),
        messages().get("hof.gallery.created", "gallery", id, "season", Integer.toString(season)),
        "hof.gallery.create",
        "gallery",
        id);
  }

  void galleryBounds(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Gallery gallery = found.get();
    corners(source, arguments, gallery)
        .ifPresent(bounds -> edits.galleryBounds(source, gallery, bounds));
  }

  /**
   * The box from a {@code corners} argument, or with none, the player's wand selection (questions
   * 228), in the gallery's dimension.
   */
  private Optional<Bounds> corners(CommandSource source, Arguments arguments, Gallery gallery) {
    Optional<String> given = arguments.optionalString("corners");
    if (given.isPresent() && !given.get().isBlank() && !isSelection(given.get())) {
      return typedBox(source, given.get(), gallery.dimension());
    }
    Optional<Bounds> selected = source.player().flatMap(wand::selection);
    if (selected.isEmpty()) {
      source.reply(messages().get("hof.cell.corners"));
      return Optional.empty();
    }
    if (!selected.get().dimension().equals(gallery.dimension())) {
      source.reply(
          messages()
              .get(
                  "hof.wand.other-dimension",
                  "dimension",
                  selected.get().dimension(),
                  "gallery",
                  gallery.id(),
                  "other",
                  gallery.dimension()));
      return Optional.empty();
    }
    return selected;
  }

  void galleryEntry(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    if (edits.held(source, found.get())) {
      return;
    }
    Optional<String> typed = arguments.optionalString("at");
    Optional<Spot> spot;
    if (typed.isPresent()
        && (Splitter.split(typed.get()).length >= 6
            || typed.get().trim().startsWith(Positions.SELECTION))) {
      spot = spot(source, typed, found.get().dimension());
    } else {
      spot =
          position(source, typed, found.get().dimension())
              .map(at -> new Spot(at, Optional.empty()));
    }
    if (spot.isEmpty()) {
      return;
    }
    Position at = spot.get().point();
    Optional<Bounds> area = spot.get().area();
    saved(
        source,
        hall.save(found.get().withEntry(at, area)),
        area.isPresent()
            ? messages()
                .get(
                    "hof.gallery.entry-area",
                    with(areaText(area.get()), "gallery", found.get().id()))
            : messages().get("hof.gallery.entry", place("gallery", found.get().id(), at)));
  }

  void galleryExit(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    if (edits.held(source, found.get())) {
      return;
    }
    Optional<String> typed = arguments.optionalString("at");
    if (typed.isPresent()
        && (Splitter.split(typed.get()).length >= 6
            || typed.get().trim().equals(Positions.SELECTION))) {
      Optional<Spot> spot = spot(source, typed, found.get().dimension());
      if (spot.isEmpty()) {
        return;
      }
      Bounds area = spot.get().area().orElseThrow();
      saved(
          source,
          hall.save(found.get().withExitArea(area)),
          messages()
              .get("hof.gallery.exit-area", with(areaText(area), "gallery", found.get().id())));
      return;
    }
    Optional<Position> at = position(source, typed, found.get().dimension());
    if (at.isEmpty()) {
      return;
    }
    saved(
        source,
        hall.save(found.get().withExit(at.get())),
        messages().get("hof.gallery.exit", place("gallery", found.get().id(), at.get())));
  }

  /** {@code /hof gallery launch <gallery> [at|none]}: sets or removes the launch button. */
  void galleryLaunch(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Gallery gallery = found.get();
    if (edits.held(source, gallery)) {
      return;
    }
    if (arguments.optionalString("at").map(String::trim).filter("none"::equals).isPresent()) {
      saved(
          source,
          hall.save(gallery.withLaunch(Optional.empty())),
          messages().get("hof.gallery.no-launch", "gallery", gallery.id()));
      return;
    }
    Optional<Position> at = position(source, arguments.optionalString("at"), gallery.dimension());
    if (at.isEmpty()) {
      return;
    }
    if (!gallery.contains(at.get())) {
      source.reply(messages().get("hof.gallery.launch-outside", "gallery", gallery.id()));
      return;
    }
    saved(
        source,
        hall.save(gallery.withLaunch(at)),
        messages().get("hof.gallery.launch", place("gallery", gallery.id(), at.get())));
  }

  void galleryInfo(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Gallery gallery = found.get();
    int season = gallery.season();
    long free = gallery.cells().stream().filter(c -> c.owner(season).isEmpty()).count();
    source.reply(
        messages()
            .get(
                "hof.gallery.info",
                "gallery",
                gallery.id(),
                "season",
                Integer.toString(season),
                "cells",
                Integer.toString(gallery.cells().size()),
                "free",
                Long.toString(free)));
    for (Cell cell : gallery.cells()) {
      String owner =
          cell.owner(season).map(this::name).orElseGet(() -> messages().text("hof.gallery.free"));
      source.reply(messages().get("hof.gallery.info-cell", "cell", cell.id(), "owner", owner));
    }
  }

  /** A gallery's next free cell id: {@code c1}, {@code c2}, … */
  private static String nextCellId(Gallery gallery) {
    for (int i = 1; ; i++) {
      String id = "c" + i;
      if (gallery.cell(id).isEmpty()) {
        return id;
      }
    }
  }

  // Gates (questions 250)

  /** {@code open} or {@code shut}, telling the source if it is neither. */
  private Optional<Boolean> shut(CommandSource source, Arguments arguments) {
    String state = arguments.string("state");
    if (state.equals(OPEN) || state.equals(SHUT)) {
      return Optional.of(state.equals(SHUT));
    }
    source.reply(messages().get("hof.gate.open-or-shut"));
    return Optional.empty();
  }

  void cellGate(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    Optional<Boolean> shut = found.isEmpty() ? Optional.empty() : shut(source, arguments);
    if (shut.isEmpty()) {
      return;
    }
    HallService.CellRef ref = found.get();
    saved(
        source,
        hall.save(ref.gallery().withCell(ref.cell().withGateShut(shut.get()))),
        messages().get(shut.get() ? "hof.gate.shut" : "hof.gate.opened", "cell", ref.key()));
  }

  void galleryGate(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    Optional<Boolean> shut = found.isEmpty() ? Optional.empty() : shut(source, arguments);
    if (shut.isEmpty()) {
      return;
    }
    Gallery next = found.get();
    for (Cell cell : found.get().cells()) {
      next = next.withCell(cell.withGateShut(shut.get()));
    }
    saved(
        source,
        hall.save(next),
        messages()
            .get(shut.get() ? "hof.gate.all-shut" : "hof.gate.all-open", "gallery", next.id()));
  }

  /** {@code /hof gate open|shut}: the gate of the cell the player owns in their newest season. */
  void ownerGate(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    Optional<Boolean> shut = shut(source, arguments);
    if (shut.isEmpty()) {
      return;
    }
    PlayerId player = source.player().get();
    Optional<HallService.CellRef> mine =
        hall.galleries().stream()
            .filter(g -> g.kind() == Gallery.Kind.SEASON)
            .sorted(java.util.Comparator.comparingInt(Gallery::season).reversed())
            .flatMap(g -> g.cellOf(player).map(c -> new HallService.CellRef(g, c)).stream())
            .findFirst()
            .filter(ref ->
                ref.gallery().access(config.get().config()) == SeasonsConfig.CellAccess.GATE
                    && ref.cell().gate().isPresent());
    if (mine.isEmpty()) {
      source.reply(messages().get("hof.gate.no-cell"));
      return;
    }
    HallService.CellRef ref = mine.get();
    saved(
        source,
        hall.save(ref.gallery().withCell(ref.cell().withGateShut(shut.get()))),
        messages().get(shut.get() ? "hof.gate.own-shut" : "hof.gate.own-open"));
  }

  // The hub, the Hall spawn and the Hall's exits (questions 243 and 244)

  /** Whether hall.yml was left out at startup, so it mustn't be written over; if so, says so. */
  private boolean hallLeftOut(CommandSource source) {
    if (hall.hallLeftOut()) {
      source.reply(messages().get("hof.hall.left-out"));
      return true;
    }
    return false;
  }

  void hallHub(CommandSource source, Arguments arguments) {
    if (hallLeftOut(source)) {
      return;
    }
    String dimension =
        source.player().map(p -> platform.players().position(p).dimension()).orElse(OVERWORLD);
    Optional<Bounds> box = box(source, arguments, dimension);
    if (box.isEmpty()) {
      return;
    }
    Bounds hub = box.get();
    for (Gallery gallery : hall.galleries()) {
      if (gallery.bounds().map(hub::overlaps).orElse(false)) {
        source.reply(messages().get("hof.hall.hub-overlaps", "gallery", gallery.id()));
        return;
      }
    }
    HallLayout layout = hall.layout();
    if (layout.spawn().isPresent() && !hub.contains(layout.spawn().get())) {
      source.reply(messages().get("hof.hall.hub-spawn", xyz(layout.spawn().get())));
      return;
    }
    saved(
        source,
        hall.saveHall(layout.withHub(hub)),
        messages()
            .get(
                "hof.hall.hub",
                "from",
                CellEdits.corner(hub.min()),
                "to",
                CellEdits.corner(hub.max())),
        "hof.hall.hub");
  }

  /** A box from typed corners in a dimension, or else the player's wand selection. */
  private Optional<Bounds> box(CommandSource source, Arguments arguments, String dimension) {
    Optional<String> given = arguments.optionalString("corners");
    if (given.isPresent() && !given.get().isBlank() && !isSelection(given.get())) {
      return typedBox(source, given.get(), dimension);
    }
    Optional<Bounds> selected = source.player().flatMap(wand::selection);
    if (selected.isEmpty()) {
      source.reply(messages().get("hof.cell.corners"));
    }
    return selected;
  }

  private static boolean isSelection(String text) {
    return text.trim().equals(Positions.SELECTION);
  }

  /** A box typed as two positions, each absolute, relative or {@code here} (questions 264). */
  Optional<Bounds> typedBox(CommandSource source, String text, String dimension) {
    List<String> words = List.of(Splitter.split(text));
    Optional<Position> origin = origin(source);
    Positions.Read first = Positions.block(words, 0, origin, dimension);
    if (first instanceof Positions.Read.Block a) {
      Positions.Read second = Positions.block(words, a.used(), origin, dimension);
      if (second instanceof Positions.Read.Block b && a.used() + b.used() == words.size()) {
        Position p = a.block();
        Position q = b.block();
        return Optional.of(
            Bounds.between(
                dimension, p.blockX(), p.blockY(), p.blockZ(), q.blockX(), q.blockY(), q.blockZ()));
      }
      if (second instanceof Positions.Read.Failed failed
          && failed.messageKey().equals(Positions.CONSOLE)) {
        source.reply(messages().get(Positions.CONSOLE));
        return Optional.empty();
      }
    } else if (first instanceof Positions.Read.Failed failed
        && failed.messageKey().equals(Positions.CONSOLE)) {
      source.reply(messages().get(Positions.CONSOLE));
      return Optional.empty();
    }
    source.reply(messages().get("position.box"));
    return Optional.empty();
  }

  /** A point, or a box from its first corner (questions 279), as typed. */
  private record Spot(Position point, Optional<Bounds> area) {}

  /**
   * A typed point {@code <position> [yaw]}, or a box {@code <position> <position> [yaw]} or {@code
   * sel [yaw]}, at most 32 blocks a side; with nothing typed, where the sender stands.
   */
  private Optional<Spot> spot(CommandSource source, Optional<String> given, String dimension) {
    if (given.isEmpty() || given.get().isBlank()) {
      return standing(source, Optional.empty(), dimension)
          .map(at -> new Spot(at, Optional.empty()));
    }
    List<String> words = new ArrayList<>(List.of(Splitter.split(given.get())));
    Optional<Float> yaw = Optional.empty();
    if (words.size() == 2 || words.size() == 4 || words.size() == 7) {
      yaw = Positions.yaw(words.getLast());
      if (yaw.isPresent()) {
        words.removeLast();
      }
    }
    Optional<Bounds> box;
    if (words.size() == 1 && words.getFirst().equals(Positions.SELECTION)) {
      box = source.player().flatMap(wand::selection);
      if (box.isEmpty()) {
        source.reply(messages().get("hof.cell.corners"));
        return Optional.empty();
      }
    } else if (words.size() == 6
        || (words.size() >= 2 && words.contains(Positions.HERE) && words.size() <= 4)) {
      box = typedBox(source, String.join(" ", words), dimension);
      if (box.isEmpty()) {
        return Optional.empty();
      }
    } else {
      return standing(source, given, dimension).map(at -> new Spot(at, Optional.empty()));
    }
    Bounds b = box.get();
    if (b.maxX() - b.minX() >= GalleryFiles.MAX_AREA
        || b.maxY() - b.minY() >= GalleryFiles.MAX_AREA
        || b.maxZ() - b.minZ() >= GalleryFiles.MAX_AREA) {
      source.reply(
          messages().get("hof.area.too-big", "max", Integer.toString(GalleryFiles.MAX_AREA)));
      return Optional.empty();
    }
    float facing = yaw.orElse(origin(source).map(at -> Wand.facing(at.yaw())).orElse(0f));
    Position min = b.min();
    return Optional.of(
        new Spot(
            new Position(b.dimension(), min.blockX(), min.blockY(), min.blockZ(), facing, 0f),
            Optional.of(b)));
  }

  private static String[] areaText(Bounds b) {
    return new String[] {"from", CellEdits.corner(b.min()), "to", CellEdits.corner(b.max())};
  }

  /** Where the sender stands and faces, or empty for the console. */
  private Optional<Position> origin(CommandSource source) {
    return source.player().map(p -> platform.players().position(p));
  }

  void hallSpawn(CommandSource source, Arguments arguments) {
    if (hallLeftOut(source)) {
      return;
    }
    HallLayout layout = hall.layout();
    if (layout.hub().isEmpty()) {
      source.reply(messages().get("hof.hall.no-hub"));
      return;
    }
    Optional<Spot> spot = spot(source, arguments.optionalString("at"), layout.dimension());
    if (spot.isEmpty()) {
      return;
    }
    Position at = spot.get().point();
    if (!layout.inHub(at)
        || spot.get().area().map(a -> !layout.hub().orElseThrow().contains(a)).orElse(false)) {
      source.reply(messages().get("hof.hall.spawn-outside"));
      return;
    }
    saved(
        source,
        hall.saveHall(layout.withSpawn(at, spot.get().area())),
        spot.get().area().isPresent()
            ? messages().get("hof.hall.spawn-area", areaText(spot.get().area().get()))
            : messages().get("hof.hall.spawn", xyz(at)),
        "hof.hall.spawn");
  }

  /**
   * A spot typed as {@code x y z [yaw]}, or else the block the player stands in, facing their way.
   */
  private Optional<Position> standing(
      CommandSource source, Optional<String> given, String dimension) {
    if (given.isPresent() && !given.get().isBlank()) {
      // Typed: no facing unless typed, but here faces the sender's way, as standing there does.
      Optional<Float> facing =
          given.get().trim().startsWith(Positions.HERE)
              ? origin(source).map(at -> Wand.facing(at.yaw()))
              : Optional.empty();
      return typedPosition(source, given.get(), dimension, facing);
    }
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.cell.anchor-where"));
      return Optional.empty();
    }
    Position at = platform.players().position(source.player().get());
    return Optional.of(
        new Position(
            at.dimension(), at.blockX(), at.blockY(), at.blockZ(), Wand.facing(at.yaw()), 0f));
  }

  void hallExit(CommandSource source, Arguments arguments) {
    if (hallLeftOut(source)) {
      return;
    }
    HallLayout layout = hall.layout();
    if (arguments.optionalString("at").map(String::trim).filter("none"::equals).isPresent()) {
      saved(source, hall.saveHall(layout.withoutExits()), messages().get("hof.hall.no-exits"));
      return;
    }
    Optional<String> typed = arguments.optionalString("at");
    if ((typed.isPresent() && Splitter.split(typed.get()).length >= 6)
        || typed.map(t -> t.trim().equals(Positions.SELECTION)).orElse(false)) {
      Optional<Spot> spot = spot(source, typed, layout.dimension());
      if (spot.isEmpty()) {
        return;
      }
      Bounds area = spot.get().area().orElseThrow();
      if (!layout.hub().map(h -> h.contains(area)).orElse(false)) {
        source.reply(messages().get("hof.hall.exit-outside"));
        return;
      }
      saved(
          source,
          hall.saveHall(layout.withExitArea(area)),
          messages().get("hof.hall.exit-area", areaText(area)));
      return;
    }
    Optional<Position> at = position(source, typed, layout.dimension());
    if (at.isEmpty()) {
      return;
    }
    if (!layout.inHub(at.get())) {
      source.reply(messages().get("hof.hall.exit-outside"));
      return;
    }
    saved(
        source,
        hall.saveHall(layout.withExit(at.get())),
        messages().get("hof.hall.exit", xyz(at.get())));
  }

  void hallCheck(CommandSource source, Arguments arguments) {
    HallCheck.Report report =
        HallCheck.run(
            hall.layout(),
            hall.galleries(),
            at -> platform.world().block(at).type().equals("minecraft:air"),
            at -> platform.world().block(at).type(),
            Gates.blockId(config.get().config().cells().gateBlock()),
            at ->
                platform
                        .world()
                        .block(
                            Position.of(at.dimension(), at.blockX(), at.blockY() - 1, at.blockZ()))
                        .solid()
                    && platform.world().block(at).passable(),
            visits::seasonSpawn,
            messages());
    source.reply(messages().get("hof.check.header"));
    report.findings().forEach(source::reply);
    report.counts().forEach(source::reply);
    int count = report.findings().size();
    source.reply(
        count == 0
            ? messages().get("hof.check.clear")
            : messages()
                .get(
                    count == 1 ? "hof.check.one" : "hof.check.many",
                    "count",
                    Integer.toString(count)));
  }

  // Links (questions 248)

  void linkAdd(CommandSource source, Arguments arguments) {
    if (hallLeftOut(source)) {
      return;
    }
    String id = arguments.string("link");
    if (!HallFiles.ID.matcher(id).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    // [hall | season <n>] [x y z]
    List<String> words =
        new ArrayList<>(List.of(Splitter.split(arguments.optionalString("rest").orElse(""))));
    Optional<HallLayout.Target> to = Optional.empty();
    if (!words.isEmpty() && words.getFirst().equals("hall")) {
      to = Optional.of(new HallLayout.Target.Hall());
      words.removeFirst();
    } else if (words.size() >= 2 && words.getFirst().equals("season")) {
      try {
        int season = Integer.parseInt(words.get(1));
        if (season < 1) {
          throw new NumberFormatException();
        }
        to = Optional.of(new HallLayout.Target.SeasonSpawn(season));
      } catch (NumberFormatException e) {
        source.reply(messages().get("hof.link.usage"));
        return;
      }
      words.subList(0, 2).clear();
    }
    if (!words.isEmpty() && words.getFirst().equals("area")) {
      // A link that is an area, taken by passing through it (questions 279).
      String dimension =
          source.player().map(p -> platform.players().position(p).dimension()).orElse(OVERWORLD);
      Optional<Spot> spot =
          spot(source, Optional.of(String.join(" ", words.subList(1, words.size()))), dimension);
      if (spot.isEmpty()) {
        return;
      }
      if (spot.get().area().isEmpty()) {
        source.reply(messages().get("hof.link.usage"));
        return;
      }
      Bounds area = spot.get().area().get();
      HallLayout.Link link = new HallLayout.Link(id, area.min(), to, Optional.of(area));
      String[] where = with(areaText(area), "link", id);
      saved(
          source,
          hall.saveHall(hall.layout().withLink(link)),
          to.isPresent()
              ? messages()
                  .get(
                      "hof.link.added-area",
                      with(with(areaText(area), "link", id), "dest", describe(to.get())))
              : messages().get("hof.link.added-area-nowhere", where));
      return;
    }
    if (!words.isEmpty() && words.size() != 3) {
      source.reply(messages().get("hof.link.usage"));
      return;
    }
    String dimension =
        source.player().map(p -> platform.players().position(p).dimension()).orElse(OVERWORLD);
    Optional<Position> block =
        position(
            source,
            words.isEmpty() ? Optional.empty() : Optional.of(String.join(" ", words)),
            dimension);
    if (block.isEmpty()) {
      return;
    }
    addLink(
        source,
        id,
        Position.of(dimension, block.get().blockX(), block.get().blockY(), block.get().blockZ()),
        to);
  }

  /** Adds a link, or moves the one with its id, telling the source where it leads. */
  public void addLink(
      CommandSource source, String id, Position block, Optional<HallLayout.Target> to) {
    // The wand comes here too, not only /hof link add: never write over a left-out hall.yml.
    if (hallLeftOut(source)) {
      return;
    }
    HallLayout layout = hall.layout();
    Optional<HallLayout.Link> there = layout.linkAt(block);
    if (there.isPresent() && !there.get().id().equals(id)) {
      source.reply(messages().get("hof.link.taken", place("link", there.get().id(), block)));
      return;
    }
    for (Gallery gallery : hall.galleries()) {
      if (gallery.contains(block) && edits.held(source, gallery)) {
        return;
      }
    }
    HallLayout.Link link = new HallLayout.Link(id, block, to);
    saved(source, hall.saveHall(layout.withLink(link)), added(link));
  }

  private Message added(HallLayout.Link link) {
    String[] where = place("link", link.id(), link.block());
    if (link.to().isEmpty()) {
      return messages().get("hof.link.added-nowhere", where);
    }
    return messages().get("hof.link.added", with(where, "to", describe(link.to().get())));
  }

  /** Where a link leads, as admins read it. */
  private String describe(HallLayout.Target to) {
    return switch (to) {
      case HallLayout.Target.Hall() -> messages().text("hof.link.to-hall");
      case HallLayout.Target.SeasonSpawn(int season) ->
          messages().text("hof.link.to-season", "season", Integer.toString(season));
      case HallLayout.Target.At(Position at) -> CellEdits.corner(at);
    };
  }

  void linkTo(CommandSource source, Arguments arguments) {
    if (hallLeftOut(source)) {
      return;
    }
    String id = arguments.string("link");
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      source.reply(messages().get("hof.link.unknown", "link", id));
      return;
    }
    Optional<Position> at =
        standing(source, arguments.optionalString("at"), link.get().block().dimension());
    if (at.isEmpty()) {
      return;
    }
    HallLayout.Link next = link.get().withTo(Optional.of(new HallLayout.Target.At(at.get())));
    saved(
        source,
        hall.saveHall(hall.layout().withLink(next)),
        messages().get("hof.link.to", place("link", id, at.get())));
  }

  void linkRemove(CommandSource source, Arguments arguments) {
    if (hallLeftOut(source)) {
      return;
    }
    String id = arguments.string("link");
    if (hall.layout().link(id).isEmpty()) {
      source.reply(messages().get("hof.link.unknown", "link", id));
      return;
    }
    saved(
        source,
        hall.saveHall(hall.layout().withoutLink(id)),
        messages().get("hof.link.removed", "link", id));
  }

  void linkList(CommandSource source, Arguments arguments) {
    if (hall.layout().links().isEmpty()) {
      source.reply(messages().get("hof.link.none"));
      return;
    }
    for (HallLayout.Link link : hall.layout().links().values()) {
      String[] where = place("link", link.id(), link.block());
      source.reply(
          link.to().isEmpty()
              ? messages().get("hof.link.line-nowhere", where)
              : messages().get("hof.link.line", with(where, "to", describe(link.to().get()))));
    }
  }

  private static String[] xyz(Position at) {
    return new String[] {
      "x",
      Integer.toString(at.blockX()),
      "y",
      Integer.toString(at.blockY()),
      "z",
      Integer.toString(at.blockZ())
    };
  }

  private static String[] with(String[] placeholders, String key, String value) {
    String[] all = java.util.Arrays.copyOf(placeholders, placeholders.length + 2);
    all[placeholders.length] = key;
    all[placeholders.length + 1] = value;
    return all;
  }

  // Cells

  void cellNew(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    // With no id, the gallery's next free c1, c2, … (questions 254).
    String id = arguments.optionalString("id").orElseGet(() -> nextCellId(found.get()));
    if (!GalleryFiles.ID.matcher(id).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    Gallery gallery = found.get();
    if (edits.held(source, gallery)) {
      return;
    }
    if (gallery.cell(id).isPresent()) {
      source.reply(messages().get("hof.cell.exists", "cell", gallery.id() + ":" + id));
      return;
    }
    Cell cell = Cell.create(id);
    Gallery next = gallery.withCell(cell);
    source.player().ifPresent(p -> wand.focus(p, new HallService.CellRef(next, cell)));
    saved(
        source,
        hall.save(next),
        messages().get("hof.cell.created", "cell", id, "gallery", gallery.id()),
        "hof.cell.new",
        "cell",
        gallery.id() + ":" + id);
  }

  void cellBounds(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    HallService.CellRef ref = found.get();
    corners(source, arguments, ref.gallery())
        .ifPresent(bounds -> edits.cellBounds(source, ref, bounds));
  }

  void cellAnchor(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Optional<AnchorType> type = AnchorType.of(arguments.string("type"));
    if (type.isEmpty()) {
      source.reply(messages().get("hof.cell.unknown-anchor"));
      return;
    }
    HallService.CellRef ref = found.get();
    if (ref.cell().bounds().isEmpty()) {
      source.reply(messages().get("hof.cell.anchor-no-bounds", "cell", ref.key()));
      return;
    }
    Optional<String> typed = arguments.optionalString("at");
    if (type.get() == AnchorType.ENTRY
        && typed.isPresent()
        && (Splitter.split(typed.get()).length >= 6
            || typed.get().trim().startsWith(Positions.SELECTION))) {
      // An entry may be a box arrivals spread over (questions 279).
      Optional<Spot> spot = spot(source, typed, ref.gallery().dimension());
      if (spot.isPresent()) {
        edits.anchor(
            source,
            ref,
            Anchor.of(AnchorType.ENTRY, spot.get().point()).withArea(spot.get().area()));
      }
      return;
    }
    Optional<Position> at = position(source, typed, ref.gallery().dimension());
    if (at.isEmpty()) {
      return;
    }
    edits.anchor(source, ref, Anchor.of(type.get(), at.get()));
  }

  void cellUnanchor(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Optional<AnchorType> type = AnchorType.of(arguments.string("type"));
    if (type.isEmpty()) {
      source.reply(messages().get("hof.cell.unknown-anchor"));
      return;
    }
    HallService.CellRef ref = found.get();
    int count = ref.cell().anchors(type.get()).size();
    saved(
        source,
        hall.save(ref.gallery().withCell(ref.cell().withoutAnchors(type.get()))),
        messages()
            .get(
                "hof.cell.unanchored",
                "count",
                Integer.toString(count),
                "type",
                type.get().id(),
                "cell",
                ref.key()));
  }

  void cellAssign(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    String name = arguments.string("player");
    Optional<PlayerId> player = player(name);
    if (player.isEmpty()) {
      source.reply(messages().get("hof.unknown-player", "name", name));
      return;
    }
    HallService.CellRef ref = found.get();
    int season =
        arguments.values().containsKey("season")
            ? arguments.integer("season")
            : ref.gallery().season();
    Optional<PlayerId> before = ref.cell().owner(season);
    if (before.isPresent() && !before.get().equals(player.get())) {
      renderer.clear(ref);
    }
    // A player owns one cell a season: any other cell of theirs is freed.
    Optional<HallService.CellRef> other = hall.cellOf(player.get(), season);
    if (other.isPresent() && !other.get().key().equals(ref.key())) {
      renderer.clear(other.get());
      hall.assign(other.get(), season, Optional.empty());
    }
    hall.assign(ref, season, player);
    var _ = renderer.render(player.get(), season);
    source.reply(
        messages()
            .get(
                "hof.cell.assigned",
                "cell",
                ref.key(),
                "name",
                name(player.get()),
                "season",
                Integer.toString(season)));
  }

  void cellUnassign(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    HallService.CellRef ref = found.get();
    int season =
        arguments.values().containsKey("season")
            ? arguments.integer("season")
            : ref.gallery().season();
    renderer.clear(ref);
    hall.assign(ref, season, Optional.empty());
    source.reply(
        messages()
            .get("hof.cell.unassigned", "cell", ref.key(), "season", Integer.toString(season)));
  }

  void cellInfo(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    HallService.CellRef ref = found.get();
    int season = ref.gallery().season();
    Messages messages = messages();
    String owner =
        ref.cell()
            .owner(season)
            .map(id -> messages.text("hof.cell.info-owner", "name", name(id)))
            .orElseGet(() -> messages.text("hof.cell.info-free"));
    List<String> anchors = new ArrayList<>();
    for (AnchorType type : AnchorType.values()) {
      int count = ref.cell().anchors(type).size();
      if (count > 0) {
        anchors.add(type.id() + " " + count);
      }
    }
    source.reply(
        messages.get(
            "hof.cell.info",
            "cell",
            ref.key(),
            "owner",
            owner,
            "season",
            Integer.toString(season),
            "anchors",
            anchors.isEmpty() ? messages.text("hof.cell.info-none") : String.join(", ", anchors)));
  }

  // Swaps and displays

  /**
   * {@code /hof}, {@code /hof <season>} and {@code /hof <player>}. The node has no permission of
   * its own, since the admin commands hang off it, so the visit permission is checked here.
   */
  void visit(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = visitor(source);
    if (player.isPresent()) {
      visits.visit(player.get(), arguments.optionalString("target"));
    }
  }

  void leave(CommandSource source, Arguments arguments) {
    visitor(source).ifPresent(visits::leave);
  }

  private Optional<PlayerId> visitor(CommandSource source) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return Optional.empty();
    }
    if (!platform.permissions().has(source.player().get(), HallVisits.PERMISSION_VISIT)) {
      source.reply(messages().get("hof.no-permission"));
      return Optional.empty();
    }
    return source.player();
  }

  void swap(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    PlayerId asker = source.player().get();
    String name = arguments.string("player");
    Optional<PlayerRef> other = platform.players().findByName(name);
    if (other.isEmpty()) {
      source.reply(messages().get("hof.swap.offline", "name", name));
      return;
    }
    String askerName = name(asker);
    switch (hall.swap(asker, other.get().id())) {
      case HallService.SwapResult.Refused refused ->
          source.reply(messages().get(refused.messageKey(), "name", other.get().name()));
      case HallService.SwapResult.Asked asked -> {
        source.reply(
            messages().get("hof.swap.asked", "name", other.get().name(), "you", askerName));
        platform
            .players()
            .sendMessage(other.get().id(), messages().get("hof.swap.request", "name", askerName));
      }
      case HallService.SwapResult.Swapped swapped -> {
        source.reply(
            messages()
                .get(
                    "hof.swap.done",
                    "name",
                    other.get().name(),
                    "cell",
                    swapped.cell().cell().id()));
        platform
            .players()
            .sendMessage(
                other.get().id(),
                messages()
                    .get("hof.swap.done", "name", askerName, "cell", swapped.theirs().cell().id()));
      }
    }
  }

  void claim(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    switch (hall.claim(source.player().get(), arguments.optionalString("cell"))) {
      case HallService.ClaimResult.Refused refused ->
          source.reply(
              messages().get(refused.messageKey(), refused.placeholders().toArray(String[]::new)));
      case HallService.ClaimResult.Claimed claimed ->
          source.reply(
              messages()
                  .get(
                      "hof.assigned",
                      "season",
                      Integer.toString(claimed.cell().gallery().season()),
                      "cell",
                      claimed.cell().cell().id(),
                      "gallery",
                      claimed.cell().gallery().id()));
      case HallService.ClaimResult.Free free ->
          source.reply(
              messages()
                  .get(
                      "hof.claim.free",
                      "cells",
                      String.join(
                          ", ", free.cells().stream().map(ref -> ref.cell().id()).toList())));
    }
  }

  void reconcile(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Gallery gallery = found.get();
    var _ =
        reconciler
            .reconcile(gallery)
            .whenComplete((repairs, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      if (failure != null) {
                        LOG.error("Reconciling gallery {} failed", gallery.id(), failure);
                        source.reply(
                            messages()
                                .get(
                                    "hof.reconcile-failed",
                                    "gallery",
                                    gallery.id(),
                                    "error",
                                    String.valueOf(failure.getMessage())));
                      } else if (repairs == 1) {
                        source.reply(messages().get("hof.reconciled-one", "gallery", gallery.id()));
                      } else {
                        source.reply(
                            messages()
                                .get(
                                    "hof.reconciled",
                                    "count",
                                    Integer.toString(repairs),
                                    "gallery",
                                    gallery.id()));
                      }
                    }));
  }

  void restore(CommandSource source, Arguments arguments) {
    String name = arguments.string("player");
    int season = arguments.integer("season");
    Optional<PlayerId> player = player(name);
    if (player.isEmpty()) {
      source.reply(messages().get("hof.unknown-player", "name", name));
      return;
    }
    var _ =
        renderer
            .render(player.get(), season)
            .whenComplete((done, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() ->
                        source.reply(
                            messages()
                                .get(
                                    failure == null ? "hof.restored" : "hof.no-archive",
                                    "name",
                                    name(player.get()),
                                    "season",
                                    Integer.toString(season)))));
  }

  // Import

  void importCells(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty() || importer.isEmpty()) {
      if (found.isPresent()) {
        source.reply(messages().get("archive.unavailable"));
      }
      return;
    }
    Gallery gallery = found.get();
    boolean dryRun = false;
    Optional<String> only = Optional.empty();
    for (String word : Splitter.split(arguments.optionalString("options").orElse("").trim())) {
      if (word.isEmpty()) {
        continue;
      }
      if (word.equals(DRY_RUN)) {
        dryRun = true;
      } else if (word.startsWith("--")) {
        source.reply(messages().get("hof.import.unknown-flag", "flag", word));
        return;
      } else {
        only = Optional.of(word);
      }
    }
    if (only.isPresent() && gallery.cell(only.get()).isEmpty()) {
      source.reply(messages().get("hof.no-cell", "cell", gallery.id() + ":" + only.get()));
      return;
    }
    var _ =
        importer
            .get()
            .run(gallery, only, dryRun, actor(source))
            .whenComplete((result, failure) ->
                platform.scheduler().runGlobal(() -> reply(source, gallery, result, failure)));
  }

  void importUndo(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty() || importer.isEmpty()) {
      return;
    }
    Gallery gallery = found.get();
    Optional<String> only = arguments.optionalString("cell");
    var _ =
        importer
            .get()
            .undo(gallery, only, actor(source))
            .whenComplete((result, failure) ->
                platform.scheduler().runGlobal(() -> reply(source, gallery, result, failure)));
  }

  private void reply(
      CommandSource source,
      Gallery gallery,
      HallImporter.@Nullable Result result,
      @Nullable Throwable failure) {
    Messages messages = messages();
    String id = gallery.id();
    if (failure != null || result == null) {
      source.reply(
          messages.get(
              "hof.import.stopped", "gallery", id, "cell", "?", "reason", String.valueOf(failure)));
      return;
    }
    source.reply(
        switch (result) {
          case HallImporter.Result.DryRun dry ->
              messages.get(
                  dry.ready() == 1 ? "hof.import.dry-run" : "hof.import.dry-run-many",
                  "gallery",
                  id,
                  "ready",
                  Integer.toString(dry.ready()),
                  "flagged",
                  Integer.toString(dry.flagged()),
                  "file",
                  dry.report());
          case HallImporter.Result.Imported done ->
              messages.get(
                  done.imported() == 1 ? "hof.import.done" : "hof.import.done-many",
                  "gallery",
                  id,
                  "count",
                  Integer.toString(done.imported()),
                  "skipped",
                  Integer.toString(done.skipped()));
          case HallImporter.Result.Stopped stopped ->
              messages.get(
                  "hof.import.stopped",
                  "gallery",
                  id,
                  "cell",
                  stopped.cell(),
                  "reason",
                  stopped.reason());
          case HallImporter.Result.Undone undone ->
              messages.get(
                  undone.cells() == 1 ? "hof.import.undone" : "hof.import.undone-many",
                  "gallery",
                  id,
                  "count",
                  Integer.toString(undone.cells()));
          case HallImporter.Result.UndoStopped stopped ->
              messages.get(
                  "hof.import.undo-stopped", "cell", stopped.cell(), "reason", stopped.reason());
          case HallImporter.Result.Busy busy -> messages.get("hof.import.busy");
        });
  }

  // Stamping

  void stamp(CommandSource source, Arguments arguments) {
    if (stamper.isEmpty()) {
      source.reply(messages().get("hof.stamp.unavailable"));
      return;
    }
    stamper.get().stamp(source, arguments);
  }

  // Outlines and undo (questions 275 and 276)

  private Optional<HallOutlines> outlines = Optional.empty();

  /** Where {@code /hof show} goes. */
  public void bindOutlines(HallOutlines next) {
    this.outlines = Optional.of(next);
  }

  private void show(CommandSource source, boolean on) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    outlines.ifPresent(o -> o.show(source.player().get(), on));
    source.reply(messages().get(on ? "hof.show.on" : "hof.show.off"));
  }

  void undo(CommandSource source, Arguments arguments) {
    switch (hall.undo(source)) {
      case HallService.UndoResult.Nothing nothing ->
          source.reply(messages().get("hof.undo.nothing"));
      case HallService.UndoResult.Changed changed ->
          source.reply(messages().get("hof.undo.changed", "file", changed.file()));
      case HallService.UndoResult.Undone undone ->
          edits.saved(
              source,
              undone.saved(),
              messages().get("hof.undo.done", "file", undone.file()),
              () -> {
                hall.audited(actor(source), "undo", undone.file(), "");
                undone
                    .gallery()
                    .ifPresent(g -> {
                      var _ = reconciler.reconcile(g);
                    });
              });
    }
  }

  // Admin teleports (questions 271)

  private Optional<PlayerId> admin(CommandSource source) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
    }
    return source.player();
  }

  void tpHub(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = admin(source);
    if (player.isEmpty()) {
      return;
    }
    Optional<Position> spawn = visits.hallEntry();
    if (spawn.isEmpty()) {
      source.reply(messages().get("hof.visit.no-spawn"));
      return;
    }
    visits.adminGo(player.get(), spawn.get());
  }

  void tpGallery(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = admin(source);
    Optional<Gallery> found = player.isEmpty() ? Optional.empty() : gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Optional<Position> to = hall.galleryEntry(found.get());
    if (to.isEmpty()) {
      source.reply(messages().get("hof.tp.nowhere", "what", found.get().id()));
      return;
    }
    visits.adminGo(player.get(), to.get());
  }

  void tpCell(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = admin(source);
    Optional<HallService.CellRef> found =
        player.isEmpty() ? Optional.empty() : cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Optional<Position> to =
        hall.arrival(found.get()).or(() -> hall.galleryEntry(found.get().gallery()));
    if (to.isEmpty()) {
      source.reply(messages().get("hof.tp.nowhere", "what", found.get().key()));
      return;
    }
    visits.adminGo(player.get(), to.get());
  }

  void tpPlayer(CommandSource source, Arguments arguments) {
    admin(source).ifPresent(p -> visits.adminGoTo(p, arguments.string("player")));
  }

  void tpLink(CommandSource source, Arguments arguments) {
    Optional<PlayerId> player = admin(source);
    if (player.isEmpty()) {
      return;
    }
    String id = arguments.string("link");
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      source.reply(messages().get("hof.link.unknown", "link", id));
      return;
    }
    Position block = link.get().block();
    // Beside the link, on top of its block: a plate is stood on, a button is beside a wall.
    visits.adminGo(
        player.get(),
        HallService.standOn(
            Position.of(block.dimension(), block.blockX(), block.blockY() + 1, block.blockZ())));
  }

  // Helpers

  /** Replies once the gallery file is written, so the file on disk matches what was said. */
  private void saved(CommandSource source, CompletableFuture<Void> save, Message message) {
    edits.saved(source, save, message);
  }

  /** As {@link #saved}, offering the next steps once it is written (questions 268). */
  private void saved(
      CommandSource source,
      CompletableFuture<Void> save,
      Message message,
      String hint,
      String... placeholders) {
    edits.saved(source, save, message, () -> help.after(source, hint, placeholders));
  }

  private Optional<Gallery> gallery(CommandSource source, Arguments arguments) {
    String id = arguments.string("gallery");
    Optional<Gallery> gallery = hall.gallery(id);
    if (gallery.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", id));
    }
    return gallery;
  }

  private Optional<HallService.CellRef> cell(CommandSource source, Arguments arguments) {
    String name = arguments.string("cell");
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

  /** A position from {@code x y z [yaw]}, or the block the player looks at. */
  private Optional<Position> position(
      CommandSource source, Optional<String> given, String dimension) {
    if (given.isPresent() && !given.get().isBlank()) {
      return typedPosition(source, given.get(), dimension, Optional.empty());
    }
    Optional<Position> looked =
        source.player().flatMap(p -> platform.players().targetBlock(p, REACH));
    if (looked.isEmpty()) {
      source.reply(messages().get("hof.cell.anchor-where"));
      return Optional.empty();
    }
    float yaw = source.player().map(p -> platform.players().position(p).yaw() + 180f).orElse(0f);
    Position block = looked.get();
    return Optional.of(
        new Position(block.dimension(), block.blockX(), block.blockY(), block.blockZ(), yaw, 0f));
  }

  /**
   * A position typed as {@code <position> [yaw]} (questions 264); with no yaw, {@code yaw} or else
   * 0.
   */
  Optional<Position> typedPosition(
      CommandSource source, String text, String dimension, Optional<Float> yaw) {
    List<String> words = List.of(Splitter.split(text));
    Positions.Read read = Positions.block(words, 0, origin(source), dimension);
    if (read instanceof Positions.Read.Block block) {
      Optional<Float> typedYaw = Optional.empty();
      if (words.size() == block.used() + 1) {
        typedYaw = Positions.yaw(words.get(block.used()));
      }
      if (words.size() == block.used() || typedYaw.isPresent()) {
        Position at = block.block();
        return Optional.of(
            new Position(
                dimension,
                at.blockX(),
                at.blockY(),
                at.blockZ(),
                typedYaw.or(() -> yaw).orElse(0f),
                0f));
      }
      source.reply(messages().get(Positions.BAD));
      return Optional.empty();
    }
    source.reply(messages().get(((Positions.Read.Failed) read).messageKey()));
    return Optional.empty();
  }

  private static String[] place(String key, String value, Position at) {
    return new String[] {
      key,
      value,
      "x",
      Integer.toString(at.blockX()),
      "y",
      Integer.toString(at.blockY()),
      "z",
      Integer.toString(at.blockZ())
    };
  }

  /** A player by online name, last known name, or UUID. */
  private Optional<PlayerId> player(String name) {
    Optional<PlayerRef> online = platform.players().findByName(name);
    if (online.isPresent()) {
      return Optional.of(online.get().id());
    }
    Optional<PlayerRecord> known =
        players.all().stream()
            .filter(
                r -> r.lastName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))
            .findFirst();
    if (known.isPresent()) {
      return Optional.of(known.get().id());
    }
    try {
      return Optional.of(new PlayerId(UUID.fromString(name)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
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
