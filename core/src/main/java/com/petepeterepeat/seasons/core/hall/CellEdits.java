// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.List;
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

  public CellEdits(Platform platform, ConfigHolder config, HallService hall) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
  }

  /** A gallery's new bounds: no overlap with another gallery, and every cell still inside. */
  public void galleryBounds(CommandSource source, Gallery gallery, Bounds bounds) {
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

  /**
   * A cell's new bounds: inside its gallery, clear of every other cell, and around every anchor it
   * already has, so the gallery file stays valid (GalleryRules).
   *
   * @return whether the bounds were taken, and are being saved
   */
  public boolean cellBounds(CommandSource source, HallService.CellRef ref, Bounds bounds) {
    Gallery gallery = ref.gallery();
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
      if (!bounds.contains(anchor.at())) {
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
    if (!ref.cell().contains(anchor.at())) {
      source.reply(messages().get("hof.cell.anchor-outside", "cell", ref.key()));
      return;
    }
    CompletableFuture<Void> save = hall.save(ref.gallery().withCell(ref.cell().withAnchor(anchor)));
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
