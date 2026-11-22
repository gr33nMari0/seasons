// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Words;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.hall.Bounds;
import com.petepeterepeat.seasons.core.hall.Cell;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.GalleryFiles;
import com.petepeterepeat.seasons.core.hall.HallService;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.ChatInput;
import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /season reset} (questions 283): wipes Seasons from a test world, for server operators
 * only, behind two confirmations and DELETE typed in chat. A backup comes first, and Seasons then
 * stops.
 */
final class ResetCommand implements ChatInput {
  private static final Logger LOG = LoggerFactory.getLogger(ResetCommand.class);

  /** How long each step waits for the next. */
  static final Duration WAIT = Duration.ofSeconds(60);

  static final String KEEP_HALL = "--keep-hall";

  /** In {@code plugins/Seasons/}: a confirmed reset not yet finished (questions 283). */
  static final String PENDING = "reset-pending";

  private static final String WORD = "DELETE";
  private static final String CONSOLE = "console";
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

  private final Platform platform;
  private final ConfigHolder config;
  private final Supplier<Optional<SeasonState>> state;
  private final Optional<Journal> journal;
  private final ArchiveHold hold;
  private final HallService halls;
  private final PlayerStore players;
  private final Path world;
  private final Path configDirectory;
  private final Clock clock;
  private final Executor serverThread;
  private final Runnable stopSeasons;

  /** Where each operator is in the steps. */
  private record Pending(int step, boolean keepHall, Instant expires) {}

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  /**
   * @param world {@code <world>/seasons}, every file of which goes
   * @param stopSeasons stops Seasons once its files are gone, so nothing writes them again
   */
  ResetCommand(
      Platform platform,
      ConfigHolder config,
      Supplier<Optional<SeasonState>> state,
      Optional<Journal> journal,
      ArchiveHold hold,
      HallService halls,
      PlayerStore players,
      Path world,
      Path configDirectory,
      Clock clock,
      Runnable stopSeasons) {
    this.platform = platform;
    this.config = config;
    this.state = state;
    this.journal = journal;
    this.hold = hold;
    this.halls = halls;
    this.players = players;
    this.world = world;
    this.configDirectory = configDirectory;
    this.clock = clock;
    this.serverThread = platform.scheduler()::runGlobal;
    this.stopSeasons = stopSeasons;
  }

  /** The {@code reset} node under {@code /season}: no permission, since only operators may. */
  CommandNode node() {
    return CommandNode.literal("reset")
        .executes(this::start)
        .then(
            CommandNode.literal("confirm")
                .executes(this::confirm)
                .then(CommandNode.argument("word", ArgumentType.word()).executes(this::confirm)))
        .then(
            CommandNode.argument("options", ArgumentType.greedy())
                .suggests((source, typed) -> List.of(KEEP_HALL))
                .executes(this::start))
        .build();
  }

  private static String key(CommandSource source) {
    return source.player().map(p -> p.uuid().toString()).orElse(CONSOLE);
  }

  /** Server operators and the console only: never a permission node (questions 283). */
  private boolean operator(CommandSource source) {
    return source.player().map(p -> platform.permissions().isOperator(p)).orElse(true);
  }

  void start(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    if (!operator(source)) {
      source.reply(messages.get("reset.operators-only"));
      return;
    }
    List<String> options = Words.of(arguments.optionalString("options").orElse(""));
    if (options.stream().anyMatch(o -> !o.equals(KEEP_HALL))) {
      source.reply(messages.get("reset.usage"));
      return;
    }
    Optional<String> busy = busy();
    if (busy.isPresent()) {
      source.reply(messages.get(busy.get()));
      return;
    }
    boolean keepHall = options.contains(KEEP_HALL);
    pending.put(key(source), new Pending(1, keepHall, clock.instant().plus(WAIT)));
    source.reply(messages.get("reset.warning"));
    source.reply(
        messages.get(
            "reset.counts",
            "players",
            Integer.toString(players.all().size()),
            "archives",
            Long.toString(count(world.resolve("data/archives"))),
            "vaults",
            Long.toString(count(world.resolve("data/vaults"))),
            "museum",
            Long.toString(count(world.resolve("data/museum"))),
            "recycled",
            Long.toString(count(world.resolve("data/recycle")))));
    source.reply(messages.get(keepHall ? "reset.keeps-hall" : "reset.keeps-nothing"));
    source.reply(messages.get("reset.next-confirm"));
  }

