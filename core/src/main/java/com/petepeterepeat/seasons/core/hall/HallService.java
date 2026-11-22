// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchivePages;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.season.Decorating;
import com.petepeterepeat.seasons.core.season.HumanTime;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Hall of Fame at run time: galleries and cells as the gallery files define them, who owns
 * which cell, the decorating window and its lock, swaps, game modes in the Hall, building rules
 * inside it, and opening archive views from anchors and figures. Everything here runs on the server
 * thread; gallery files are written on the writer thread.
 */
public final class HallService {
  /** Who may build anywhere in the Hall, ops included only if granted. */
  public static final String BYPASS = "seasons.hof.bypass";

  /** How long a swap request waits for the other player. */
  public static final Duration SWAP_WINDOW = Duration.ofSeconds(60);

  private static final Logger LOG = LoggerFactory.getLogger(HallService.class);
  private static final Duration SWEEP = Duration.ofMillis(500);
  private static final Duration TICK = Duration.ofSeconds(1);

  private final Platform platform;
  private final ConfigHolder config;
  private final Function<String, Path> galleryFile;
  private Path hallFile;
  private final Path backups;
  private final SerialWriter writer;
  private final Clock clock;
  private final PlayerStore players;
  private final ArchiveFiles archives;
  private final Announcer announcer;
  private final AuditLog audit;
  private final RandomGenerator random;
  private final Map<PlayerId, SwapRequest> swaps = new HashMap<>();

  /**
   * Galleries a stamp is placing copies in, or one a crash or failure left open (questions 242).
   */
  private final Set<String> stamping = new HashSet<>();

  /** Players without a record yet (before /season init) whom Seasons switched to adventure. */
  private final Set<PlayerId> switchedWithoutRecord = new HashSet<>();

  private Supplier<Optional<SeasonState>> state = Optional::empty;
  private Consumer<PlayerId> launch = player -> {};

  /** When each admin last pressed a launch button, for the second press that launches. */
  private final Map<PlayerId, Instant> launchPresses = new HashMap<>();

  /** How long after a first press on the launch button a second one launches. */
  public static final Duration LAUNCH_CONFIRM = Duration.ofSeconds(10);

  private Consumer<UnaryOperator<Decorating>> decorating = change -> {};
  private Instant lastTick;
  private @Nullable ScheduledTask sweeper;
  private @Nullable ScheduledTask ticker;

  /** A cell and the gallery it is in. */
  public record CellRef(Gallery gallery, Cell cell) {
    /** Such as {@code season-2:c1}. */
    public String key() {
      return gallery.id() + ":" + cell.id();
    }
  }

  private record SwapRequest(PlayerId with, Instant at) {}

  /**
   * @param galleryFile where a gallery's file lives, by id
   * @param backups where the last copies of each edited gallery file go
   */
  public HallService(
      Platform platform,
      ConfigHolder config,
      Function<String, Path> galleryFile,
      Path backups,
      SerialWriter writer,
      Clock clock,
      PlayerStore players,
      ArchiveFiles archives,
      Announcer announcer,
      AuditLog audit,
      RandomGenerator random) {
    this.platform = platform;
    this.config = config;
    this.galleryFile = galleryFile;
    this.hallFile =
        backups.resolveSibling(com.petepeterepeat.seasons.core.config.ConfigLoader.HALL);
    this.backups = backups;
    this.writer = writer;
    this.clock = clock;
    this.players = players;
    this.archives = archives;
    this.announcer = announcer;
    this.audit = audit;
    this.random = random;
    this.lastTick = clock.instant();
  }

  /**
   * Connects the season state, once the season service exists.
   *
   * @param decorating applies a change to the decorating window and saves it
   */
  public void bind(
      Supplier<Optional<SeasonState>> state, Consumer<UnaryOperator<Decorating>> decorating) {
    this.state = state;
    this.decorating = decorating;
  }

  /** The season state, as last bound; empty before {@code /season init}. */
  public Optional<SeasonState> seasonState() {
    return state.get();
  }

  /** Connects the launch button to {@code /season launch}, run as the player who pressed it. */
  public void bindLaunch(Consumer<PlayerId> launch) {
    this.launch = launch;
  }

  /** Starts the game-mode sweep and the decorating clock. */
  public void start() {
    lastTick = clock.instant();
    sweeper = platform.scheduler().runGlobalRepeating(SWEEP, this::sweep);
    ticker = platform.scheduler().runGlobalRepeating(TICK, this::tick);
  }

  public void stop() {
    if (sweeper != null) {
      sweeper.cancel();
    }
    if (ticker != null) {
      ticker.cancel();
    }
  }

  /** The seams archiving uses. */
  public Hall hall(DisplayRenderer renderer) {
    return new Hall(this::lockAll, directory(), renderer, this::inHall);
  }

  // Galleries and cells

  public Collection<Gallery> galleries() {
    return config.get().allGalleries();
  }

  public Optional<Gallery> gallery(String id) {
    return config.get().gallery(id);
  }

  /** The galleries of one season, in id order. */
  public List<Gallery> seasonGalleries(int season) {
    return galleries().stream()
        .filter(g -> g.kind() == Gallery.Kind.SEASON && g.season() == season)
        .toList();
  }

  /**
   * Holds a gallery for a stamp: until it is released, the gallery's bounds, entry, exits and
   * launch button and its cells' bounds can't change, nor cells be made in it (questions 242).
   */
  public void holdForStamp(String gallery) {
    stamping.add(gallery);
  }

  /** Lets go of a gallery a stamp held, once the stamp is finished or refused. */
  public void releaseStamp(String gallery) {
    stamping.remove(gallery);
  }

