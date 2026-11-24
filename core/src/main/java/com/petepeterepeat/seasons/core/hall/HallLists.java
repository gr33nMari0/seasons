// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Positions;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Listing, showing, moving and removing the Hall's parts (questions 269 and 270): {@code /hof
 * list}, {@code /hof locate}, {@code /hof hall info}, {@code /hof gallery list|remove}, {@code /hof
 * cell list|remove} and {@code /hof link info|move}. Merged into the {@code /hof} tree.
 */
public final class HallLists {
  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final PlayerStore players;
  private final CellEdits edits;

  public HallLists(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      PlayerStore players,
      CellEdits edits) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.players = players;
    this.edits = edits;
  }

  /** The nodes these add under {@code /hof}. */
  public CommandNode root() {
    String admin = HallCommands.PERMISSION_ADMIN;
    return CommandNode.literal("hof")
        .then(CommandNode.literal("list").permission(admin).executes(this::list))
        .then(
            CommandNode.literal("locate")
                .permission(admin)
                .executes(this::locate)
                .then(CommandNode.argument("target", ArgumentType.id()).executes(this::locate)))
        .then(
            CommandNode.literal("hall")
                .permission(admin)
                .then(CommandNode.literal("info").executes(this::hallInfo))
                .then(
                    CommandNode.literal("exit")
                        .then(CommandNode.literal("list").executes(this::exitList))))
        .then(
            CommandNode.literal("gallery")
                .permission(admin)
                .then(CommandNode.literal("list").executes(this::galleryList))
                .then(
                    CommandNode.literal("remove")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::galleryRemove))))
        .then(
            CommandNode.literal("cell")
                .permission(admin)
                .then(
                    CommandNode.literal("list")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .executes(this::cellList)
                                .then(
                                    CommandNode.argument("which", ArgumentType.word())
                                        .suggests(
                                            (source, typed) -> java.util.List.of("free", "owned"))
                                        .executes(this::cellList))))
                .then(
                    CommandNode.literal("remove")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .executes(this::cellRemove))))
        .then(
            CommandNode.literal("link")
                .then(
                    CommandNode.literal("info")
                        .permission(LinkService.PERMISSION_ADMIN)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::linkInfo)))
                .then(
                    CommandNode.literal("move")
                        .permission(LinkService.PERMISSION_ADMIN)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::linkMove)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::linkMove)))))
        .build();
  }

  // The Hall at a glance

  void list(CommandSource source, Arguments arguments) {
    hallLine(source);
    galleryList(source, arguments);
  }

  private void hallLine(CommandSource source) {
    HallLayout layout = hall.layout();
    Messages messages = messages();
    String spawn =
        layout
            .spawn()
            .map(at -> messages.text("hof.list.spawn", "at", CellEdits.corner(at)))
            .orElseGet(() -> messages.text("hof.list.no-spawn"));
    source.reply(
        layout
            .hub()
            .map(hub ->
                messages.get(
                    "hof.list.hub",
                    "from",
                    CellEdits.corner(hub.min()),
                    "to",
                    CellEdits.corner(hub.max()),
                    "spawn",
                    spawn))
            .orElseGet(() -> messages.get("hof.list.no-hub", "spawn", spawn)));
  }

  void galleryList(CommandSource source, Arguments arguments) {
    if (hall.galleries().isEmpty()) {
      source.reply(messages().get("hof.list.no-galleries"));
      return;
    }
    for (Gallery gallery : hall.galleries()) {
      int season = gallery.season();
      long owned = gallery.cells().stream().filter(c -> c.owner(season).isPresent()).count();
      String kind =
          switch (gallery.kind()) {
            case SEASON -> "Season " + season;
            case MUSEUM -> messages().text("hof.list.museum");
            case LEGACY -> messages().text("hof.list.legacy", "season", Integer.toString(season));
          };
      source.reply(
          messages()
              .get(
                  "hof.list.gallery",
                  "gallery",
                  gallery.id(),
                  "kind",
                  kind,
                  "cells",
                  Integer.toString(gallery.cells().size()),
                  "owned",
                  Long.toString(owned),
                  "free",
                  Long.toString(gallery.cells().size() - owned)));
    }
  }

  void hallInfo(CommandSource source, Arguments arguments) {
    hallLine(source);
    HallLayout layout = hall.layout();
    source.reply(
        messages()
            .get(
                "hof.list.parts",
                "exits",
                Integer.toString(layout.exits().size()),
                "links",
                Integer.toString(layout.links().size())));
  }

  void exitList(CommandSource source, Arguments arguments) {
    HallLayout layout = hall.layout();
    if (layout.exits().isEmpty()) {
      source.reply(messages().get("hof.list.no-exits"));
      return;
    }
    for (Position exit : layout.exits()) {
      source.reply(messages().get("hof.list.exit", "at", CellEdits.corner(exit)));
    }
  }

  // Galleries and cells

  void galleryRemove(CommandSource source, Arguments arguments) {
    String id = arguments.string("gallery");
    Optional<Gallery> found = hall.gallery(id);
    if (found.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", id));
      return;
    }
    Gallery gallery = found.get();
    if (edits.held(source, gallery)) {
      return;
    }
    Optional<Cell> owned = gallery.cells().stream().filter(c -> !c.owners().isEmpty()).findFirst();
    if (owned.isPresent()) {
      source.reply(
          messages().get("hof.gallery.remove-owned", "gallery", id, "cell", owned.get().id()));
      return;
    }
    edits.saved(
        source,
        hall.remove(gallery),
        messages().get("hof.gallery.removed", "gallery", id),
        () -> hall.audited(actor(source), "gallery-remove", id, gallery.cells().size() + " cells"));
  }

  void cellList(CommandSource source, Arguments arguments) {
    String id = arguments.string("gallery");
    Optional<Gallery> found = hall.gallery(id);
    if (found.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", id));
      return;
    }
    Optional<String> which = arguments.optionalString("which");
    if (which.isPresent() && !which.get().equals("free") && !which.get().equals("owned")) {
      source.reply(messages().get("hof.cell.list-which"));
      return;
    }
    Gallery gallery = found.get();
    int season = gallery.season();
    int shown = 0;
    for (Cell cell : gallery.cells()) {
      Optional<PlayerId> owner = cell.owner(season);
      if (which.isPresent() && which.get().equals("free") == owner.isPresent()) {
        continue;
      }
      shown++;
      source.reply(
          messages()
              .get(
                  "hof.gallery.info-cell",
                  "cell",
                  cell.id(),
                  "owner",
                  owner.map(this::name).orElseGet(() -> messages().text("hof.gallery.free"))));
    }
    if (shown == 0) {
      source.reply(messages().get("hof.cell.list-none", "gallery", id));
    }
  }

  void cellRemove(CommandSource source, Arguments arguments) {
    String name = arguments.string("cell");
    Optional<HallService.CellRef> found =
        switch (hall.resolve(name)) {
          case HallService.Resolved.Found f -> Optional.of(f.ref());
          case HallService.Resolved.NotFound n -> {
            source.reply(messages().get("hof.no-cell", "cell", name));
            yield Optional.empty();
          }
          case HallService.Resolved.Ambiguous a -> {
            source.reply(messages().get("hof.ambiguous-cell", "cell", name));
            yield Optional.empty();
          }
        };
    if (found.isEmpty()) {
      return;
    }
    HallService.CellRef ref = found.get();
    if (edits.held(source, ref.gallery())) {
      return;
    }
    Optional<Map.Entry<Integer, PlayerId>> owner =
        ref.cell().owners().entrySet().stream().min(Map.Entry.comparingByKey());
    if (owner.isPresent()) {
      source.reply(
          messages()
              .get(
                  "hof.cell.remove-owned",
                  "cell",
                  ref.key(),
                  "name",
                  name(owner.get().getValue()),
                  "season",
                  Integer.toString(owner.get().getKey())));
      return;
    }
    edits.saved(
        source,
        hall.save(ref.gallery().withoutCell(ref.cell().id())),
        messages().get("hof.cell.removed", "cell", ref.key()),
        () -> hall.audited(actor(source), "cell-remove", ref.key(), ""));
  }

  // Links

  void linkInfo(CommandSource source, Arguments arguments) {
    String id = arguments.string("link");
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      source.reply(messages().get("hof.link.unknown", "link", id));
      return;
    }
    source.reply(linkLine(link.get()));
    if (linkCommands != null) {
      linkCommands.details(link.get()).forEach(source::reply);
    }
  }

  private @Nullable LinkCommands linkCommands;

  /** Connects M8's link commands, for the details of a link 1.1 couldn't have made. */
  public HallLists bindLinks(LinkCommands next) {
    this.linkCommands = next;
    return this;
  }

  void linkMove(CommandSource source, Arguments arguments) {
    if (hall.hallLeftOut()) {
      source.reply(messages().get("hof.hall.left-out"));
      return;
    }
    String id = arguments.string("link");
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      source.reply(messages().get("hof.link.unknown", "link", id));
      return;
    }
    Optional<Position> origin = source.player().map(p -> platform.players().position(p));
    Optional<Position> block;
    Optional<String> typed = arguments.optionalString("at");
    if (typed.isPresent()) {
      String dimension = origin.map(Position::dimension).orElse(link.get().block().dimension());
      Positions.Read read = Positions.typed(typed.get(), origin, dimension, 0f);
      if (read instanceof Positions.Read.Failed failed) {
        source.reply(messages().get(failed.messageKey()));
        return;
      }
      block = Optional.of(((Positions.Read.Block) read).block());
    } else {
      block = source.player().flatMap(p -> platform.players().targetBlock(p, 6));
      if (block.isEmpty()) {
        source.reply(messages().get("hof.cell.anchor-where"));
        return;
      }
    }
    Position at =
        Position.of(
            block.get().dimension(),
            block.get().blockX(),
            block.get().blockY(),
            block.get().blockZ());
    Optional<HallLayout.Link> there = hall.layout().linkAt(at);
    if (there.isPresent() && !there.get().id().equals(id)) {
      source.reply(
          messages()
              .get("hof.link.taken", "link", there.get().id(), "x", x(at), "y", y(at), "z", z(at)));
      return;
    }
    if (link.get().triggers().size() > 1) {
      source.reply(messages().get("hof.link.move-many", "link", id));
      return;
    }
    // The block moves with the trigger the 1.1 form would give it there (M8); the rest stays.
    HallLayout.BlockKind kind =
        LinkService.upgradedKind(
            platform.world().block(at), config.get().config().links().upgrade().keepStepping());
    HallLayout.Link next =
        link.get().withTriggers(java.util.List.of(new HallLayout.Trigger.OnBlock(at, kind)));
    edits.saved(
        source,
        hall.saveHall(hall.layout().withLink(next)),
        messages().get("hof.link.moved", "link", id, "x", x(at), "y", y(at), "z", z(at)),
        () -> hall.audited(actor(source), "link-move", id, CellEdits.corner(at)));
  }

  private Message linkLine(HallLayout.Link link) {
    Position at = link.block();
    String[] where = {"link", link.id(), "x", x(at), "y", y(at), "z", z(at)};
    if (link.to().isEmpty()) {
      return messages().get("hof.link.line-nowhere", where);
    }
    String to = LinkService.describe(messages(), link.to().get());
    String[] all = java.util.Arrays.copyOf(where, where.length + 2);
    all[where.length] = "to";
    all[where.length + 1] = to;
    return messages().get("hof.link.line", all);
  }

  // Where things are

  void locate(CommandSource source, Arguments arguments) {
    Optional<String> target = arguments.optionalString("target");
    Messages messages = messages();
    if (target.isEmpty()) {
      Optional<Position> at = source.player().map(p -> platform.players().position(p));
      if (at.isEmpty()) {
        source.reply(messages.get("hof.player-only"));
        return;
      }
      Optional<HallService.CellRef> cell = hall.cellAt(at.get());
      if (cell.isPresent()) {
        source.reply(messages.get("hof.locate.in-cell", "cell", cell.get().key()));
      } else if (hall.layout().inHub(at.get())) {
        source.reply(messages.get("hof.locate.in-hub"));
      } else {
        Optional<Gallery> gallery =
            hall.galleries().stream().filter(g -> g.contains(at.get())).findFirst();
        source.reply(
            gallery
                .map(g -> messages.get("hof.locate.in-gallery", "gallery", g.id()))
                .orElseGet(() -> messages.get("hof.locate.outside")));
      }
      return;
    }
    String text = target.get();
    Optional<Gallery> gallery = hall.gallery(text);
    if (gallery.isPresent()) {
      source.reply(
          gallery
              .get()
              .bounds()
              .map(b -> box("hof.locate.gallery", "gallery", text, b))
              .orElseGet(() -> messages.get("hof.locate.no-bounds", "what", text)));
      return;
    }
    if (text.contains(":") && hall.resolve(text) instanceof HallService.Resolved.Found found) {
      source.reply(cellBox("hof.locate.cell", "cell", found.ref().key(), found.ref()));
      return;
    }
    Optional<HallLayout.Link> link = hall.layout().link(text);
    if (link.isPresent()) {
      source.reply(
          messages.get(
              "hof.locate.link", "link", text, "at", CellEdits.corner(link.get().block())));
      return;
    }
    Optional<PlayerId> player =
        platform
            .players()
            .findByName(text)
            .map(PlayerRef::id)
            .or(() ->
                players.all().stream()
                    .filter(r -> r.lastName().equalsIgnoreCase(text))
                    .map(PlayerRecord::id)
                    .findFirst());
    if (player.isEmpty()) {
      source.reply(messages.get("hof.locate.unknown", "what", text));
      return;
    }
    Optional<HallService.CellRef> cell =
        hall.galleries().stream()
            .filter(g -> g.kind() == Gallery.Kind.SEASON)
            .sorted(Comparator.comparingInt(Gallery::season).reversed())
            .flatMap(g -> g.cellOf(player.get()).map(c -> new HallService.CellRef(g, c)).stream())
            .findFirst();
    if (cell.isEmpty()) {
      source.reply(messages.get("hof.visit.no-cell", "name", name(player.get())));
      return;
    }
    source.reply(
        cellBox(
            "hof.locate.player", "cell", cell.get().key(), cell.get(), "name", name(player.get())));
  }

  private Message cellBox(
      String key, String name, String value, HallService.CellRef ref, String... more) {
    if (ref.cell().bounds().isEmpty()) {
      return messages().get("hof.locate.no-bounds", "what", ref.key());
    }
    Bounds b = ref.cell().bounds().get();
    String[] all = new String[6 + more.length];
    all[0] = name;
    all[1] = value;
    all[2] = "from";
    all[3] = CellEdits.corner(b.min());
    all[4] = "to";
    all[5] = CellEdits.corner(b.max());
    System.arraycopy(more, 0, all, 6, more.length);
    return messages().get(key, all);
  }

  private Message box(String key, String name, String value, Bounds b) {
    return messages()
        .get(key, name, value, "from", CellEdits.corner(b.min()), "to", CellEdits.corner(b.max()));
  }

  // Helpers

  private static String x(Position at) {
    return Integer.toString(at.blockX());
  }

  private static String y(Position at) {
    return Integer.toString(at.blockY());
  }

  private static String z(Position at) {
    return Integer.toString(at.blockZ());
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