  void confirm(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    if (!operator(source)) {
      source.reply(messages.get("reset.operators-only"));
      return;
    }
    Pending first = pending.remove(key(source));
    if (first == null || first.step() != 1 || !clock.instant().isBefore(first.expires())) {
      source.reply(messages.get("reset.not-started"));
      return;
    }
    Optional<String> word = arguments.optionalString("word");
    if (source.player().isEmpty()) {
      // The console can't be asked in chat: it types DELETE after confirm.
      if (word.isPresent() && word.get().equals(WORD)) {
        run(source, first.keepHall());
      } else {
        pending.put(key(source), first);
        source.reply(messages.get("reset.console-word"));
      }
      return;
    }
    pending.put(key(source), new Pending(2, first.keepHall(), clock.instant().plus(WAIT)));
    source.reply(messages.get("reset.last-warning"));
    source.reply(messages.get("reset.type-delete"));
  }

  /** A line in chat: DELETE goes on, anything else stops it. Any thread. */
  @Override
  public boolean typed(PlayerId player, String text) {
    String key = player.uuid().toString();
    Pending second = pending.get(key);
    if (second == null || second.step() != 2 || !pending.remove(key, second)) {
      return false;
    }
    serverThread.execute(() -> {
      CommandSource source = source(player);
      if (!clock.instant().isBefore(second.expires()) || !text.strip().equals(WORD)) {
        source.reply(messages().get("reset.cancelled"));
        return;
      }
      run(source, second.keepHall());
    });
    return true;
  }

  /** Forgets a leaving operator's reset. */
  void left(PlayerId player) {
    pending.remove(player.uuid().toString());
  }

  /** Why a reset can't run now, as a message key. */
  private Optional<String> busy() {
    Optional<SeasonState> current = state.get();
    if (current.isPresent()
        && (current.get().phase() instanceof Phase.Countdown
            || current.get().phase() instanceof Phase.Archiving)) {
      return Optional.of("reset.ending");
    }
    if (!hold.held().isEmpty()) {
      return Optional.of("reset.busy");
    }
    if (journal.map(j -> !JournalRecovery.openOperations(j.entries()).isEmpty()).orElse(false)) {
      return Optional.of("reset.busy");
    }
    return Optional.empty();
  }

  /**
   * The reset itself (questions 283): the backup, then {@code reset-pending} and the journal before
   * the first change to the world; then Seasons' menus closed and Seasons stopped, so nothing of it
   * can draw a display again or leave a view-only copy takeable; then the displays, objectives,
   * game modes and files; then Seasons disables itself.
   */
  private void run(CommandSource source, boolean keepHall) {
    Messages messages = messages();
    Optional<String> busy = busy();
    if (busy.isPresent()) {
      source.reply(messages.get(busy.get()));
      return;
    }
    Path backup =
        configDirectory
            .resolve("reset-backups")
            .resolve(worldName() + "-" + STAMP.format(clock.instant()) + ".zip");
    List<Bounds> boxes = boxes(halls.galleries());
    try {
      zip(world, backup);
      writePending(configDirectory.resolve(PENDING), keepHall, boxes);
    } catch (IOException e) {
      LOG.error("The reset's backup failed, so nothing was reset", e);
      source.reply(messages.get("reset.backup-failed", "error", String.valueOf(e.getMessage())));
      return;
    }
    source.reply(messages.get("reset.backed-up", "file", String.valueOf(backup.getFileName())));
    int season = state.get().map(SeasonState::season).orElse(0);
    CompletableFuture<?> journalled =
        journal
            .<CompletableFuture<?>>map(j ->
                j.append(
                    JournalKind.RESET_STARTED,
                    source.player().orElse(null),
                    season,
                    keepHall ? "reset-keep-hall" : "reset"))
            .orElseGet(() -> CompletableFuture.completedFuture(null));
    var _ =
        journalled.whenCompleteAsync(
            (entry, failure) -> {
              if (failure != null) {
                LOG.error("The reset couldn't be journalled, so nothing was reset", failure);
                try {
                  Files.deleteIfExists(configDirectory.resolve(PENDING));
                } catch (IOException e) {
                  LOG.error("Can't remove {}", configDirectory.resolve(PENDING), e);
                }
                source.reply(
                    messages()
                        .get(
                            "reset.not-journalled", "error", String.valueOf(failure.getMessage())));
                return;
              }
              stopThenClear(source, keepHall, boxes);
            },
            serverThread);
  }

