// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchivePages;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
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
  private final Path backups;
  private final SerialWriter writer;
  private final Clock clock;
  private final PlayerStore players;
  private final ArchiveFiles archives;
  private final Announcer announcer;
  private final RandomGenerator random;
  private final Map<PlayerId, SwapRequest> swaps = new HashMap<>();

  /** Players without a record yet (before /season init) whom Seasons switched to adventure. */
  private final Set<PlayerId> switchedWithoutRecord = new HashSet<>();

  private Supplier<Optional<SeasonState>> state = Optional::empty;
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
      RandomGenerator random) {
    this.platform = platform;
    this.config = config;
    this.galleryFile = galleryFile;
    this.backups = backups;
    this.writer = writer;
    this.clock = clock;
    this.players = players;
    this.archives = archives;
    this.announcer = announcer;
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

  /** Whether a position is in the Hall: inside any gallery's bounds. */
  public boolean inHall(Position position) {
    return galleries().stream().anyMatch(g -> g.contains(position));
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
          markSwitched(player, false);
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

  /** Whether Seasons switched a player from survival to adventure in the Hall. */
  private boolean switched(PlayerId player) {
    return players
        .find(player)
        .map(PlayerRecord::hallAdventure)
        .orElseGet(() -> switchedWithoutRecord.contains(player));
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
            if (use.mainHand()) {
              openArchive(use.player(), new CellRef(gallery, cell), anchor.page().orElse(0));
            }
            return Optional.of(new Decision.Deny(Optional.empty()));
          }
        }
      }
    }
    return Optional.empty();
  }

  private Optional<Decision> useDisplay(Intent.UseDisplay use) {
    Optional<DisplayTag> tag = DisplayTag.parse(use.tag());
    if (tag.isPresent() && tag.get().kind().equals(DisplayTag.FIGURE)) {
      Optional<Gallery> gallery = gallery(tag.get().gallery());
      Optional<Cell> cell = gallery.flatMap(g -> g.cell(tag.get().cell()));
      if (cell.isPresent()) {
        openArchive(use.player(), new CellRef(gallery.get(), cell.get()), 0);
      }
    }
    return Optional.of(new Decision.Deny(Optional.empty()));
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
        Optional<Position> arrival = ensureCell(player, season).flatMap(HallService.this::arrival);
        if (arrival.isPresent()) {
          archived(player, season);
        }
        return arrival;
      }
    };
  }

  private Messages messages() {
    return config.get().messages();
  }
}
