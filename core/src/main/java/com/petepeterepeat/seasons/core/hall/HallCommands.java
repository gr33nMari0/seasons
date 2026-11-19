// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
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
 * The {@code /hof} command tree: tagging galleries and cells (the wand comes in M5), assigning and
 * swapping cells, redrawing displays, and the Season 1 import.
 */
public final class HallCommands {
  /** Permission for the admin commands: galleries, cells, import, reconcile. */
  public static final String PERMISSION_ADMIN = "seasons.admin.hof";

  /** Permission for {@code /hof swap}. */
  public static final String PERMISSION_SWAP = "seasons.hof.swap";

  /** {@code /hof claim}. */
  public static final String PERMISSION_CLAIM = "seasons.hof.claim";

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
      PlayerStore players) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.visits = visits;
    this.renderer = renderer;
    this.reconciler = reconciler;
    this.importer = importer;
    this.players = players;
  }

  public CommandNode root() {
    return CommandNode.literal("hof")
        .executes(this::visit)
        .then(CommandNode.literal("leave").executes(this::leave))
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
                                .then(
                                    CommandNode.argument("id", ArgumentType.word())
                                        .executes(this::cellNew))))
                .then(
                    CommandNode.literal("bounds")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
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
        messages().get("hof.gallery.created", "gallery", id, "season", Integer.toString(season)));
  }

  void galleryBounds(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    Optional<int[]> corners = numbers(arguments.string("corners"), 6);
    if (found.isEmpty()) {
      return;
    }
    if (corners.isEmpty()) {
      source.reply(messages().get("hof.cell.anchor-where"));
      return;
    }
    Gallery gallery = found.get();
    int[] c = corners.get();
    Bounds bounds = Bounds.between(gallery.dimension(), c[0], c[1], c[2], c[3], c[4], c[5]);
    for (Gallery other : hall.galleries()) {
      if (!other.id().equals(gallery.id())
          && other.bounds().map(b -> b.overlaps(bounds)).orElse(false)) {
        source.reply(
            messages().get("hof.gallery.overlaps", "gallery", gallery.id(), "other", other.id()));
        return;
      }
    }
    for (Cell cell : gallery.cells()) {
      if (cell.bounds().isPresent() && !bounds.contains(cell.bounds().get())) {
        source.reply(
            messages()
                .get("hof.gallery.cells-outside", "gallery", gallery.id(), "cell", cell.id()));
        return;
      }
    }
    saved(
        source,
        hall.save(gallery.withBounds(bounds)),
        messages()
            .get(
                "hof.gallery.bounds",
                "gallery",
                gallery.id(),
                "from",
                corner(bounds.min()),
                "to",
                corner(bounds.max())));
  }

  void galleryEntry(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Optional<Position> at =
        position(source, arguments.optionalString("at"), found.get().dimension());
    if (at.isEmpty()) {
      return;
    }
    saved(
        source,
        hall.save(found.get().withEntry(at.get())),
        messages().get("hof.gallery.entry", place("gallery", found.get().id(), at.get())));
  }

  void galleryExit(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Optional<Position> at =
        position(source, arguments.optionalString("at"), found.get().dimension());
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

  // Cells

  void cellNew(CommandSource source, Arguments arguments) {
    Optional<Gallery> found = gallery(source, arguments);
    String id = arguments.string("id");
    if (found.isEmpty()) {
      return;
    }
    if (!GalleryFiles.ID.matcher(id).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    Gallery gallery = found.get();
    if (gallery.cell(id).isPresent()) {
      source.reply(messages().get("hof.cell.exists", "cell", gallery.id() + ":" + id));
      return;
    }
    saved(
        source,
        hall.save(gallery.withCell(Cell.create(id))),
        messages().get("hof.cell.created", "cell", id, "gallery", gallery.id()));
  }

  void cellBounds(CommandSource source, Arguments arguments) {
    Optional<HallService.CellRef> found = cell(source, arguments);
    if (found.isEmpty()) {
      return;
    }
    Optional<int[]> corners = numbers(arguments.string("corners"), 6);
    if (corners.isEmpty()) {
      source.reply(messages().get("hof.cell.anchor-where"));
      return;
    }
    HallService.CellRef ref = found.get();
    Gallery gallery = ref.gallery();
    int[] c = corners.get();
    Bounds bounds = Bounds.between(gallery.dimension(), c[0], c[1], c[2], c[3], c[4], c[5]);
    if (gallery.bounds().isEmpty() || !gallery.bounds().get().contains(bounds)) {
      source.reply(
          messages().get("hof.cell.outside-gallery", "cell", ref.key(), "gallery", gallery.id()));
      return;
    }
    for (Cell other : gallery.cells()) {
      if (!other.id().equals(ref.cell().id())
          && other.bounds().map(b -> b.overlaps(bounds)).orElse(false)) {
        source.reply(
            messages()
                .get(
                    "hof.cell.overlaps",
                    "cell",
                    ref.key(),
                    "other",
                    gallery.id() + ":" + other.id()));
        return;
      }
    }
    saved(
        source,
        hall.save(gallery.withCell(ref.cell().withBounds(bounds))),
        messages()
            .get(
                "hof.cell.bounds",
                "cell",
                ref.key(),
                "from",
                corner(bounds.min()),
                "to",
                corner(bounds.max())));
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
    Optional<Position> at =
        position(source, arguments.optionalString("at"), ref.gallery().dimension());
    if (at.isEmpty()) {
      return;
    }
    if (!ref.cell().contains(at.get())) {
      source.reply(messages().get("hof.cell.anchor-outside", "cell", ref.key()));
      return;
    }
    CompletableFuture<Void> save =
        hall.save(ref.gallery().withCell(ref.cell().withAnchor(Anchor.of(type.get(), at.get()))));
    String name = type.get().id();
    String key = "aeiou".indexOf(name.charAt(0)) >= 0 ? "hof.cell.anchor-an" : "hof.cell.anchor";
    String[] placeholders = place("cell", ref.key(), at.get());
    String[] all = new String[placeholders.length + 2];
    System.arraycopy(placeholders, 0, all, 0, placeholders.length);
    all[placeholders.length] = "type";
    all[placeholders.length + 1] = name;
    saved(source, save, messages().get(key, all));
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

  // Helpers

  /** Replies once the gallery file is written, so the file on disk matches what was said. */
  private void saved(CommandSource source, CompletableFuture<Void> save, Message message) {
    var _ =
        save.whenComplete((done, failure) ->
            platform
                .scheduler()
                .runGlobal(() ->
                    source.reply(
                        failure == null
                            ? message
                            : messages()
                                .get(
                                    "hof.save-failed",
                                    "error",
                                    String.valueOf(failure.getMessage())))));
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
      String[] words = Splitter.split(given.get().trim());
      if (words.length == 3 || words.length == 4) {
        try {
          float yaw = words.length == 4 ? Float.parseFloat(words[3]) : 0f;
          return Optional.of(
              new Position(
                  dimension,
                  Integer.parseInt(words[0]),
                  Integer.parseInt(words[1]),
                  Integer.parseInt(words[2]),
                  yaw,
                  0f));
        } catch (NumberFormatException e) {
          // Falls through to the reply below.
        }
      }
      source.reply(messages().get("hof.cell.anchor-where"));
      return Optional.empty();
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

  private static Optional<int[]> numbers(String text, int count) {
    String[] words = Splitter.split(text.trim());
    if (words.length != count) {
      return Optional.empty();
    }
    int[] values = new int[count];
    try {
      for (int i = 0; i < count; i++) {
        values[i] = Integer.parseInt(words[i]);
      }
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    return Optional.of(values);
  }

  private static String corner(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
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