  private void stopThenClear(CommandSource source, boolean keepHall, List<Bounds> boxes) {
    Messages messages = messages();
    List<PlayerId> switched = new ArrayList<>();
    List<PlayerId> admins = new ArrayList<>();
    for (PlayerRef online : platform.players().online()) {
      PlayerId player = online.id();
      // A view-only copy outlives the listener that keeps it so once Seasons stops (questions 283).
      platform.menus().closeOwn(player);
      if (halls.switched(player) && platform.players().gameMode(player) == GameMode.ADVENTURE) {
        switched.add(player);
      }
      if (platform.permissions().has(player, SeasonService.ADMIN_PERMISSION)
          && !source.player().map(player::equals).orElse(false)) {
        admins.add(player);
      }
    }
    // Stopped before the displays go, so reconciliation can't draw one again meanwhile, nor the
    // reset's own chunk loads queue one (questions 283); and so nothing writes the files again.
    stopSeasons.run();
    var _ =
        removeDisplays(platform, boxes)
            .whenCompleteAsync(
                (done, failure) -> {
                  if (failure != null) {
                    LOG.warn("Some displays weren't removed by the reset", failure);
                  }
                  finish(source, keepHall, switched, admins, messages);
                },
                serverThread);
  }

  private void finish(
      CommandSource source,
      boolean keepHall,
      List<PlayerId> switched,
      List<PlayerId> admins,
      Messages messages) {
    platform.players().removeObjectives("seasons_deaths_s");
    for (PlayerId player : switched) {
      // Their records go with the folder, so there is nothing to mark.
      if (platform.players().find(player).isPresent()) {
        platform.players().setGameMode(player, GameMode.SURVIVAL);
      }
    }
    try {
      delete(world, keepHall);
      Files.deleteIfExists(configDirectory.resolve(PENDING));
    } catch (IOException e) {
      LOG.error("The reset couldn't delete every file; the next start finishes it", e);
      source.reply(messages.get("reset.delete-failed", "error", String.valueOf(e.getMessage())));
    }
    String name = source.player().flatMap(p -> platform.players().knownName(p)).orElse(CONSOLE);
    LOG.warn(
        "Seasons was reset by {}{}; the backup is in {}",
        name,
        keepHall ? ", keeping the Hall's layout" : "",
        configDirectory.resolve("reset-backups"));
    for (PlayerId admin : admins) {
      platform.players().sendMessage(admin, messages.get("reset.stopped-by", "name", name));
    }
    source.reply(messages.get("reset.done"));
    platform.storage().stopSeasons();
  }

  /** Where Seasons' displays may be: each gallery's bounds, else each of its cells'. */
  static List<Bounds> boxes(java.util.Collection<Gallery> galleries) {
    List<Bounds> boxes = new ArrayList<>();
    for (Gallery gallery : galleries) {
      if (gallery.bounds().isPresent()) {
        boxes.add(gallery.bounds().get());
      } else {
        gallery.cells().forEach(cell -> cell.bounds().ifPresent(boxes::add));
      }
    }
    return boxes;
  }

