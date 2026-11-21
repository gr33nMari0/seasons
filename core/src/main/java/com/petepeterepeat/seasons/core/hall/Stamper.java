// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.TemplateScan;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.OpenOperation;
import com.petepeterepeat.seasons.core.store.RecoveryHandler;
import com.petepeterepeat.seasons.core.store.RecoveryOutcome;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /hof stamp <template> <gallery> <count> [--step x y z] [--rotate 0|90|180|270]} (questions
 * 230 to 235): checks every copy first, then, journalled, places the copies a tick apart, turns
 * their markers to air, saves the dimension and writes the new cells. A stamp a crash interrupted
 * is finished at startup from its saved plan. One stamp runs at a time, on the server thread.
 */
public final class Stamper {
  private static final Logger LOG = LoggerFactory.getLogger(Stamper.class);
  private static final Pattern WORDS = Pattern.compile("\\s+");
  private static final Duration BETWEEN_COPIES = Duration.ofMillis(50);
  private static final String AIR = "minecraft:air";
  private static final String ROTATE = "--rotate";
  private static final String STEP = "--step";

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final TemplateLibrary library;
  private final Journal journal;
  private final SerialWriter writer;
  private final AuditLog audit;
  private final CrashPoints crash;
  private final Path stamps;
  private final Supplier<OptionalInt> season;
  private final Executor serverThread;
  private final Clock clock;
  private final AtomicInteger counter = new AtomicInteger();
  private boolean busy;

