// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

/**
 * Edits to galleries and cells with their checks and replies, shared by the {@code /hof} commands
 * and the wand, so a cell tagged either way reads the same (questions 227 and 228).
 */
public final class CellEdits {
  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private Optional<CommandHelp> help = Optional.empty();

  public CellEdits(Platform platform, ConfigHolder config, HallService hall) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
  }

  /** Where next steps come from (questions 268), once the commands are built. */
  public void bindHelp(CommandHelp next) {
    this.help = Optional.of(next);
  }

  /** Offers the next steps after a setup step. */
  void hint(CommandSource source, String key, String... placeholders) {
    help.ifPresent(h -> h.after(source, key, placeholders));
  }

  /**
   * Whether a stamp holds a gallery, so its bounds, points and cells can't change (questions 242);
   * if so, says why.
   */
  public boolean held(CommandSource source, Gallery gallery) {
    if (!hall.heldForStamp(gallery.id())) {
      return false;
    }
    source.reply(messages().get("hof.stamp.held", "gallery", gallery.id()));
    return true;
  }

  /** A gallery's new bounds: no overlap with another gallery, and every cell still inside. */
  public void galleryBounds(CommandSource source, Gallery gallery, Bounds bounds) {
    if (held(source, gallery)) {
      return;
    }
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
                corner(bounds.max())),
        () -> hint(source, "hof.gallery.bounds", "gallery", gallery.id()));
  }

  /**
   * A cell's new bounds: inside its gallery, clear of every other cell, and around every anchor it
   * already has, so the gallery file stays valid (GalleryRules).
   *
   * @return whether the bounds were taken, and are being saved
   */
  public boolean cellBounds(CommandSource source, HallService.CellRef ref, Bounds bounds) {
    Gallery gallery = ref.gallery();
    if (held(source, gallery)) {
      return false;
    }
    if (gallery.bounds().isEmpty() || !gallery.bounds().get().contains(bounds)) {
      source.reply(
          messages().get("hof.cell.outside-gallery", "cell", ref.key(), "gallery", gallery.id()));
      return false;
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
        return false;
      }
    }
    for (Anchor anchor : ref.cell().anchors()) {
      if (!GalleryRules.fits(bounds, anchor)) {
        source.reply(
            messages()
                .get(
                    "hof.cell.anchors-outside",
                    with(place("cell", ref.key(), anchor.at()), "type", anchor.type().id())));
        return false;
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
    return true;
  }

  /** Adds an anchor inside a cell that has bounds. */
  public void anchor(CommandSource source, HallService.CellRef ref, Anchor anchor) {
    if (ref.cell().bounds().isEmpty()) {
      source.reply(messages().get("hof.cell.anchor-no-bounds", "cell", ref.key()));
      return;
    }
    if (!GalleryRules.fits(ref.cell().bounds().get(), anchor)) {
      source.reply(
          messages()
              .get(
                  anchor.type() == AnchorType.GATE
                      ? "hof.cell.gate-outside"
                      : "hof.cell.anchor-outside",
                  "cell",
                  ref.key()));
      return;
    }
    Cell nextCell = ref.cell().withAnchor(anchor);
    Gallery next = ref.gallery().withCell(nextCell);
    List<GalleryRules.Problem> before = GalleryRules.check(ref.gallery());
    // A gate's second corner makes its plane, which mustn't reach into another cell or out of the
    // gallery (questions 250); a first corner alone is fine until then.
    Optional<GalleryRules.Problem> broken =
        nextCell.gate().isEmpty()
            ? Optional.empty()
            : GalleryRules.check(next).stream().filter(p -> !before.contains(p)).findFirst();
    if (broken.isPresent()) {
      // Such as a gate plane reaching into another cell: the file would no longer load.
      source.reply(messages().get("hof.cell.breaks", "problem", broken.get().message()));
      return;
    }
    CompletableFuture<Void> save = hall.save(next);
    OptionalInt egg = anchor.egg();
    if (egg.isPresent()) {
      saved(
          source,
          save,
          messages()
              .get(
                  "hof.wand.egg-added",
                  with(
                      place("cell", ref.key(), anchor.at()),
                      "season",
                      Integer.toString(egg.getAsInt()))));
      return;
    }
    String name = anchor.type().id();
    String key = "aeiou".indexOf(name.charAt(0)) >= 0 ? "hof.cell.anchor-an" : "hof.cell.anchor";
    saved(
        source,
        save,
        messages().get(key, with(place("cell", ref.key(), anchor.at()), "type", name)));
  }

  /** Takes every anchor on one block off a cell; false if there was none. */
  public boolean removeAt(CommandSource source, HallService.CellRef ref, Position block) {
    List<Anchor> kept = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    for (Anchor anchor : ref.cell().anchors()) {
      if (anchor.isAt(block)) {
        if (!removed.contains(anchor.type().id())) {
          removed.add(anchor.type().id());
        }
      } else {
        kept.add(anchor);
      }
    }
    if (removed.isEmpty()) {
      return false;
    }
    saved(
        source,
        hall.save(ref.gallery().withCell(ref.cell().withAnchors(kept))),
        messages()
            .get(
                "hof.wand.removed",
                with(place("cell", ref.key(), block), "type", String.join(" and ", removed))));
    return true;
  }

  /** Replies once the gallery file is written, so the file on disk matches what was said. */
  void saved(CommandSource source, CompletableFuture<Void> save, Message message) {
    saved(source, save, message, () -> {});
  }

  /**
   * {@code /hof cell redefine}'s selection (questions 273): new bounds, inside the gallery and
   * clear of other cells as always, but anchors left outside are taken off and listed rather than
   * stopping it. Gate corners beside the bounds stay, as questions 250 allows.
   */
  public boolean redefine(CommandSource source, HallService.CellRef ref, Bounds bounds) {
    Gallery gallery = ref.gallery();
    if (held(source, gallery)) {
      return false;
    }
    if (gallery.bounds().isPresent() && !gallery.bounds().get().contains(bounds)) {
      source.reply(
          messages().get("hof.cell.outside-gallery", "cell", ref.key(), "gallery", gallery.id()));
      return false;
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
        return false;
      }
    }
    List<Anchor> kept = new ArrayList<>();
    List<String> dropped = new ArrayList<>();
    for (Anchor anchor : ref.cell().anchors()) {
      if (GalleryRules.fits(bounds, anchor)) {
        kept.add(anchor);
      } else {
        dropped.add(anchor.type().id() + " at " + corner(anchor.at()));
      }
    }
    Cell cell = ref.cell();
    Cell next =
        new Cell(
            cell.id(),
            Optional.of(bounds),
            kept,
            cell.owners(),
            cell.imported(),
            cell.pose(),
            cell.gateShut());
    Gallery after = gallery.withCell(next);
    hall.rememberUndo(source, after);
    saved(
        source,
        hall.save(after),
        messages()
            .get(
                "hof.cell.bounds",
                "cell",
                ref.key(),
                "from",
                corner(bounds.min()),
                "to",
                corner(bounds.max())),
        () -> {
          if (!dropped.isEmpty()) {
            source.reply(
                messages()
                    .get("hof.cell.redefined-dropped", "anchors", String.join(", ", dropped)));
          }
          source.reply(messages().get("hof.undo.offer"));
        });
    return true;
  }

  /** Replies once the file is written, then, if it was, runs {@code then}, such as a hint. */
  void saved(CommandSource source, CompletableFuture<Void> save, Message message, Runnable then) {
    var _ =
        save.whenComplete((done, failure) ->
            platform
                .scheduler()
                .runGlobal(() -> {
                  if (failure == null) {
                    source.reply(message);
                    then.run();
                  } else {
                    source.reply(
                        messages()
                            .get("hof.save-failed", "error", String.valueOf(failure.getMessage())));
                  }
                }));
  }

  static String corner(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
  }

  static String[] place(String key, String value, Position at) {
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

  private static String[] with(String[] placeholders, String key, String value) {
    String[] all = new String[placeholders.length + 2];
    System.arraycopy(placeholders, 0, all, 0, placeholders.length);
    all[placeholders.length] = key;
    all[placeholders.length + 1] = value;
    return all;
  }

  private Messages messages() {
    return config.get().messages();
  }
}
