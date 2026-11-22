// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Words;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
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
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.Journal;
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

  /** The reset itself: backup, displays, objectives, game modes, files, then Seasons stops. */
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
    try {
      zip(world, backup);
    } catch (IOException e) {
      LOG.error("The reset's backup failed, so nothing was reset", e);
      source.reply(messages.get("reset.backup-failed", "error", String.valueOf(e.getMessage())));
      return;
    }
    source.reply(messages.get("reset.backed-up", "file", String.valueOf(backup.getFileName())));
    List<Gallery> galleries = List.copyOf(halls.galleries());
    var _ =
        removeDisplays(galleries)
            .whenCompleteAsync(
                (done, failure) -> {
                  if (failure != null) {
                    LOG.warn("Some displays weren't removed by the reset", failure);
                  }
                  finish(source, keepHall, galleries);
                },
                serverThread);
  }

  private void finish(CommandSource source, boolean keepHall, List<Gallery> galleries) {
    platform.players().removeObjectives("seasons_deaths_s");
    for (PlayerRef online : platform.players().online()) {
      PlayerId player = online.id();
      if (halls.switched(player) && platform.players().gameMode(player) == GameMode.ADVENTURE) {
        platform.players().setGameMode(player, GameMode.SURVIVAL);
      }
      halls.forgetSwitched(player);
    }
    // Stopped first, so nothing it does on the way out writes its files again.
    stopSeasons.run();
    try {
      delete(keepHall, galleries);
    } catch (IOException e) {
      LOG.error("The reset couldn't delete every file; the backup has them all", e);
      source.reply(messages().get("reset.delete-failed", "error", String.valueOf(e.getMessage())));
    }
    LOG.warn(
        "Seasons was reset by {}{}; the backup is in {}",
        source.player().flatMap(p -> platform.players().knownName(p)).orElse(CONSOLE),
        keepHall ? ", keeping the Hall's layout" : "",
        configDirectory.resolve("reset-backups"));
    source.reply(messages().get("reset.done"));
    platform.storage().stopSeasons();
  }

  /** Every Seasons display of every gallery, its chunks loaded first. */
  private CompletableFuture<Void> removeDisplays(List<Gallery> galleries) {
    List<CompletableFuture<Void>> loads = new ArrayList<>();
    for (Gallery gallery : galleries) {
      gallery
          .bounds()
          .ifPresent(b -> {
            for (int x = b.minX() >> 4; x <= b.maxX() >> 4; x++) {
              for (int z = b.minZ() >> 4; z <= b.maxZ() >> 4; z++) {
                loads.add(
                    platform
                        .world()
                        .loadChunk(Position.of(b.dimension(), x * 16, b.minY(), z * 16)));
              }
            }
          });
    }
    return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
        .thenRunAsync(
            () -> {
              for (Gallery gallery : galleries) {
                gallery
                    .bounds()
                    .ifPresent(b -> platform.displays().removeTagged(b.min(), b.max(), ""));
              }
            },
            serverThread);
  }

  /** Deletes {@code <world>/seasons}, keeping with {@code --keep-hall} the Hall's layout. */
  private void delete(boolean keepHall, List<Gallery> galleries) throws IOException {
    if (!Files.exists(world)) {
      return;
    }
    Files.walkFileTree(
        world,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (keepHall && dir.equals(world.resolve("templates"))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            boolean kept =
                keepHall
                    && (file.equals(world.resolve("hall.yml"))
                        || world.resolve("galleries").equals(file.getParent()));
            if (!kept) {
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
    if (keepHall) {
      // The layout stays; who owned what, and which gates were shut, go with the seasons.
      Path backups = Files.createTempDirectory("seasons-reset");
      for (Gallery gallery : galleries) {
        Path file = world.resolve("galleries").resolve(gallery.id() + ".yml");
        if (!Files.exists(file)) {
          continue;
        }
        Gallery bare = gallery;
        for (Cell cell : gallery.cells()) {
          bare =
              bare.withCell(
                  new Cell(
                      cell.id(),
                      cell.bounds(),
                      cell.anchors(),
                      Map.of(),
                      false,
                      cell.pose(),
                      false));
        }
        GalleryFiles.write(file, backups, bare);
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
