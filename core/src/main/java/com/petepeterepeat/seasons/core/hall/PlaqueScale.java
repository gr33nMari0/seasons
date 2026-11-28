// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Plaque text size (questions 278): {@code /hof cell plaque <cell> scale <n>}, {@code /hof gallery
 * plaque <gallery> scale <n>}, and sneak-scrolling with the wand while looking at a plaque.
 */
public final class PlaqueScale {
  /** How much one scroll changes a plaque's size. */
  static final double STEP = 0.1;

  /** How far away a plaque can be looked at. */
  private static final double REACH = 6;

  private static final double EYE = 1.62;

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final CellEdits edits;

  public PlaqueScale(Platform platform, ConfigHolder config, HallService hall, CellEdits edits) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.edits = edits;
  }

  public CommandNode root() {
    String admin = HallCommands.PERMISSION_ADMIN;
    return CommandNode.literal("hof")
        .then(
            CommandNode.literal("cell")
                .permission(admin)
                .then(
                    CommandNode.literal("plaque")
                        .then(
                            CommandNode.argument("cell", ArgumentType.id())
                                .then(
                                    CommandNode.literal("scale")
                                        .then(
                                            CommandNode.argument("size", ArgumentType.word())
                                                .executes(this::cellScale)))
                                .then(
                                    CommandNode.literal("facing")
                                        .then(
                                            CommandNode.argument("facing", ArgumentType.word())
                                                .suggests((s, t) -> FACINGS)
                                                .executes(this::cellFacing))))))
        .then(
            CommandNode.literal("gallery")
                .permission(admin)
                .then(
                    CommandNode.literal("plaque")
                        .then(
                            CommandNode.argument("gallery", ArgumentType.word())
                                .then(
                                    CommandNode.literal("scale")
                                        .then(
                                            CommandNode.argument("size", ArgumentType.word())
                                                .executes(this::galleryScale))))))
        .build();
  }

  private Optional<Double> size(CommandSource source, Arguments arguments) {
    try {
      double size = Double.parseDouble(arguments.string("size"));
      if (size >= GalleryFiles.MIN_SCALE && size <= GalleryFiles.MAX_SCALE) {
        return Optional.of(Math.round(size * 100) / 100.0);
      }
    } catch (NumberFormatException e) {
      // Falls through to the refusal.
    }
    source.reply(messages().get("hof.plaque-size.bad-scale"));
    return Optional.empty();
  }

  void cellScale(CommandSource source, Arguments arguments) {
    Optional<Double> size = size(source, arguments);
    if (size.isEmpty()) {
      return;
    }
    String name = arguments.string("cell");
    if (!(hall.resolve(name) instanceof HallService.Resolved.Found found)) {
      source.reply(messages().get("hof.no-cell", "cell", name));
      return;
    }
    HallService.CellRef ref = found.ref();
    Optional<Anchor> plaque = ref.cell().anchor(AnchorType.PLAQUE);
    if (plaque.isEmpty()) {
      source.reply(messages().get("hof.plaque-size.none", "cell", ref.key()));
      return;
    }
    Gallery next = withScale(ref, plaque.get(), size.get());
    edits.saved(
        source,
        hall.save(next),
        messages().get("hof.plaque-size.scaled", "cell", ref.key(), "size", text(size.get())),
        () -> redraw(next));
  }

  /** What {@code /hof cell plaque <cell> facing} takes: a side, or auto to guess it again. */
  private static final java.util.List<String> FACINGS =
      java.util.List.of("north", "south", "west", "east", "up", "auto");

  /**
   * {@code /hof cell plaque <cell> facing <side|auto>} (1.4.1, H4): which side of its block the
   * plaque's text stands on; auto forgets it, so the next drawing guesses it again.
   */
  void cellFacing(CommandSource source, Arguments arguments) {
    String word = arguments.string("facing");
    Optional<PlaqueFit.Facing> facing = PlaqueFit.Facing.of(word);
    if (facing.isEmpty() && !word.equals("auto")) {
      source.reply(messages().get("hof.plaque-facing.bad"));
      return;
    }
    String name = arguments.string("cell");
    if (!(hall.resolve(name) instanceof HallService.Resolved.Found found)) {
      source.reply(messages().get("hof.no-cell", "cell", name));
      return;
    }
    HallService.CellRef ref = found.ref();
    Optional<Anchor> plaque = ref.cell().anchor(AnchorType.PLAQUE);
    if (plaque.isEmpty()) {
      source.reply(messages().get("hof.plaque-size.none", "cell", ref.key()));
      return;
    }
    java.util.List<Anchor> anchors = new java.util.ArrayList<>();
    for (Anchor anchor : ref.cell().anchors()) {
      anchors.add(anchor.equals(plaque.get()) ? anchor.withFacing(facing) : anchor);
    }
    Gallery next = ref.gallery().withCell(ref.cell().withAnchors(anchors));
    edits.saved(
        source,
        hall.save(next),
        facing.isPresent()
            ? messages().get("hof.plaque-facing.set", "cell", ref.key(), "facing", word)
            : messages().get("hof.plaque-facing.auto", "cell", ref.key()));
  }

  void galleryScale(CommandSource source, Arguments arguments) {
    Optional<Double> size = size(source, arguments);
    if (size.isEmpty()) {
      return;
    }
    String id = arguments.string("gallery");
    Optional<Gallery> gallery = hall.gallery(id);
    if (gallery.isEmpty()) {
      source.reply(messages().get("hof.no-gallery", "gallery", id));
      return;
    }
    Gallery next =
        gallery
            .get()
            .withSettings(gallery.get().settings().withPlaqueScale(Optional.of(size.get())));
    edits.saved(
        source,
        hall.save(next),
        messages().get("hof.plaque-size.gallery-scaled", "gallery", id, "size", text(size.get())),
        () -> redraw(next));
  }

  /** Sneak-scrolling with the wand at a plaque: bigger scrolling up, smaller down. */
  public Optional<Decision> decide(Intent intent) {
    if (!(intent instanceof Intent.WandScroll scroll)) {
      return Optional.empty();
    }
    PlayerId player = scroll.player();
    if (!scroll.sneaking() || !platform.permissions().has(player, HallCommands.PERMISSION_ADMIN)) {
      return Optional.empty();
    }
    Optional<HallService.CellRef> looked = lookedAt(player);
    if (looked.isEmpty()) {
      return Optional.empty();
    }
    HallService.CellRef ref = looked.get();
    Anchor plaque = ref.cell().anchor(AnchorType.PLAQUE).orElseThrow();
    // A step back in the hotbar is a scroll up.
    int step = Math.floorMod(scroll.to() - scroll.from(), 9) == 8 ? 1 : -1;
    double size =
        Math.max(
            GalleryFiles.MIN_SCALE,
            Math.min(
                GalleryFiles.MAX_SCALE,
                Math.round((ref.gallery().plaqueScale(plaque) + step * STEP) * 100) / 100.0));
    Gallery next = withScale(ref, plaque, size);
    var _ = hall.save(next);
    redraw(next);
    platform
        .players()
        .actionBar(
            player, messages().get("hof.plaque-size.bar", "cell", ref.key(), "size", text(size)));
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  /** The cell whose plaque the player looks at, within reach. */
  private Optional<HallService.CellRef> lookedAt(PlayerId player) {
    Position at = platform.players().position(player);
    double yaw = Math.toRadians(at.yaw());
    double pitch = Math.toRadians(at.pitch());
    double dx = -Math.sin(yaw) * Math.cos(pitch);
    double dy = -Math.sin(pitch);
    double dz = Math.cos(yaw) * Math.cos(pitch);
    double ex = at.x();
    double ey = at.y() + EYE;
    double ez = at.z();
    HallService.CellRef best = null;
    double nearest = Double.MAX_VALUE;
    for (Gallery gallery : hall.galleries()) {
      if (!gallery.dimension().equals(at.dimension())) {
        continue;
      }
      for (Cell cell : gallery.cells()) {
        Optional<Anchor> plaque = cell.anchor(AnchorType.PLAQUE);
        if (plaque.isEmpty()) {
          continue;
        }
        Position p = plaque.get().at();
        // The anchor block and the text above it, where the display stands.
        double t =
            ray(
                ex,
                ey,
                ez,
                dx,
                dy,
                dz,
                p.blockX(),
                p.blockY(),
                p.blockZ(),
                p.blockX() + 1,
                p.blockY() + 2.5,
                p.blockZ() + 1);
        if (t >= 0 && t <= REACH && t < nearest) {
          nearest = t;
          best = new HallService.CellRef(gallery, cell);
        }
      }
    }
    return Optional.ofNullable(best);
  }

  /** How far along a ray it first meets a box, or -1 if it doesn't. */
  private static double ray(
      double ox,
      double oy,
      double oz,
      double dx,
      double dy,
      double dz,
      double x0,
      double y0,
      double z0,
      double x1,
      double y1,
      double z1) {
    double near = 0;
    double far = Double.MAX_VALUE;
    double[][] axes = {{ox, dx, x0, x1}, {oy, dy, y0, y1}, {oz, dz, z0, z1}};
    for (double[] axis : axes) {
      if (Math.abs(axis[1]) < 1e-9) {
        if (axis[0] < axis[2] || axis[0] > axis[3]) {
          return -1;
        }
        continue;
      }
      double t1 = (axis[2] - axis[0]) / axis[1];
      double t2 = (axis[3] - axis[0]) / axis[1];
      near = Math.max(near, Math.min(t1, t2));
      far = Math.min(far, Math.max(t1, t2));
      if (near > far) {
        return -1;
      }
    }
    return near;
  }

  private static Gallery withScale(HallService.CellRef ref, Anchor plaque, double size) {
    java.util.List<Anchor> anchors = new java.util.ArrayList<>();
    for (Anchor anchor : ref.cell().anchors()) {
      anchors.add(anchor.equals(plaque) ? anchor.withScale(OptionalDouble.of(size)) : anchor);
    }
    return ref.gallery().withCell(ref.cell().withAnchors(anchors));
  }

  /** Every plaque display of a gallery at its new size. */
  private void redraw(Gallery gallery) {
    if (gallery.bounds().isEmpty()) {
      return;
    }
    Bounds b = gallery.bounds().get();
    for (Displays.Shown shown : platform.displays().tagged(b.min(), b.max())) {
      Optional<DisplayTag> tag = DisplayTag.parse(shown.tag());
      if (tag.isEmpty() || !tag.get().kind().equals(DisplayTag.PLAQUE)) {
        continue;
      }
      gallery
          .cell(tag.get().cell())
          .flatMap(c -> c.anchor(AnchorType.PLAQUE))
          .ifPresent(
              plaque -> platform.displays().scaleText(shown.id(), gallery.plaqueScale(plaque)));
    }
  }

  private static String text(double size) {
    return String.format(Locale.ROOT, "%.1f", size);
  }

  private Messages messages() {
    return config.get().messages();
  }
}