  /** Every Seasons display in the boxes, their chunks loaded first; on the server thread. */
  static CompletableFuture<Void> removeDisplays(Platform platform, List<Bounds> boxes) {
    List<CompletableFuture<Void>> loads = new ArrayList<>();
    for (Bounds b : boxes) {
      for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) {
        for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) {
          loads.add(
              platform.world().loadChunk(Position.of(b.dimension(), x * 16, b.minY(), z * 16)));
        }
      }
    }
    Executor serverThread = platform.scheduler()::runGlobal;
    return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
        .thenRunAsync(
            () -> boxes.forEach(b -> platform.displays().removeTagged(b.min(), b.max(), "")),
            serverThread);
  }

  /**
   * At startup, before anything reads {@code <world>/seasons/}: finishes a reset that a crash or a
   * failed delete left half done, as {@code plugins/Seasons/reset-pending} records it (questions
   * 283), so the next start never loads a half-deleted folder as real data.
   *
   * @return the boxes whose displays still have to go, empty when no reset was pending
   */
  static List<Bounds> finishInterrupted(Path world, Path configDirectory) throws IOException {
    Path pending = configDirectory.resolve(PENDING);
    if (!Files.exists(pending)) {
      return List.of();
    }
    boolean keepHall = false;
    List<Bounds> boxes = new ArrayList<>();
    for (String line : Files.readAllLines(pending)) {
      List<String> words = Words.of(line);
      if (words.size() == 2 && words.get(0).equals("keepHall")) {
        keepHall = Boolean.parseBoolean(words.get(1));
      } else if (words.size() == 8 && words.get(0).equals("box")) {
        boxes.add(
            new Bounds(
                words.get(1),
                Integer.parseInt(words.get(2)),
                Integer.parseInt(words.get(3)),
                Integer.parseInt(words.get(4)),
                Integer.parseInt(words.get(5)),
                Integer.parseInt(words.get(6)),
                Integer.parseInt(words.get(7))));
      }
    }
    LOG.warn("Finishing a /season reset that didn't finish; its backup is in reset-backups");
    delete(world, keepHall);
    Files.delete(pending);
    return boxes;
  }

  /** {@code reset-pending}: whether the Hall is kept and where the displays are, fsynced. */
  private static void writePending(Path file, boolean keepHall, List<Bounds> boxes)
      throws IOException {
    StringBuilder text = new StringBuilder("keepHall " + keepHall + "\n");
    for (Bounds b : boxes) {
      text.append(
          String.join(
                  " ",
                  "box",
                  b.dimension(),
                  Integer.toString(b.minX()),
                  Integer.toString(b.minY()),
                  Integer.toString(b.minZ()),
                  Integer.toString(b.maxX()),
                  Integer.toString(b.maxY()),
                  Integer.toString(b.maxZ()))
              + "\n");
    }
    Files.createDirectories(file.getParent());
    AtomicFiles.write(file, text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Deletes {@code <world>/seasons}, keeping with {@code --keep-hall} the Hall's layout: {@code
   * hall.yml}, {@code templates/} and the gallery files, each read back from disk and written again
   * without owners, imports or gate states. Safe to run again on what an earlier run left.
   */
  private static void delete(Path world, boolean keepHall) throws IOException {
    if (!Files.exists(world)) {
      return;
    }
    Path galleries = world.resolve("galleries");
    if (keepHall && Files.isDirectory(galleries)) {
      try (Stream<Path> files = Files.list(galleries)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
          strip(file);
        }
      }
    }
    Files.walkFileTree(
        world,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (keepHall && (dir.equals(world.resolve("templates")) || dir.equals(galleries))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!(keepHall && file.equals(world.resolve("hall.yml")))) {
              Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException failure)
              throws IOException {
            if (!dir.equals(world)) {
              try (Stream<Path> left = Files.list(dir)) {
                if (left.findAny().isEmpty()) {
                  Files.delete(dir);
                }
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** A gallery file written again with its layout only: who owned what goes with the seasons. */
  private static void strip(Path file) throws IOException {
    String name = file.getFileName().toString();
    String id = name.substring(0, name.length() - ".yml".length());
    Gallery gallery;
    try {
      gallery =
          GalleryFiles.read(
              id,
              org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                  .path(file)
                  .build()
                  .load());
    } catch (GalleryFiles.Invalid | IOException e) {
      // A file that doesn't load stays as it was, for the admin to fix; the backup has it too.
      LOG.warn("Kept {} as it was: it doesn't load ({})", file, e.getMessage());
      return;
    }
    Gallery bare = gallery;
    for (Cell cell : gallery.cells()) {
      bare =
          bare.withCell(
              new Cell(
                  cell.id(), cell.bounds(), cell.anchors(), Map.of(), false, cell.pose(), false));
    }
    // No backup of its own: the reset's zip has the file as it was.
    Path scratch = Files.createTempDirectory("seasons-reset");
    try {
      GalleryFiles.write(file, scratch, bare);
    } finally {
      try (Stream<Path> left = Files.walk(scratch)) {
        for (Path path : left.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static void zip(Path from, Path to) throws IOException {
    Files.createDirectories(to.getParent());
    try (OutputStream out = Files.newOutputStream(to);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      if (Files.exists(from)) {
        try (Stream<Path> files = Files.walk(from)) {
          for (Path file : files.filter(Files::isRegularFile).toList()) {
            zip.putNextEntry(new ZipEntry(from.relativize(file).toString().replace('\\', '/')));
            Files.copy(file, zip);
            zip.closeEntry();
          }
        }
      }
      zip.finish();
      out.flush();
      if (out instanceof java.io.FileOutputStream file) {
        file.getFD().sync();
      }
    }
    try (java.nio.channels.FileChannel channel =
        java.nio.channels.FileChannel.open(to, java.nio.file.StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private static long count(Path dir) {
    if (!Files.isDirectory(dir)) {
      return 0;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files.filter(Files::isRegularFile).count();
    } catch (IOException e) {
      return 0;
    }
  }

  /** The world folder's name: {@code <world>/seasons}'s parent. */
  private String worldName() {
    Path parent = world.toAbsolutePath().getParent();
    Path name = parent == null ? null : parent.getFileName();
    return name == null ? "world" : name.toString();
  }

  private CommandSource source(PlayerId player) {
    return new CommandSource() {
      @Override
      public Optional<PlayerId> player() {
        return Optional.of(player);
      }

      @Override
      public void reply(com.petepeterepeat.seasons.core.message.Message message) {
        platform.players().sendMessage(player, message);
      }
    };
  }

  private Messages messages() {
    return config.get().messages();
  }
}