  /** Whether a stamp holds a gallery. */
  public boolean heldForStamp(String gallery) {
    return stamping.contains(gallery);
  }

  /** Where hall.yml lives; beside the backups folder unless told otherwise. */
  public void hallFile(Path file) {
    this.hallFile = file;
  }

  /** The Hall's own parts: the hub, the Hall spawn, the Hall's exits and links. */
  public HallLayout layout() {
    return config.get().hall();
  }

  /** Where plain {@code /hof} lands: the Hall spawn, standing in its block (questions 244). */
  public Optional<Position> hallSpawn() {
    return layout().spawn().map(HallService::standOn);
  }

  /** Whether hall.yml was left out at startup, so it mustn't be written over. */
  public boolean hallLeftOut() {
    return !config.get().hallLeftOut().isEmpty();
  }

  /**
   * Saves hall.yml: the live config has it at once, and the file is written on the writer thread.
   * Refused while hall.yml is left out, since writing it would replace the admin's hub, spawn and
   * links with what little the live config holds.
   */
  public CompletableFuture<Void> saveHall(HallLayout hall) {
    if (hallLeftOut()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("hall.yml was left out at startup; fix it and /season reload"));
    }
    config.set(config.get().withHall(hall));
    Path file = hallFile;
    Path backupDir = backups.resolve("hall");
    return writer
        .submit(() -> {
          HallFiles.write(file, backupDir, hall);
          return Boolean.TRUE;
        })
        .thenApply(done -> (Void) null)
        .whenComplete((done, failure) -> {
          if (failure != null) {
            LOG.error("Can't write {}", file, failure);
          }
        });
  }

  /**
   * Saves a gallery: the live config has it at once, and its file is written on the writer thread.
   */
  public CompletableFuture<Void> save(Gallery gallery) {
    config.set(config.get().withGallery(gallery));
    Path file = galleryFile.apply(gallery.id());
    Path backupDir = backups.resolve("galleries");
    return writer
        .submit(() -> {
          GalleryFiles.write(file, backupDir, gallery);
          return Boolean.TRUE;
        })
        .thenApply(done -> (Void) null)
        .whenComplete((done, failure) -> {
          if (failure != null) {
            LOG.error("Can't write {}", file, failure);
          }
        });
  }

  /** Removes a gallery and its file, keeping a backup (questions 269). */
  public CompletableFuture<Void> remove(Gallery gallery) {
    config.set(config.get().withoutGallery(gallery.id()));
    Path file = galleryFile.apply(gallery.id());
    Path backupDir = backups.resolve("galleries");
    return writer
        .submit(() -> {
          com.petepeterepeat.seasons.core.store.AtomicFiles.deleteKeepingBackup(file, backupDir);
          return Boolean.TRUE;
        })
        .thenApply(done -> (Void) null)
        .whenComplete((done, failure) -> {
          if (failure != null) {
            LOG.error("Can't remove {}", file, failure);
          }
        });
  }

  // Undo (questions 275)

  /** What {@code /hof undo} puts back: a file as it was before an edit, and as the edit left it. */
  private sealed interface Undo {
    record OfGallery(String id, Optional<Gallery> before, Optional<Gallery> after)
        implements Undo {}

    record OfHall(HallLayout before, HallLayout after) implements Undo {}
  }

  private final Map<String, Undo> undos = new HashMap<>();

  private static String undoKey(com.petepeterepeat.seasons.core.command.CommandSource source) {
    return source.player().map(p -> p.uuid().toString()).orElse("console");
  }

  /** Remembers a gallery as it is now, about to become {@code after}, for the source's undo. */
  public void rememberUndo(
      com.petepeterepeat.seasons.core.command.CommandSource source, Gallery after) {
    undos.put(
        undoKey(source), new Undo.OfGallery(after.id(), gallery(after.id()), Optional.of(after)));
  }

  /** Remembers a gallery about to be removed. */
  public void rememberRemoval(
      com.petepeterepeat.seasons.core.command.CommandSource source, Gallery removed) {
    undos.put(
        undoKey(source), new Undo.OfGallery(removed.id(), Optional.of(removed), Optional.empty()));
  }

  /** Remembers hall.yml as it is now, about to become {@code after}. */
  public void rememberUndo(
      com.petepeterepeat.seasons.core.command.CommandSource source, HallLayout after) {
    undos.put(undoKey(source), new Undo.OfHall(layout(), after));
  }

  /** What an undo came to. */
  public sealed interface UndoResult {
    record Nothing() implements UndoResult {}

    record Changed(String file) implements UndoResult {}

    record Undone(String file, CompletableFuture<Void> saved, Optional<Gallery> gallery)
        implements UndoResult {}
  }

  /**
   * {@code /hof undo}: the source's last Hall edit put back, if its file is as the edit left it.
   */
  public UndoResult undo(com.petepeterepeat.seasons.core.command.CommandSource source) {
    Undo undo = undos.get(undoKey(source));
    if (undo == null) {
      return new UndoResult.Nothing();
    }
    return switch (undo) {
      case Undo.OfGallery(String id, Optional<Gallery> before, Optional<Gallery> after) -> {
        if (!gallery(id).equals(after)) {
          yield new UndoResult.Changed("galleries/" + id + ".yml");
        }
        undos.remove(undoKey(source));
        CompletableFuture<Void> saved =
            before.isPresent() ? save(before.get()) : remove(after.orElseThrow());
        yield new UndoResult.Undone("galleries/" + id + ".yml", saved, before);
      }
      case Undo.OfHall(HallLayout before, HallLayout after) -> {
        if (!layout().equals(after)) {
          yield new UndoResult.Changed("hall.yml");
        }
        undos.remove(undoKey(source));
        yield new UndoResult.Undone("hall.yml", saveHall(before), Optional.empty());
      }
    };
  }

  /** Records a Hall edit in the audit log. */
  public void audited(String actor, String action, String target, String details) {
    audit.record(actor, action, target, "ok", details);
  }

  /** What a cell argument names. */
  public sealed interface Resolved {
    record Found(CellRef ref) implements Resolved {}

    record NotFound() implements Resolved {}

    record Ambiguous() implements Resolved {}
  }

  /** A cell named as {@code gallery:cell}, or by its id alone when only one gallery has it. */
  public Resolved resolve(String name) {
    int colon = name.indexOf(':');
    if (colon >= 0) {
      Optional<Gallery> gallery = gallery(name.substring(0, colon));
      Optional<Cell> cell = gallery.flatMap(g -> g.cell(name.substring(colon + 1)));
      return cell.isPresent()
          ? new Resolved.Found(new CellRef(gallery.orElseThrow(), cell.get()))
          : new Resolved.NotFound();
    }
    List<CellRef> found = new ArrayList<>();
    for (Gallery gallery : galleries()) {
      gallery.cell(name).ifPresent(cell -> found.add(new CellRef(gallery, cell)));
    }
    return switch (found.size()) {
      case 0 -> new Resolved.NotFound();
      case 1 -> new Resolved.Found(found.getFirst());
      default -> new Resolved.Ambiguous();
    };
  }

  /** The cell a player owns for a season, in that season's galleries. */
  public Optional<CellRef> cellOf(PlayerId player, int season) {
    for (Gallery gallery : seasonGalleries(season)) {
      Optional<Cell> cell = gallery.cellOf(player);
      if (cell.isPresent()) {
        return Optional.of(new CellRef(gallery, cell.get()));
      }
    }
    return Optional.empty();
  }

  /** The cell whose bounds hold a position, in any gallery. */
  public Optional<CellRef> cellAt(Position position) {
    for (Gallery gallery : galleries()) {
      if (gallery.contains(position)) {
        Optional<Cell> cell = gallery.cellAt(position);
        if (cell.isPresent()) {
          return Optional.of(new CellRef(gallery, cell.get()));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Whether a block is Seasons' to protect: in the Hall, or a link's block anywhere (questions
   * 248).
   */
  public boolean guarded(Position block) {
    return inHall(block) || layout().linkAt(block).isPresent();
  }

  /** Whether a position is in the Hall: inside the hub or any gallery's bounds. */
  public boolean inHall(Position position) {
    return config.get().inHall(position);
  }

  /**
   * Whether a player may be inside a cell now: anyone may, unless its gallery's access is {@code
   * bounds}, where only its owner may while they may decorate it, and bypass holders (questions
   * 168).
   */
  public boolean mayBeIn(PlayerId player, CellRef ref) {
    return ref.gallery().access(config.get().config()) != SeasonsConfig.CellAccess.BOUNDS
        || canDecorate(player, ref)
        || platform.permissions().has(player, BYPASS);
  }

  /**
   * Whether a position is in the gate plane of a cell in a {@code gate} gallery, inside the cell or
   * in its doorway (questions 250).
   */
  public boolean inGate(Position position) {
    SeasonsConfig settings = config.get().config();
    for (Gallery gallery : galleries()) {
      if (gallery.access(settings) != SeasonsConfig.CellAccess.GATE
          || !gallery.contains(position)) {
        continue;
      }
      for (Cell cell : gallery.cells()) {
        if (cell.gate().map(plane -> plane.contains(position)).orElse(false)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Where a player stands in a gallery outside its cells: its entry, else the Hall spawn. */
  public Optional<Position> galleryEntry(Gallery gallery) {
    return gallery.entry().map(HallService::standOn).or(this::hallSpawn);
  }

  /** Where to send a player to their cell: its entry anchor, else in front of its figure. */
  public Optional<Position> arrival(CellRef ref) {
    Optional<Anchor> entry = ref.cell().anchor(AnchorType.ENTRY);
    if (entry.isPresent()) {
      return Optional.of(standOn(entry.get().at()));
    }
    Optional<Anchor> figure = ref.cell().anchor(AnchorType.FIGURE);
    if (figure.isPresent()) {
      Position at = figure.get().at();
      double radians = Math.toRadians(at.yaw());
      // Two blocks in front of the figure, facing it.
      Position front =
          new Position(
              at.dimension(),
              at.blockX() + 0.5 - 2 * Math.sin(radians),
              at.y(),
              at.blockZ() + 0.5 + 2 * Math.cos(radians),
              at.yaw() + 180f,
              0f);
      return Optional.of(front);
    }
    return ref.gallery().entry().map(HallService::standOn);
  }

  /** The middle of a block, where a player stands, keeping the yaw. */
  static Position standOn(Position block) {
    return new Position(
        block.dimension(),
        block.blockX() + 0.5,
        block.blockY(),
        block.blockZ() + 0.5,
        block.yaw(),
        0f);
  }

  // Assignment

  /** Whether a cell can be handed out for a season: it has a figure anchor and no owner. */
  static boolean isFree(Cell cell, int season) {
    return cell.owner(season).isEmpty() && cell.anchor(AnchorType.FIGURE).isPresent();
  }

  /** The free cells of a season's galleries. */
  public List<CellRef> freeCells(int season) {
    List<CellRef> free = new ArrayList<>();
    for (Gallery gallery : seasonGalleries(season)) {
      for (Cell cell : gallery.cells()) {
        if (isFree(cell, season)) {
          free.add(new CellRef(gallery, cell));
        }
      }
    }
    return free;
  }

  /** Whether a player has played enough of a season to earn a cell. */
  boolean earnsCell(PlayerRecord record, int season) {
    Duration needed = config.get().config().cells().minPlaytime();
    return Optional.ofNullable(record.seasons().get(season))
        .map(play -> play.playTime().compareTo(needed) >= 0)
        .orElse(false);
  }

  /** Players who earned a cell in a season and have none. */
  public List<PlayerRecord> withoutCell(int season) {
    return players.all().stream()
        .filter(r -> earnsCell(r, season))
        .filter(r -> cellOf(r.id(), season).isEmpty())
        .sorted(Comparator.comparing(PlayerRecord::lastName))
        .toList();
  }

  /**
   * The player's cell for a season, handing them a free one first if they earned one and the
   * assignment strategy hands cells out. Tells them which, if they are online.
   */
  public Optional<CellRef> ensureCell(PlayerId player, int season) {
    Optional<CellRef> owned = cellOf(player, season);
    if (owned.isPresent()) {
      return owned;
    }
    Optional<PlayerRecord> record = players.find(player);
    if (record.isEmpty() || !earnsCell(record.get(), season)) {
      return Optional.empty();
    }
    List<CellRef> free = freeCells(season).stream().filter(ref -> handsOut(ref.gallery())).toList();
    if (free.isEmpty()) {
      return Optional.empty();
    }
    CellRef chosen = pick(free);
    CellRef assigned = assign(chosen, season, Optional.of(player));
    tell(player, season, assigned);
    return Optional.of(assigned);
  }

  /** Hands everyone who played a season and has no cell a free one. Returns how many own one. */
  public int assignAll(int season) {
    if (seasonGalleries(season).stream().anyMatch(this::handsOut)) {
      for (PlayerRecord record : withoutCell(season)) {
        ensureCell(record.id(), season);
      }
    }
    return (int)
        seasonGalleries(season).stream()
            .flatMap(g -> g.cells().stream())
            .filter(c -> c.owner(season).isPresent())
            .count();
  }

  /** Whether a gallery's cells are claimed with {@code /hof claim}. */
  private boolean claims(Gallery gallery) {
    return gallery.assignment(config.get().config()) == SeasonsConfig.CellAssignment.CLAIM;
  }

  /** The free cells of a season's {@code claim} galleries. */
  private List<CellRef> claimable(int season) {
    return freeCells(season).stream().filter(ref -> claims(ref.gallery())).toList();
  }

  /**
   * A player archived without a cell who could have claimed one gets a free one at random, so
   * nobody who played goes without displays while a cell is free (questions 167).
   */
  private Optional<CellRef> unclaimed(PlayerId player, int season) {
    List<CellRef> free = claimable(season);
    if (free.isEmpty() || !players.find(player).map(r -> earnsCell(r, season)).orElse(false)) {
      return Optional.empty();
    }
    CellRef assigned = assign(free.get(random.nextInt(free.size())), season, Optional.of(player));
    tell(player, season, assigned);
    return Optional.of(assigned);
  }

  /** What {@code /hof claim} came to. */
  public sealed interface ClaimResult {
    record Refused(String messageKey, List<String> placeholders) implements ClaimResult {}

    record Claimed(CellRef cell) implements ClaimResult {}

    /** No cell named and none where they stand: the free cells to choose from. */
    record Free(List<CellRef> cells) implements ClaimResult {}
  }

  private static ClaimResult refusedClaim(String key, String... placeholders) {
    return new ClaimResult.Refused(key, List.of(placeholders));
  }

  /**
   * {@code /hof claim [cell]}: a free cell of a {@code claim} gallery, during the window, for a
   * player who played the season and has none. Without a cell, the one they stand in.
   */
  public ClaimResult claim(PlayerId player, Optional<String> name) {
    Optional<SeasonState> found = state.get();
    if (found.isEmpty() || !playing(found.get()) || !found.get().decorating().open()) {
      return refusedClaim("hof.claim.closed");
    }
    int season = found.get().season();
    Optional<CellRef> mine = cellOf(player, season);
    if (mine.isPresent()) {
      return refusedClaim("hof.claim.already", "cell", mine.get().cell().id());
    }
    if (!players.find(player).map(r -> earnsCell(r, season)).orElse(false)) {
      return refusedClaim("hof.claim.not-played", "season", Integer.toString(season));
    }
    List<CellRef> free = claimable(season);
    CellRef chosen;
    if (name.isEmpty()) {
      Optional<CellRef> here =
          cellAt(platform.players().position(player))
              .filter(ref -> free.stream().anyMatch(f -> f.key().equals(ref.key())));
      if (here.isEmpty()) {
        return free.isEmpty() ? refusedClaim("hof.claim.none-free") : new ClaimResult.Free(free);
      }
      chosen = here.get();
    } else {
      String cell = name.get();
      CellRef ref;
      switch (resolve(cell)) {
        case Resolved.Found(CellRef named) -> ref = named;
        case Resolved.Ambiguous() -> {
          return refusedClaim("hof.ambiguous-cell", "cell", cell);
        }
        case Resolved.NotFound() -> {
          return refusedClaim("hof.no-cell", "cell", cell);
        }
      }
      if (ref.gallery().kind() != Gallery.Kind.SEASON || ref.gallery().season() != season) {
        return refusedClaim(
            "hof.claim.other-season", "cell", cell, "season", Integer.toString(season));
      }
      if (!claims(ref.gallery())) {
        return refusedClaim("hof.claim.not-claimed", "gallery", ref.gallery().id());
      }
      if (!isFree(ref.cell(), season)) {
        return refusedClaim("hof.claim.taken", "cell", ref.cell().id());
      }
      chosen = ref;
    }
    CellRef claimed = assign(chosen, season, Optional.of(player));
    audit.record(
        platform.players().knownName(player).orElse(player.toString()),
        "claim",
        claimed.key(),
        "claimed",
        "Season " + season);
    return new ClaimResult.Claimed(claimed);
  }

  /** Whether a gallery hands its cells out by itself: random or ordered assignment. */
  private boolean handsOut(Gallery gallery) {
    SeasonsConfig.CellAssignment strategy = gallery.assignment(config.get().config());
    return strategy == SeasonsConfig.CellAssignment.RANDOM
        || strategy == SeasonsConfig.CellAssignment.ORDERED;
  }

  /** The first free cell if its gallery hands cells out in order, else one at random. */
  private CellRef pick(List<CellRef> free) {
    CellRef first = free.getFirst();
    if (first.gallery().assignment(config.get().config()) == SeasonsConfig.CellAssignment.ORDERED) {
      return first;
    }
    return free.get(random.nextInt(free.size()));
  }

  /** Sets or clears a cell's owner for a season, and saves the gallery. */
  public CellRef assign(CellRef ref, int season, Optional<PlayerId> owner) {
    Gallery current = gallery(ref.gallery().id()).orElse(ref.gallery());
    Cell cell = current.cell(ref.cell().id()).orElse(ref.cell()).withOwner(season, owner);
    Gallery next = current.withCell(cell);
    var _ = save(next);
    return new CellRef(next, cell);
  }

  private void tell(PlayerId player, int season, CellRef ref) {
    if (platform.players().find(player).isPresent()) {
      platform
          .players()
          .sendMessage(
              player,
              messages()
                  .get(
                      "hof.assigned",
                      "season",
                      Integer.toString(season),
                      "cell",
                      ref.cell().id(),
                      "gallery",
                      ref.gallery().id()));
    }
  }

  // The decorating window

  /** Whether the season is being played, so its window can be open. */
  private static boolean playing(SeasonState state) {
    return state.phase() instanceof Phase.Active || state.phase() instanceof Phase.Countdown;
  }

  /** {@code /season decorate open}, or the window opening by itself. */
  public OpenResult open() {
    Optional<SeasonState> current = state.get();
    if (current.isEmpty() || !playing(current.get())) {
      return new OpenResult.Refused("decorate.refused");
    }
    if (current.get().decorating().open()) {
      return new OpenResult.Refused("decorate.already-open");
    }
    Instant now = clock.instant();
    decorating.accept(d -> d.opened(now));
    int season = current.get().season();
    int owners = assignAll(season);
    if (seasonGalleries(season).stream().anyMatch(this::claims)) {
      for (PlayerRecord record : withoutCell(season)) {
        if (platform.players().find(record.id()).isPresent()) {
          platform
              .players()
              .sendMessage(
                  record.id(),
                  messages().get("hof.claim.open", "season", Integer.toString(season)));
        }
      }
    }
    return new OpenResult.Opened(owners);
  }

  /** What opening the window came to. */
  public sealed interface OpenResult {
    /**
     * The window opened.
     *
     * @param owners how many players own a cell in the season's galleries now
     */
    record Opened(int owners) implements OpenResult {}

    record Refused(String messageKey) implements OpenResult {}
  }

  /** {@code /season decorate close}: the window closes and every cell locks, now. */
  public boolean close() {
    Optional<SeasonState> current = state.get();
    if (current.isEmpty()) {
      return false;
    }
    Decorating window = current.get().decorating();
    Instant now = clock.instant();
    boolean anyOpen =
        window.open() || window.lockAt().values().stream().anyMatch(at -> at.isAfter(now));
    if (!anyOpen) {
      return false;
    }
    decorating.accept(Decorating::closed);
    lockOwnersStandingInCells();
    return true;
  }

  /**
   * At the end of the countdown, before anyone is archived: the window closes and owners standing
   * in their cell switch to adventure mode first.
   */
  public CompletableFuture<Void> lockAll(int season) {
    SeasonsConfig settings = config.get().config();
    List<Gallery> galleries = seasonGalleries(season);
    boolean any =
        galleries.isEmpty()
            ? settings.decorating().lockAtEnd()
            : galleries.stream().anyMatch(g -> g.lockAtEnd(settings));
    if (!any) {
      return CompletableFuture.completedFuture(null);
    }
    // A gallery that doesn't lock at the end keeps the window open for its own cells.
    if (galleries.stream().allMatch(g -> g.lockAtEnd(settings))) {
      decorating.accept(Decorating::lockedAtEnd);
    }
    lockOwnersStandingInCells();
    if (!seasonGalleries(season).isEmpty()) {
      announcer.broadcast("hof.locked", "season", Integer.toString(season));
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * An owner archived at the end, or during the Intermission, gets decorating.afterArchive to
   * arrange their cell around their figure. Once only.
   */
  public void archived(PlayerId player, int season) {
    Optional<SeasonState> current = state.get();
    SeasonsConfig settings = config.get().config();
    Optional<CellRef> cell = cellOf(player, season);
    if (current.isEmpty()
        || current.get().season() != season
        || playing(current.get())
        || cell.isEmpty()
        || !cell.get().gallery().lockAtEnd(settings)
        || !cell.get().gallery().afterArchive(settings).isPositive()
        || current.get().decorating().lockAt().containsKey(player)) {
      return;
    }
    Duration afterArchive = cell.get().gallery().afterArchive(settings);
    Instant until = clock.instant().plus(afterArchive);
    decorating.accept(d -> d.withLockAt(player, until));
    platform
        .players()
        .sendMessage(
            player,
            messages()
                .get(
                    "decorate.after-archive",
                    "when",
                    HumanTime.duration(messages(), afterArchive)));
  }

  /**
   * Whether a player may take things out of a cell now: open its containers for real, pick up and
   * keep what they break. Only while decorating before the season ends; in the time after their
   * archive an owner arranges their cell but takes nothing into the next season.
   */
  public boolean canTakeOut(PlayerId player, CellRef ref) {
    return canDecorate(player, ref) && state.get().map(HallService::playing).orElse(false);
  }

  /** Whether a player may build in a cell now. */
  public boolean canDecorate(PlayerId player, CellRef ref) {
    Optional<SeasonState> found = state.get();
    if (found.isEmpty()) {
      return false;
    }
    SeasonState current = found.get();
    int season = ref.gallery().season();
    if (ref.gallery().kind() != Gallery.Kind.SEASON
        || season != current.season()
        || !ref.cell().owner(season).equals(Optional.of(player))) {
      return false;
    }
    Decorating window = current.decorating();
    if (playing(current)) {
      return window.open();
    }
    if (current.phase() instanceof Phase.Intermission
        || current.phase() instanceof Phase.Archiving) {
      return window.afterArchive(player, clock.instant())
          || (!ref.gallery().lockAtEnd(config.get().config()) && window.open());
    }
    return false;
  }

  /** Once a second: opens the window when it is due, and locks cells whose time ran out. */
  void tick() {
    Instant now = clock.instant();
    Instant since = lastTick;
    lastTick = now;
    Optional<SeasonState> found = state.get();
    if (found.isEmpty()) {
      return;
    }
    SeasonState current = found.get();
    Decorating window = current.decorating();
    if (playing(current)
        && !window.open()
        && window.openedAt().isEmpty()
        && current.plannedEnd().isPresent()) {
      Instant opens =
          current
              .plannedEnd()
              .get()
              .toInstant()
              .minus(config.get().config().decorating().opensBefore());
      if (!now.isBefore(opens)) {
        LOG.info("The decorating window for Season {} opens", current.season());
        open();
      }
    }
    for (Map.Entry<PlayerId, Instant> lock : window.lockAt().entrySet()) {
      Instant at = lock.getValue();
      if (at.isAfter(since) && !at.isAfter(now)) {
        PlayerId player = lock.getKey();
        if (platform.players().find(player).isPresent()) {
          platform.players().sendMessage(player, messages().get("decorate.locked"));
          lockIfInCell(player);
        }
      }
    }
  }

  private void lockOwnersStandingInCells() {
    for (PlayerRef online : platform.players().online()) {
      lockIfInCell(online.id());
    }
  }

  /** Switches a player standing in a cell they can no longer decorate to adventure. */
  private void lockIfInCell(PlayerId player) {
    Optional<CellRef> in = cellAt(platform.players().position(player));
    if (in.isPresent()
        && !canDecorate(player, in.get())
        && platform.players().gameMode(player) == GameMode.SURVIVAL
        && !platform.permissions().has(player, BYPASS)) {
      platform.players().setGameMode(player, GameMode.ADVENTURE);
      markSwitched(player, true);
    }
  }

  // Swaps

  /** What {@code /hof swap} came to, as a message key for the player who ran it. */
  public sealed interface SwapResult {
    record Refused(String messageKey) implements SwapResult {}

    record Asked() implements SwapResult {}

    /**
     * Both asked, so the cells changed hands.
     *
     * @param cell the asker's cell now
     */
    record Swapped(CellRef cell, CellRef theirs) implements SwapResult {}
  }

  /** {@code /hof swap}: both players must ask within {@link #SWAP_WINDOW}. */
  public SwapResult swap(PlayerId asker, PlayerId other) {
    Optional<SeasonState> found = state.get();
    if (found.isEmpty() || !playing(found.get()) || !found.get().decorating().open()) {
      return new SwapResult.Refused("hof.swap.closed");
    }
    if (asker.equals(other)) {
      return new SwapResult.Refused("hof.swap.self");
    }
    int season = found.get().season();
    Optional<CellRef> mine = cellOf(asker, season);
    Optional<CellRef> theirs = cellOf(other, season);
    if (mine.isEmpty()) {
      return new SwapResult.Refused("hof.swap.no-cell");
    }
    if (theirs.isEmpty()) {
      return new SwapResult.Refused("hof.swap.other-no-cell");
    }
    Instant now = clock.instant();
    SwapRequest waiting = swaps.get(other);
    if (waiting != null
        && waiting.with().equals(asker)
        && now.isBefore(waiting.at().plus(SWAP_WINDOW))) {
      swaps.remove(other);
      swaps.remove(asker);
      // assign() reads each gallery afresh, so both cells may share one.
      CellRef theirsNow = assign(mine.get(), season, Optional.of(other));
      CellRef mineNow = assign(theirs.get(), season, Optional.of(asker));
      return new SwapResult.Swapped(mineNow, theirsNow);
    }
    swaps.put(asker, new SwapRequest(other, now));
    return new SwapResult.Asked();
  }

  // Game modes and building

  /**
   * Twice a second: inside the Hall, owners decorating their own cell are in survival and everyone
   * else in adventure; leaving the Hall puts back survival for players Seasons switched from it,
   * and only for them. Creative and spectator players, and holders of the bypass permission, are
   * left alone.
   */
  void sweep() {
    Optional<SeasonState> current = state.get();
    for (PlayerRef online : platform.players().online()) {
      PlayerId player = online.id();
      if (current.isPresent()) {
        handOutLate(player, current.get());
      }
      GameMode mode = platform.players().gameMode(player);
      if (mode == GameMode.CREATIVE
          || mode == GameMode.SPECTATOR
          || platform.permissions().has(player, BYPASS)) {
        continue;
      }
      Position at = platform.players().position(player);
      if (!inHall(at)) {
        if (switched(player)) {
          forgetSwitched(player);
          if (mode == GameMode.ADVENTURE) {
            platform.players().setGameMode(player, GameMode.SURVIVAL);
          }
        }
        continue;
      }
      boolean decorating = cellAt(at).map(ref -> canDecorate(player, ref)).orElse(false);
      if (decorating) {
        // Only an owner Seasons put in adventure goes back to survival: anyone in adventure for
        // another reason stays in it.
        if (mode == GameMode.ADVENTURE && switched(player)) {
          platform.players().setGameMode(player, GameMode.SURVIVAL);
        }
      } else if (mode == GameMode.SURVIVAL) {
        platform.players().setGameMode(player, GameMode.ADVENTURE);
        markSwitched(player, true);
      }
    }
    switchedWithoutRecord.removeIf(player -> platform.players().find(player).isEmpty());
  }

  /**
   * Whether Seasons switched a player from survival to adventure in the Hall: by their record, or,
   * for a player switched before their record was made (a first join in the hub), in memory.
   */
  public boolean switched(PlayerId player) {
    return players.find(player).map(PlayerRecord::hallAdventure).orElse(false)
        || switchedWithoutRecord.contains(player);
  }

  /** Forgets that Seasons switched a player, once they have their game mode back. */
  public void forgetSwitched(PlayerId player) {
    markSwitched(player, false);
    switchedWithoutRecord.remove(player);
  }

  /** Remembers, in the player record so it survives a restart, whether Seasons switched them. */
  private void markSwitched(PlayerId player, boolean switched) {
    Optional<PlayerRecord> record = players.find(player);
    if (record.isPresent()) {
      if (record.get().hallAdventure() != switched) {
        players.put(record.get().withHallAdventure(switched));
      }
    } else if (switched) {
      switchedWithoutRecord.add(player);
    } else {
      switchedWithoutRecord.remove(player);
    }
  }

  /** A player who first plays while the window is open gets a cell straight away. */
  private void handOutLate(PlayerId player, SeasonState current) {
    if (playing(current)
        && current.decorating().open()
        && seasonGalleries(current.season()).stream().anyMatch(this::handsOut)
        && cellOf(player, current.season()).isEmpty()
        && players.find(player).map(r -> earnsCell(r, current.season())).orElse(false)) {
      ensureCell(player, current.season());
    }
  }

  /**
   * The Hall's say on an intent, if it has one: archive anchors and figures open the archive view,
   * plaques the season card. Everything else in the Hall is {@link HallRules}'.
   */
  public Optional<Decision> decide(Intent intent) {
    return switch (intent) {
      case Intent.UseBlock use -> useBlock(use);
      case Intent.UseDisplay display -> useDisplay(display);
      default -> Optional.empty();
    };
  }

  private Optional<Decision> useBlock(Intent.UseBlock use) {
    for (Gallery gallery : galleries()) {
      if (gallery.launch().filter(at -> sameBlock(at, use.block())).isPresent()) {
        if (use.mainHand()) {
          pressLaunch(use.player(), use.block());
        }
        // Whatever it is, Seasons decides what it does: no redstone signal.
        return Optional.of(new Decision.Deny(Optional.empty()));
      }
    }
    for (Gallery gallery : galleries()) {
      if (!gallery.contains(use.block()) && gallery.bounds().isPresent()) {
        continue;
      }
      for (Cell cell : gallery.cells()) {
        for (Anchor anchor : cell.anchors(AnchorType.PLAQUE)) {
          if (anchor.isAt(use.block())) {
            if (use.mainHand()) {
              openCard(use.player(), new CellRef(gallery, cell));
            }
            return Optional.of(new Decision.Deny(Optional.empty()));
          }
        }
        for (Anchor anchor : cell.anchors(AnchorType.ARCHIVE)) {
          if (anchor.isAt(use.block())) {
            if (use.mainHand()
                && !(use.sneaking() && curate(use.player(), new CellRef(gallery, cell)))) {
              openArchive(use.player(), new CellRef(gallery, cell), anchor.page().orElse(0));
            }
            return Optional.of(new Decision.Deny(Optional.empty()));
          }
        }
      }
    }
    return Optional.empty();
  }

  private static boolean sameBlock(Position a, Position b) {
    return a.dimension().equals(b.dimension())
        && a.blockX() == b.blockX()
        && a.blockY() == b.blockY()
        && a.blockZ() == b.blockZ();
  }

  /**
   * The launch button (questions 172): only for {@code seasons.admin.season}; during the
   * Intermission the first press asks, and a second within {@link #LAUNCH_CONFIRM} launches.
   * Otherwise it runs {@code /season launch}, which says why it can't.
   */
  private void pressLaunch(PlayerId player, Position at) {
    if (!platform.permissions().has(player, SeasonService.ADMIN_PERMISSION)) {
      audit.record(
          platform.players().knownName(player).orElse(player.toString()),
          HallAudit.BLOCKED,
          "hall",
          "denied",
          "launch at " + at.blockX() + ", " + at.blockY() + ", " + at.blockZ());
      platform.players().sendMessage(player, messages().get("hof.launch.admins-only"));
      return;
    }
    Optional<SeasonState> current = state.get();
    if (current.isEmpty() || !(current.get().phase() instanceof Phase.Intermission)) {
      launch.accept(player);
      return;
    }
    Instant now = clock.instant();
    Instant first = launchPresses.remove(player);
    if (first != null && now.isBefore(first.plus(LAUNCH_CONFIRM))) {
      launch.accept(player);
      return;
    }
    launchPresses.put(player, now);
    platform
        .players()
        .sendMessage(
            player,
            messages()
                .get("hof.launch.again", "season", Integer.toString(current.get().season() + 1)));
  }

  private Optional<Decision> useDisplay(Intent.UseDisplay use) {
    Optional<DisplayTag> tag = DisplayTag.parse(use.tag());
    if (tag.isPresent() && tag.get().kind().equals(DisplayTag.FIGURE)) {
      Optional<Gallery> gallery = gallery(tag.get().gallery());
      Optional<Cell> cell = gallery.flatMap(g -> g.cell(tag.get().cell()));
      if (cell.isPresent()) {
        CellRef ref = new CellRef(gallery.get(), cell.get());
        if (!(use.sneaking() && curate(use.player(), ref))) {
          openArchive(use.player(), ref, 0);
        }
      }
    }
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  /**
   * Hands a cell to the curator view instead of the archive view, for a curator who sneak-clicks
   * its archive anchor or figure.
   */
  public interface CuratorView {
    /** Whether a player may open the curator view. */
    boolean mayView(PlayerId player);

    /** Opens the curator view of one archive. */
    void openFor(PlayerId viewer, PlayerId owner, int season);
  }

  private @Nullable CuratorView curator;

  /** The curator view, once curation is ready; before any intent arrives. */
  public void bindCuration(CuratorView view) {
    this.curator = view;
  }

  /** Opens the curator view if the player may, and says whether it did. */
  private boolean curate(PlayerId player, CellRef ref) {
    CuratorView view = curator;
    if (view == null || !view.mayView(player)) {
      return false;
    }
    int season = ref.gallery().season();
    Optional<PlayerId> owner = ref.cell().owner(season);
    if (owner.isEmpty()) {
      platform.players().sendMessage(player, messages().get("hof.nothing-archived"));
      return true;
    }
    view.openFor(player, owner.get(), season);
    return true;
  }

  /** Opens the archive view of a cell's owner for its gallery's season, at a page. */
  public void openArchive(PlayerId viewer, CellRef ref, int page) {
    int season = ref.gallery().season();
    Optional<PlayerId> owner = ref.cell().owner(season);
    if (owner.isEmpty()) {
      platform.players().sendMessage(viewer, messages().get("hof.nothing-archived"));
      return;
    }
    var _ =
        writer
            .submit(() -> archives.read(owner.get(), season))
            .whenComplete((found, failure) -> {
              if (failure != null) {
                LOG.error("Can't read an archive for the archive view", failure);
              }
              Optional<ArchiveRecord> read = failure == null ? found : Optional.empty();
              platform.scheduler().runGlobal(() -> show(viewer, read, page));
            });
  }

  /** Opens the season card of a cell's owner for its gallery's season, as a book. */
  public void openCard(PlayerId viewer, CellRef ref) {
    int season = ref.gallery().season();
    Optional<PlayerId> owner = ref.cell().owner(season);
    if (owner.isEmpty()) {
      platform.players().sendMessage(viewer, messages().get("hof.nothing-archived"));
      return;
    }
    var _ =
        card(owner.get(), season)
            .whenComplete((card, failure) -> {
              if (failure != null) {
                LOG.error("Can't read an archive for the season card", failure);
              }
              Optional<SeasonCard> found = failure == null ? card : Optional.empty();
              platform
                  .scheduler()
                  .runGlobal(() -> {
                    if (platform.players().find(viewer).isEmpty()) {
                      return;
                    }
                    if (found.isEmpty()) {
                      platform
                          .players()
                          .sendMessage(viewer, messages().get("hof.nothing-archived"));
                      return;
                    }
                    platform
                        .players()
                        .openBook(
                            viewer,
                            found.get().title(),
                            messages().get("card.author"),
                            found.get().messages());
                  });
            });
  }

  /**
   * A player's season card, from their archive, their record and the season's firsts; empty if they
   * have no archive for that season.
   */
  public CompletableFuture<Optional<SeasonCard>> card(PlayerId player, int season) {
    return writer
        .submit(() -> archives.read(player, season))
        .thenApplyAsync(
            found ->
                found.map(archive ->
                    SeasonCard.of(
                        archive,
                        players.find(player),
                        state.get().map(s -> s.firsts(season)).orElse(List.of()).stream()
                            .filter(first -> first.player().equals(player))
                            .toList(),
                        state.get().flatMap(s -> s.awards(season)),
                        messages())),
            platform.scheduler()::runGlobal);
  }

  private void show(PlayerId viewer, Optional<ArchiveRecord> found, int page) {
    if (platform.players().find(viewer).isEmpty()) {
      return;
    }
    if (found.isEmpty()) {
      platform.players().sendMessage(viewer, messages().get("hof.nothing-archived"));
      return;
    }
    var view = ArchivePages.view(found.get(), messages());
    platform.menus().openPaged(viewer, view, Math.min(page, view.pages().size() - 1));
  }

  private CellDirectory directory() {
    return new CellDirectory() {
      @Override
      public Optional<Position> cell(PlayerId player, int season) {
        Optional<CellRef> cell = ensureCell(player, season).or(() -> unclaimed(player, season));
        Optional<Position> arrival = cell.flatMap(HallService.this::arrival);
        if (arrival.isEmpty()) {
          return arrival;
        }
        archived(player, season);
        if (config.get().config().archive().arrival() == SeasonsConfig.Arrival.HUB) {
          // Everyone wakes at the Hall spawn instead of their cell (questions 257).
          Optional<Position> spawn = hallSpawn();
          if (spawn.isPresent()) {
            return spawn;
          }
        }
        // In a bounds gallery an owner whose cell is locked waits outside it.
        CellRef ref = cell.get();
        return mayBeIn(player, ref) ? arrival : galleryEntry(ref.gallery()).or(() -> arrival);
      }
    };
  }

  private Messages messages() {
    return config.get().messages();
  }
}