  /**
   * @param stamps {@code data/stamps}, where each running stamp's plan and template are kept
   * @param season the season being played, if one is recorded
   */
  public Stamper(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      TemplateLibrary library,
      Journal journal,
      SerialWriter writer,
      AuditLog audit,
      CrashPoints crash,
      Path stamps,
      Supplier<OptionalInt> season,
      Clock clock) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.library = library;
    this.journal = journal;
    this.writer = writer;
    this.audit = audit;
    this.crash = crash;
    this.stamps = stamps;
    this.season = season;
    this.serverThread = platform.scheduler()::runGlobal;
    this.clock = clock;
  }

  /** The options given: the rotation and the step, if one was. */
  private record Options(int rotation, Optional<int[]> step) {}

  public void stamp(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    if (source.player().isEmpty()) {
      source.reply(messages.get("hof.stamp.player-only"));
      return;
    }
    PlayerId player = source.player().get();
    Optional<Options> options = options(source, arguments.optionalString("options"));
    if (options.isEmpty()) {
      return;
    }
    String galleryId = arguments.string("gallery");
    Optional<Gallery> found = hall.gallery(galleryId);
    if (found.isEmpty()) {
      source.reply(messages.get("hof.no-gallery", "gallery", galleryId));
      return;
    }
    Gallery gallery = found.get();
    String name = arguments.string("template");
    Optional<Template> template = template(source, name, gallery.kind());
    if (template.isEmpty()) {
      return;
    }
    if (busy) {
      source.reply(messages.get("hof.stamp.busy"));
      return;
    }
    int count = arguments.integer("count");
    List<String> ids = nextIds(gallery, count);
    Position at = platform.players().position(player);
    int[] step =
        options
            .get()
            .step()
            .orElseGet(
                () -> StampPlan.defaultStep(template.get().scan(), options.get().rotation()));
    StampPlan plan =
        new StampPlan(
            gallery.id(),
            name,
            template.get().source(),
            at.dimension(),
            at.blockX(),
            at.blockY(),
            at.blockZ(),
            options.get().rotation(),
            step[0],
            step[1],
            step[2],
            ids,
            platform.players().find(player).map(PlayerRef::name).orElse(player.toString()));
    List<StampPlan.Copy> copies = plan.copies(template.get());
    if (!fits(source, gallery, copies)) {
      return;
    }
    busy = true;
    var _ =
        loadChunks(copies)
            .thenRunAsync(
                () -> {
                  if (overAir(source, copies)) {
                    start(source, player, plan, template.get(), copies);
                  } else {
                    busy = false;
                  }
                },
                serverThread)
            .exceptionally(failure -> {
              serverThread.execute(() -> {
                busy = false;
                LOG.error("A stamp couldn't load its chunks", failure);
                source.reply(messages().get("hof.stamp.failed", "error", String.valueOf(failure)));
              });
              return null;
            });
  }

  /** The template by name, checked for this gallery's kind; empty, with a reply, if refused. */
  private Optional<Template> template(CommandSource source, String name, Gallery.Kind kind) {
    Messages messages = messages();
    Optional<TemplateLibrary.Found> found;
    try {
      found = library.find(name);
    } catch (RuntimeException e) {
      LOG.error("Can't read template {}", name, e);
      source.reply(
          messages.get(
              "hof.stamp.refused",
              "template",
              name,
              "reason",
              messages.text("hof.stamp.reasons.damaged", "error", String.valueOf(e.getMessage()))));
      return Optional.empty();
    }
    if (found.isEmpty()) {
      source.reply(
          messages.get(
              "hof.stamp.unknown",
              "template",
              name,
              "templates",
              String.join(", ", library.names())));
      return Optional.empty();
    }
    TemplateScan scan;
    try {
      scan = platform.templates().scan(found.get().data());
    } catch (RuntimeException e) {
      source.reply(
          messages.get(
              "hof.stamp.refused",
              "template",
              name,
              "reason",
              messages.text("hof.stamp.reasons.damaged", "error", String.valueOf(e.getMessage()))));
      return Optional.empty();
    }
    Template template = new Template(name, found.get().source(), found.get().data(), scan);
    Optional<String> problem = template.problem(kind, messages);
    if (problem.isPresent()) {
      source.reply(messages.get("hof.stamp.refused", "template", name, "reason", problem.get()));
      return Optional.empty();
    }
    return Optional.of(template);
  }

  private Optional<Options> options(CommandSource source, Optional<String> text) {
    Messages messages = messages();
    String[] words =
        WORDS
            .splitAsStream(text.orElse("").trim())
            .filter(w -> !w.isEmpty())
            .toArray(String[]::new);
    int rotation = 0;
    Optional<int[]> step = Optional.empty();
    for (int i = 0; i < words.length; i++) {
      switch (words[i]) {
        case ROTATE -> {
          OptionalInt degrees = i + 1 < words.length ? parse(words[i + 1]) : OptionalInt.empty();
          if (degrees.isEmpty() || !List.of(0, 90, 180, 270).contains(degrees.getAsInt())) {
            source.reply(messages.get("hof.stamp.bad-rotate"));
            return Optional.empty();
          }
          rotation = degrees.getAsInt();
          i++;
        }
        case STEP -> {
          if (i + 3 >= words.length) {
            source.reply(messages.get("hof.stamp.bad-step"));
            return Optional.empty();
          }
          int[] values = new int[3];
          for (int k = 0; k < 3; k++) {
            OptionalInt value = parse(words[i + 1 + k]);
            if (value.isEmpty()) {
              source.reply(messages.get("hof.stamp.bad-step"));
              return Optional.empty();
            }
            values[k] = value.getAsInt();
          }
          step = Optional.of(values);
          i += 3;
        }
        default -> {
          source.reply(messages.get("hof.stamp.unknown-flag", "flag", words[i]));
          return Optional.empty();
        }
      }
    }
    return Optional.of(new Options(rotation, step));
  }

  private static OptionalInt parse(String word) {
    try {
      return OptionalInt.of(Integer.parseInt(word));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /** The gallery's next free ids {@code c1}, {@code c2}, …, one per copy. */
  private static List<String> nextIds(Gallery gallery, int count) {
    Set<String> taken = new HashSet<>();
    for (Cell cell : gallery.cells()) {
      taken.add(cell.id());
    }
    List<String> ids = new ArrayList<>();
    for (int n = 1; ids.size() < count; n++) {
      if (!taken.contains("c" + n)) {
        ids.add("c" + n);
      }
    }
    return ids;
  }

  /** Every copy inside the gallery, clear of cells, of each other and of other galleries. */
  private boolean fits(CommandSource source, Gallery gallery, List<StampPlan.Copy> copies) {
    Messages messages = messages();
    for (int i = 0; i < copies.size(); i++) {
      StampPlan.Copy copy = copies.get(i);
      String number = Integer.toString(i + 1);
      if (gallery.bounds().isEmpty() || !gallery.bounds().get().contains(copy.bounds())) {
        source.reply(messages.get("hof.stamp.outside", "copy", number, "gallery", gallery.id()));
        return false;
      }
      for (Cell cell : gallery.cells()) {
        if (cell.bounds().map(b -> b.overlaps(copy.bounds())).orElse(false)) {
          source.reply(
              messages.get(
                  "hof.stamp.overlaps", "copy", number, "cell", gallery.id() + ":" + cell.id()));
          return false;
        }
      }
      for (int j = 0; j < i; j++) {
        if (copies.get(j).bounds().overlaps(copy.bounds())) {
          source.reply(
              messages.get(
                  "hof.stamp.overlaps",
                  "copy",
                  number,
                  "cell",
                  gallery.id() + ":" + copies.get(j).cell()));
          return false;
        }
      }
      for (Gallery other : hall.galleries()) {
        if (!other.id().equals(gallery.id())
            && other.bounds().map(b -> b.overlaps(copy.bounds())).orElse(false)) {
          source.reply(
              messages.get("hof.stamp.other-gallery", "copy", number, "other", other.id()));
          return false;
        }
      }
    }
    Gallery next = withCopies(gallery, copies);
    Set<String> before = new HashSet<>();
    List<Gallery> others = new ArrayList<>();
    for (Gallery g : hall.galleries()) {
      if (!g.id().equals(gallery.id())) {
        others.add(g);
      }
    }
    for (GalleryRules.Problem problem : GalleryRules.check(hall.galleries())) {
      before.add(problem.path() + problem.message());
    }
    others.add(next);
    for (GalleryRules.Problem problem : GalleryRules.check(others)) {
      if (problem.gallery().equals(gallery.id())
          && !before.contains(problem.path() + problem.message())) {
        source.reply(
            messages.get(
                "hof.stamp.broken",
                "copy",
                "1",
                "problem",
                problem.path() + " " + problem.message()));
        return false;
      }
    }
    return true;
  }

  /** A gallery with a cell for each copy that it doesn't already have. */
  private static Gallery withCopies(Gallery gallery, List<StampPlan.Copy> copies) {
    Gallery next = gallery;
    for (StampPlan.Copy copy : copies) {
      if (next.cell(copy.cell()).isEmpty()) {
        next =
            next.withCell(
                Cell.create(copy.cell()).withBounds(copy.bounds()).withAnchors(copy.anchors()));
      }
    }
    return next;
  }

  private CompletableFuture<Void> loadChunks(List<StampPlan.Copy> copies) {
    Set<String> seen = new LinkedHashSet<>();
    List<CompletableFuture<Void>> loads = new ArrayList<>();
    for (StampPlan.Copy copy : copies) {
      Bounds b = copy.bounds();
      for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
        for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
          if (seen.add(cx + "," + cz)) {
            loads.add(
                platform.world().loadChunk(Position.of(b.dimension(), cx * 16, b.minY(), cz * 16)));
          }
        }
      }
    }
    return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
  }

  /** Whether every block the copies set is air now; if not, says which isn't. */
  private boolean overAir(CommandSource source, List<StampPlan.Copy> copies) {
    for (int i = 0; i < copies.size(); i++) {
      for (Position at : copies.get(i).sets()) {
        String type = platform.world().block(at).type();
        if (!Template.isAir(type)) {
          source.reply(
              messages()
                  .get(
                      "hof.stamp.not-air",
                      "copy",
                      Integer.toString(i + 1),
                      "block",
                      type,
                      "x",
                      Integer.toString(at.blockX()),
                      "y",
                      Integer.toString(at.blockY()),
                      "z",
                      Integer.toString(at.blockZ())));
          return false;
        }
      }
    }
    return true;
  }

  /** Saves the plan, journals the start, then places and finishes. */
  private void start(
      CommandSource source,
      PlayerId player,
      StampPlan plan,
      Template template,
      List<StampPlan.Copy> copies) {
    String id = Long.toString(clock.millis(), 36) + "-" + counter.incrementAndGet();
    int playing = season.get().orElse(0);
    var _ =
        writer
            .submit(() -> {
              Files.createDirectories(stamps);
              Files.write(stamps.resolve(id + ".nbt"), template.data());
              Files.writeString(stamps.resolve(id + ".plan"), plan.write(), StandardCharsets.UTF_8);
              return Boolean.TRUE;
            })
            .thenCompose(saved -> journal.append(JournalKind.STAMP_STARTED, player, playing, id))
            .thenComposeAsync(
                started -> {
                  crash.reached(CrashPoint.STAMP_STARTED, player);
                  return place(plan, template, copies, player);
                },
                serverThread)
            .thenCompose(placed -> finish(id, plan, copies, player, playing, "done"))
            .whenCompleteAsync(
                (done, failure) -> {
                  busy = false;
                  if (failure != null) {
                    LOG.error("Stamp {} stopped; it finishes at the next start", id, failure);
                    source.reply(
                        messages()
                            .get(
                                "hof.stamp.failed", "error", String.valueOf(failure.getMessage())));
                    return;
                  }
                  source.reply(
                      messages()
                          .get(
                              copies.size() == 1 ? "hof.stamp.done" : "hof.stamp.done-many",
                              "count",
                              Integer.toString(copies.size()),
                              "template",
                              plan.template(),
                              "source",
                              messages().text(plan.source().message()),
                              "gallery",
                              plan.gallery(),
                              "cells",
                              String.join(", ", plan.cells())));
                },
                serverThread);
  }

  /**
   * Places each copy a tick apart, turns its markers to air, then saves the dimension. Completes on
   * the server thread.
   */
  private CompletableFuture<Void> place(
      StampPlan plan, Template template, List<StampPlan.Copy> copies, @Nullable PlayerId player) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    byte[] data = template.data();
    var _ =
        loadChunks(copies)
            .thenRunAsync(() -> placeFrom(0, plan, data, copies, player, done), serverThread)
            .exceptionally(failure -> {
              done.completeExceptionally(failure);
              return null;
            });
    return done;
  }

  private void placeFrom(
      int index,
      StampPlan plan,
      byte[] data,
      List<StampPlan.Copy> copies,
      @Nullable PlayerId player,
      CompletableFuture<Void> done) {
    try {
      if (index == copies.size()) {
        platform.imports().saveWorld(plan.dimension());
        if (player != null) {
          crash.reached(CrashPoint.STAMP_PLACED, player);
        }
        done.complete(null);
        return;
      }
      StampPlan.Copy copy = copies.get(index);
      platform.templates().place(data, copy.origin(), plan.rotation());
      platform.world().setBlocks(copy.markers(), AIR);
      platform
          .scheduler()
          .runGlobalLater(
              BETWEEN_COPIES, () -> placeFrom(index + 1, plan, data, copies, player, done));
    } catch (RuntimeException e) {
      done.completeExceptionally(e);
    }
  }

  /** Writes the cells the gallery doesn't have yet, closes the journal entry and audits. */
  private CompletableFuture<Void> finish(
      String id,
      StampPlan plan,
      List<StampPlan.Copy> copies,
      @Nullable PlayerId player,
      int playing,
      String result) {
    return CompletableFuture.supplyAsync(
            () ->
                withCopies(
                    hall.gallery(plan.gallery())
                        .orElseThrow(() ->
                            new IllegalStateException("gallery " + plan.gallery() + " is gone")),
                    copies),
            serverThread)
        // On the server thread, which completed the step before.
        .thenCompose(hall::save)
        .thenCompose(saved -> journal.append(JournalKind.STAMP_DONE, player, playing, id))
        .thenCompose(closed ->
            writer.submit(() -> {
              Files.deleteIfExists(stamps.resolve(id + ".nbt"));
              Files.deleteIfExists(stamps.resolve(id + ".plan"));
              return Boolean.TRUE;
            }))
        .thenRun(() ->
            audit.record(
                plan.actor(),
                "stamp",
                plan.gallery(),
                result,
                copies.size()
                    + " × "
                    + plan.template()
                    + " ("
                    + plan.source().name().toLowerCase(java.util.Locale.ROOT)
                    + ") as "
                    + String.join(", ", plan.cells())
                    + " from "
                    + plan.originX()
                    + ", "
                    + plan.originY()
                    + ", "
                    + plan.originZ()
                    + " turned "
                    + plan.rotation()));
  }

  /** Finishes a stamp a crash interrupted, from its saved plan and template. */
  public RecoveryHandler recovery() {
    return this::recover;
  }

  private CompletableFuture<RecoveryOutcome> recover(OpenOperation operation) {
    String id = operation.steps().getFirst().payloadHash();
    PlayerId player = operation.player().orElse(null);
    StampPlan plan;
    byte[] data;
    try {
      plan = StampPlan.read(Files.readString(stamps.resolve(id + ".plan"), StandardCharsets.UTF_8));
      data = Files.readAllBytes(stamps.resolve(id + ".nbt"));
    } catch (IOException | IllegalArgumentException e) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("stamp " + id + "'s saved plan can't be read: " + e.getMessage()));
    }
    if (hall.gallery(plan.gallery()).isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("stamp " + id + "'s gallery " + plan.gallery() + " is gone"));
    }
    Template template;
    try {
      template =
          new Template(plan.template(), plan.source(), data, platform.templates().scan(data));
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("stamp " + id + "'s template can't be read: " + e.getMessage()));
    }
    List<StampPlan.Copy> copies = plan.copies(template);
    busy = true;
    return place(plan, template, copies, null)
        .thenCompose(placed -> finish(id, plan, copies, player, operation.season(), "recovered"))
        .handle((done, failure) -> {
          serverThread.execute(() -> busy = false);
          if (failure != null) {
            LOG.error("Couldn't finish stamp {}", id, failure);
            return RecoveryOutcome.failed(String.valueOf(failure.getMessage()));
          }
          LOG.info("Finished stamp {} of {} into {}", id, plan.template(), plan.gallery());
          return RecoveryOutcome.finished();
        });
  }

  private Messages messages() {
    return config.get().messages();
  }
}
