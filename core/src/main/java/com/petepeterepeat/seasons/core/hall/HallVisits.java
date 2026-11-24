// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.guard.Immunity;
import com.petepeterepeat.seasons.core.log.DebugArea;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerRecord.HallReturn;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.season.HumanTime;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Visiting the Hall of Fame: {@code /hof} and sneak-clicking a bed take a player from a safe spot
 * next to a bed to the Hall, and {@code /hof leave}, an exit anchor or leaving the Hall's bounds by
 * any route takes them back to where they left, or the nearest safe place if that has changed.
 * Inside, nothing can be dropped or picked up, pearls and chorus fruit don't work, and the only
 * thing to sit on is a cushion. Everything here runs on the server thread.
 */
public final class HallVisits {
  /** Permission for {@code /hof} and {@code /hof leave}. */
  public static final String PERMISSION_VISIT = "seasons.hof.visit";

  /** Skips every {@code /hof} entry check but the rate limit; ops by default (questions 262). */
  public static final String PERMISSION_ANYWHERE = "seasons.hof.anywhere";

  /** Never held in the Hall, the Intermission included; ops by default (questions 263). */
  public static final String PERMISSION_ROAM = "seasons.hof.roam";

  private static final Logger LOG = SeasonsLog.get();
  private static final Duration SWEEP = Duration.ofMillis(500);
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String ENDER_PEARL = "minecraft:ender_pearl";
  private static final String CHORUS_FRUIT = "minecraft:chorus_fruit";

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService halls;
  private final HallAudit audit;
  private final PlayerStore players;
  private final Immunity immunity;
  private final Clock clock;
  private final IntFunction<Optional<Position>> seasonSpawn;
  private final Executor serverThread;
  private Supplier<Optional<SeasonState>> state = Optional::empty;

  /** Until when a position is locked to a player, if a zone blocks it now (questions 249). */
  private BiFunction<PlayerId, Position, Optional<String>> locked = (p, at) -> Optional.empty();

  /** Whether a player is held for archiving, when no link may take them anywhere. */
  private Predicate<PlayerId> held = player -> false;

  /** The link each player last stood on, so standing on one takes it once. */
  private final Map<PlayerId, String> standingOnLink = new HashMap<>();

  /**
   * How many more sweeps each player who just arrived by one of Seasons' moves is held off step and
   * area triggers, for links.arrivalGrace (M8). Counted in sweeps, real time, not Seasons' clock,
   * which a test can hold still.
   */
  private final Map<PlayerId, Integer> arrivalGrace = new HashMap<>();

  private @Nullable LinkService links;

  /** How far from where they last stood a player may appear in the Hall and still have walked. */
  private static final double STEP = 4;

  private final Map<PlayerId, Instant> lastAttempt = new HashMap<>();
  private final Map<PlayerId, Instant> lastVisit = new HashMap<>();
  private final Map<PlayerId, Instant> lastDamage = new HashMap<>();

  /** Saved returns of players with no record yet; kept in memory only. */
  private final Map<PlayerId, HallReturn> returnsWithoutRecord = new HashMap<>();

  /** Players Seasons is moving into or out of the Hall right now. */
  private final Set<PlayerId> moving = new HashSet<>();

  /** Players the sweep last saw inside the Hall. */
  private final Set<PlayerId> inside = new HashSet<>();

  /** Online players the sweep has seen at least once since they joined. */
  private final Set<PlayerId> known = new HashSet<>();

  /** Where each player outside the Hall last stood, as the sweep saw them. */
  private final Map<PlayerId, Position> lastOutside = new HashMap<>();

  private @Nullable ScheduledTask sweeper;

  /**
   * @param seasonSpawn the spawn of a season, if it has one
   */
  public HallVisits(
      Platform platform,
      ConfigHolder config,
      HallService halls,
      HallAudit audit,
      PlayerStore players,
      Immunity immunity,
      Clock clock,
      IntFunction<Optional<Position>> seasonSpawn) {
    this.platform = platform;
    this.config = config;
    this.halls = halls;
    this.audit = audit;
    this.players = players;
    this.immunity = immunity;
    this.clock = clock;
    this.seasonSpawn = seasonSpawn;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** Connects the season state, once the season service exists. */
  public void bind(Supplier<Optional<SeasonState>> state) {
    this.state = state;
  }

  /**
   * Connects the archive hold. Clicks reach a held player's links only through the intent router,
   * which refuses them, but standing on a link is noticed by the sweep, so it asks here too.
   */
  public void bindHold(Predicate<PlayerId> held) {
    this.held = held;
  }

  /** Connects the link rules (M8). */
  public void bindLinks(LinkService links) {
    this.links = links;
  }

  private LinkService links() {
    return Objects.requireNonNull(links, "links are bound at startup");
  }

  /** Connects the zones, which say where a link may not go. */
  public void bindZones(BiFunction<PlayerId, Position, Optional<String>> locked) {
    this.locked = locked;
  }

  /** Starts the sweep that notices players entering and leaving the Hall. */
  public void start() {
    sweeper = platform.scheduler().runGlobalRepeating(SWEEP, this::sweep);
  }

  public void stop() {
    if (sweeper != null) {
      sweeper.cancel();
    }
  }

  /** A player took damage, for the "no damage in the last 10 s" rule. */
  public void damaged(PlayerId player) {
    lastDamage.put(player, clock.instant());
  }

  // Entry

  /**
   * {@code /hof}, {@code /hof <season>} or {@code /hof <player>}, or sneak-clicking a bed.
   *
   * @param target a season number or a player's name; empty for the Hall entry
   */
  public void visit(PlayerId player, Optional<String> target) {
    if (state.get().isEmpty()) {
      tell(player, "hof.visit.not-yet");
      return;
    }
    if (!attempt(player) || moving.contains(player)) {
      return;
    }
    Optional<Position> destination = destination(player, target);
    if (destination.isEmpty()) {
      return;
    }
    enter(player, destination.get(), true, true);
  }

  /** Counts an attempt at /hof or a link; false, telling them, within the rate limit. */
  private boolean attempt(PlayerId player) {
    Duration limit = config.get().config().hof().entry().rateLimit();
    Instant now = clock.instant();
    Instant attempted = lastAttempt.get(player);
    if (attempted != null && now.isBefore(attempted.plus(limit))) {
      tell(player, "hof.entry.rate-limit");
      return false;
    }
    lastAttempt.put(player, now);
    return true;
  }

  /**
   * Takes a player into the Hall, or across it if they are already inside, after the entry checks.
   *
   * @param needsBed whether the bed check applies: not for a link (questions 249)
   * @param save whether where they stand is saved for /hof leave
   */
  private void enter(PlayerId player, Position destination, boolean needsBed, boolean save) {
    SeasonsConfig.Hof.Entry rules = config.get().config().hof().entry();
    Instant now = clock.instant();
    Position at = platform.players().position(player);
    if (halls.inHall(at)) {
      // Already inside: just a walk across the Hall, so no checks and the saved spot stays.
      moveTo(player, destination, "hof.visit.arrived");
      return;
    }
    // Admins skip every check but the rate limit (questions 262).
    boolean anywhere = platform.permissions().has(player, PERMISSION_ANYWHERE);
    Instant visited = lastVisit.get(player);
    if (!anywhere
        && rules.cooldown().isPositive()
        && visited != null
        && now.isBefore(visited.plus(rules.cooldown()))) {
      Duration left = Duration.between(now, visited.plus(rules.cooldown()));
      tell(player, "hof.entry.cooldown", "time", HumanTime.duration(messages(), left));
      return;
    }
    Optional<EntryChecks.Failure> failed =
        EntryChecks.check(
            rules,
            new EntryChecks.Situation(
                at,
                platform.players().movement(player),
                blocks(),
                platform.players().bed(player),
                platform
                    .entities()
                    .hostiles(
                        at,
                        EntryChecks.MONSTER_REACH,
                        EntryChecks.MONSTER_HEIGHT,
                        EntryChecks.MONSTER_REACH),
                Optional.ofNullable(lastDamage.get(player)),
                now),
            needsBed);
    if (!anywhere && failed.isPresent()) {
      tell(
          player,
          "hof.entry." + failed.get().key(),
          failed.get().placeholders().toArray(String[]::new));
      return;
    }
    // A spot that return safety would turn down would send them to their bed or the spawn on the
    // way back: a free teleport home. So it is turned down now, by the same rules.
    int radius = config.get().config().hof().returnSafety().hostileRadius();
    Optional<String> unsafe = ReturnSafety.unsafe(at, blocks(), hostiles(at, radius), radius);
    if (!anywhere && save && unsafe.isPresent()) {
      tell(player, "hof.entry.unsafe", "reason", messages().text("hof.unsafe." + unsafe.get()));
      return;
    }
    Optional<HallReturn> back =
        save ? Optional.of(new HallReturn(at, modeOutside(player))) : Optional.empty();
    moving.add(player);
    var _ =
        saveReturn(player, back)
            .whenCompleteAsync(
                (saved, failure) -> {
                  moving.remove(player);
                  if (failure != null) {
                    LOG.error("Couldn't save where {} left from, so they stay", player, failure);
                    tell(player, "hof.visit.failed");
                    return;
                  }
                  lastVisit.put(player, clock.instant());
                  moveTo(
                      player,
                      destination,
                      "hof.visit.arrived",
                      // They never left: a saved spot outside the Hall would be a free teleport.
                      () -> {
                        var _ = saveReturn(player, Optional.empty());
                      });
                },
                serverThread);
  }

  /**
   * Into the Hall through a link area: the entry checks but the bed and movement, no saved spot.
   */
  private void enterFlying(PlayerId player, Position destination) {
    SeasonsConfig.Hof.Entry rules = config.get().config().hof().entry();
    SeasonsConfig.Hof.Entry flying =
        new SeasonsConfig.Hof.Entry(
            false,
            rules.bedDistance(),
            rules.anyBed(),
            rules.noHostiles(),
            rules.noDamageFor(),
            false,
            rules.noHazards(),
            rules.cooldown(),
            rules.rateLimit());
    Position at = platform.players().position(player);
    if (halls.inHall(at)) {
      moveTo(player, destination, "hof.visit.arrived");
      return;
    }
    boolean anywhere = platform.permissions().has(player, PERMISSION_ANYWHERE);
    Optional<EntryChecks.Failure> failed =
        EntryChecks.check(
            flying,
            new EntryChecks.Situation(
                at,
                platform.players().movement(player),
                blocks(),
                platform.players().bed(player),
                platform
                    .entities()
                    .hostiles(
                        at,
                        EntryChecks.MONSTER_REACH,
                        EntryChecks.MONSTER_HEIGHT,
                        EntryChecks.MONSTER_REACH),
                Optional.ofNullable(lastDamage.get(player)),
                clock.instant()),
            false);
    if (!anywhere && failed.isPresent()) {
      tell(
          player,
          "hof.entry." + failed.get().key(),
          failed.get().placeholders().toArray(String[]::new));
      return;
    }
    lastVisit.put(player, clock.instant());
    moveTo(player, destination, "hof.visit.arrived");
  }

  /** Where {@code /hof} with this target lands, telling the player if nowhere. */
  private Optional<Position> destination(PlayerId player, Optional<String> target) {
    if (target.isEmpty()) {
      Optional<Position> spawn = hallEntry();
      if (spawn.isEmpty()) {
        tell(player, "hof.visit.no-spawn");
      }
      return spawn;
    }
    String text = target.get();
    if (text.chars().allMatch(Character::isDigit) && !text.isEmpty() && text.length() < 9) {
      int season = Integer.parseInt(text);
      List<Gallery> visitable = halls.visitableGalleries(season);
      Optional<Gallery> withEntry =
          visitable.stream().filter(g -> g.entry().isPresent()).findFirst();
      if (withEntry.isPresent()) {
        return halls.galleryEntry(withEntry.get());
      }
      if (visitable.isEmpty()) {
        tell(player, "hof.visit.no-season", "season", text);
      } else {
        tell(player, "hof.visit.no-entry", "season", text);
        if (platform.permissions().has(player, HallCommands.PERMISSION_ADMIN)) {
          tell(player, "hof.visit.no-entry-next", "gallery", visitable.getFirst().id());
        }
      }
      return Optional.empty();
    }
    Optional<PlayerId> owner =
        platform
            .players()
            .findByName(text)
            .map(PlayerRef::id)
            .or(() ->
                players.all().stream()
                    .filter(r -> r.lastName().equalsIgnoreCase(text))
                    .map(PlayerRecord::id)
                    .findFirst());
    if (owner.isEmpty()) {
      tell(player, "hof.unknown-player", "name", text);
      return Optional.empty();
    }
    Optional<HallService.CellRef> cell =
        halls.galleries().stream()
            .filter(g -> g.kind() == Gallery.Kind.SEASON)
            .sorted(Comparator.comparingInt(Gallery::season).reversed())
            .flatMap(g -> g.cellOf(owner.get()).map(c -> new HallService.CellRef(g, c)).stream())
            .findFirst();
    Optional<Position> arrival = cell.flatMap(halls::arrival);
    if (arrival.isEmpty()) {
      tell(player, "hof.visit.no-cell", "name", text);
      return arrival;
    }
    // A bounds cell's entry anchor is inside it, where visitors may not go (questions 168).
    if (cell.get().gallery().access(config.get().config()) == SeasonsConfig.CellAccess.BOUNDS) {
      return halls.galleryEntry(cell.get().gallery()).or(() -> arrival);
    }
    return arrival;
  }

  /** {@code /hof tp player <name>}, or a season: as {@code /hof} would land, as an admin goes. */
  public void adminGoTo(PlayerId player, String target) {
    destination(player, Optional.of(target)).ifPresent(to -> adminGo(player, to));
  }

  /** A season's recorded spawn, if it has one. */
  public Optional<Position> seasonSpawn(int season) {
    return seasonSpawn.apply(season);
  }

  /** The Hall spawn, where plain {@code /hof} lands (questions 244). */
  public Optional<Position> hallEntry() {
    return halls.hallSpawn();
  }

  // Links

  /**
   * A player clicked or stepped on a link (questions 248 and 249): to the Hall, as {@code /hof}
   * without the bed; elsewhere, out of the Hall if they are in it, unless the land is locked. A
   * destination inside the Hall (a gallery, a point or a link there) is a way in, as the Hall spawn
   * is (M8).
   */
  public void takeLink(PlayerId player, HallLayout.Link link) {
    takeLink(player, link, false);
  }

  /**
   * @param throughArea passed through the link's area rather than clicking or standing on its
   *     block: no movement checks, so an elytra flight can take it, and no spot saved, since it may
   *     be in the air (questions 279)
   */
  private void takeLink(PlayerId player, HallLayout.Link link, boolean throughArea) {
    if (held.test(player) || moving.contains(player) || !attempt(player)) {
      return;
    }
    follow(player, link, throughArea, LinkChecks.ENTRY);
  }

  /**
   * Which checks a link applies (M8, {@code /hof link send --checks}): {@code NONE} keeps only
   * locked land and the Intermission; {@code ENTRY} adds the link's access and /hof's checks but
   * the bed on the way into the Hall; {@code FULL} adds the bed.
   */
  public enum LinkChecks {
    NONE,
    ENTRY,
    FULL
  }

  /**
   * Takes a link once its rate limit is counted: its access, then where it leads.
   *
   * @param checks which checks apply ({@code /hof link send --checks})
   */
  public void follow(
      PlayerId player, HallLayout.Link link, boolean throughArea, LinkChecks checks) {
    if (moving.contains(player) || held.test(player)) {
      return;
    }
    Optional<String> refused =
        checks == LinkChecks.NONE ? Optional.empty() : links().refused(player, link);
    if (refused.isPresent()) {
      tell(player, refused.get());
      return;
    }
    if (link.to().isEmpty()) {
      tell(player, "hof.link.nowhere");
      return;
    }
    switch (links().resolve(link.to().get())) {
      case LinkService.Resolved.Nowhere(String key, List<String> placeholders) ->
          tell(player, key, placeholders.toArray(String[]::new));
      case LinkService.Resolved.Back() -> {
        if (halls.inHall(platform.players().position(player))) {
          leave(player);
        } else {
          tell(player, "hof.link.no-return");
        }
      }
      case LinkService.Resolved.Hall() -> {
        if (state.get().isEmpty()) {
          tell(player, "hof.visit.not-yet");
          return;
        }
        Optional<Position> spawn = hallEntry();
        if (spawn.isEmpty()) {
          tell(player, "hof.visit.no-spawn");
          return;
        }
        into(player, spawn.get(), throughArea, checks);
      }
      case LinkService.Resolved.There(Position to) -> {
        if (halls.inHall(to)) {
          if (state.get().isEmpty()) {
            tell(player, "hof.visit.not-yet");
            return;
          }
          into(player, to, throughArea, checks);
        } else {
          away(player, to);
        }
      }
    }
  }

  /** Into the Hall by a link, as {@code /hof} without the bed (questions 249). */
  private void into(PlayerId player, Position to, boolean throughArea, LinkChecks checks) {
    if (checks == LinkChecks.NONE) {
      adminGo(player, to);
    } else if (throughArea) {
      enterFlying(player, to);
    } else {
      enter(
          player,
          to,
          checks == LinkChecks.FULL,
          config.get().config().hof().links().returnToLink());
    }
  }

  /** A link that leads out into the world: refused into locked land and between seasons. */
  private void away(PlayerId player, Position to) {
    if (state.get().map(s -> !playing(s)).orElse(false) && !roams(player)) {
      tell(player, "hof.link.between-seasons");
      return;
    }
    Optional<String> until = locked.apply(player, to);
    if (until.isPresent()) {
      tell(player, "hof.link.locked", "until", until.get());
      return;
    }
    returnTo(player, new HallReturn(to, modeOutside(player)), "hof.link.arrived");
  }

  /**
   * The game mode a player leaving the Hall goes back to: survival if Seasons switched them to
   * adventure in it, else the mode they are in.
   */
  private GameMode modeOutside(PlayerId player) {
    GameMode mode = platform.players().gameMode(player);
    return mode == GameMode.ADVENTURE && halls.switched(player) ? GameMode.SURVIVAL : mode;
  }

  /**
   * Gives a player who just left the Hall their game mode back now, rather than at the next sweep,
   * so a quick way back in doesn't remember adventure as the mode to go back to.
   */
  private void restoreMode(PlayerId player) {
    GameMode back = modeOutside(player);
    if (platform.players().gameMode(player) != back) {
      platform.players().setGameMode(player, back);
    }
    halls.forgetSwitched(player);
  }

  /**
   * Right-clicking a link's block takes it, unless the block only fires underfoot ({@code step},
   * M8); Seasons takes over the click either way, so a button gives no signal.
   */
  private Optional<Decision> linkClick(Intent.UseBlock use) {
    Optional<LinkService.Indexed> found = links().blockAt(use.block());
    if (found.isEmpty()) {
      return none();
    }
    if (found.get().trigger() instanceof HallLayout.Trigger.OnBlock on && !on.kind().clicked()) {
      // A plate clicked: nothing to take, and nothing of the plate's own to stop.
      return none();
    }
    if (use.mainHand()) {
      takeLink(use.player(), found.get().link());
    }
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  /** The link whose trigger a player standing at {@code at} is in, as standing counts. */
  private Optional<HallLayout.Link> linkUnder(Position at) {
    return links().standingIn(at).map(LinkService.Indexed::link);
  }

  /** Every exit that is an area: the hub's and the galleries' (questions 279). */
  private List<Bounds> exitAreas() {
    List<Bounds> areas = new ArrayList<>(halls.layout().areas().exits());
    for (Gallery gallery : halls.galleries()) {
      areas.addAll(gallery.areas().exits());
    }
    return areas;
  }

  /**
   * A move through an exit or link area (questions 279): worked out on the line from where it
   * starts to where it ends, at the feet and at the head, so a fast flight can't skip a plane one
   * block thick. Fires once until the player is out of the area again.
   */
  private void crossings(PlayerId player, Position from, Position to) {
    if (moving.contains(player) || held.test(player)) {
      return;
    }
    if (!bypasses(player) && (halls.inHall(from) || halls.inHall(to))) {
      for (Bounds area : exitAreas()) {
        if (!area.contains(from) && crosses(area, from, to)) {
          later(() ->
              savedReturn(player).ifPresent(back -> returnTo(player, back, "hof.leave.returned")));
          return;
        }
      }
    }
    for (LinkService.Indexed found : links().areas()) {
      HallLayout.Link link = found.link();
      Bounds box = ((HallLayout.Trigger.Area) found.trigger()).box();
      if (!box.contains(from)
          && crosses(box, from, to)
          && !link.id().equals(standingOnLink.get(player))) {
        if (inGrace(player)) {
          return;
        }
        standingOnLink.put(player, link.id());
        later(() -> takeLink(player, link, true));
        return;
      }
    }
  }

  /** Whether the line from {@code from} to {@code to}, at the feet or the head, passes a box. */
  static boolean crosses(Bounds box, Position from, Position to) {
    if (!box.dimension().equals(from.dimension()) || !box.dimension().equals(to.dimension())) {
      return false;
    }
    for (double lift : new double[] {0.1, 1.5}) {
      if (segment(box, from.x(), from.y() + lift, from.z(), to.x(), to.y() + lift, to.z())) {
        return true;
      }
    }
    return false;
  }

  private static boolean segment(
      Bounds box, double x0, double y0, double z0, double x1, double y1, double z1) {
    double[] start = {x0, y0, z0};
    double[] delta = {x1 - x0, y1 - y0, z1 - z0};
    double[] min = {box.minX(), box.minY(), box.minZ()};
    double[] max = {box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1};
    double enter = 0;
    double leave = 1;
    for (int i = 0; i < 3; i++) {
      if (Math.abs(delta[i]) < 1e-9) {
        if (start[i] < min[i] || start[i] > max[i]) {
          return false;
        }
        continue;
      }
      double t1 = (min[i] - start[i]) / delta[i];
      double t2 = (max[i] - start[i]) / delta[i];
      enter = Math.max(enter, Math.min(t1, t2));
      leave = Math.min(leave, Math.max(t1, t2));
      if (enter > leave) {
        return false;
      }
    }
    return true;
  }

  /**
   * An admin's own teleport (questions 271): there, with no entry checks, through the floor search
   * every Seasons teleport makes, with arrival immunity. Into the Hall from outside saves the spot
   * as {@code /hof} does; out of it drops the saved spot and gives back the game mode. Out of the
   * Hall during the Intermission only for roamers, and never into land locked for them.
   */
  public void adminGo(PlayerId player, Position to) {
    if (moving.contains(player)) {
      return;
    }
    Position at = platform.players().position(player);
    boolean fromIn = halls.inHall(at);
    if (halls.inHall(to)) {
      if (fromIn) {
        moveTo(player, to, "hof.tp.arrived");
        return;
      }
      Optional<HallReturn> back = Optional.of(new HallReturn(at, modeOutside(player)));
      moving.add(player);
      var _ =
          saveReturn(player, back)
              .whenCompleteAsync(
                  (saved, failure) -> {
                    moving.remove(player);
                    if (failure != null) {
                      tell(player, "hof.visit.failed");
                      return;
                    }
                    moveTo(
                        player,
                        to,
                        "hof.tp.arrived",
                        () -> {
                          var _ = saveReturn(player, Optional.empty());
                        });
                  },
                  serverThread);
      return;
    }
    if (fromIn && state.get().map(s -> !playing(s)).orElse(false) && !roams(player)) {
      tell(player, "hof.tp.between-seasons");
      return;
    }
    Optional<String> until = locked.apply(player, to);
    if (until.isPresent()) {
      tell(player, "hof.link.locked", "until", until.get());
      return;
    }
    moving.add(player);
    var _ =
        platform
            .players()
            .teleportSafely(player, to)
            .whenCompleteAsync(
                (moved, failure) -> {
                  moving.remove(player);
                  if (failure != null || !Boolean.TRUE.equals(moved)) {
                    tell(player, "hof.visit.failed");
                    return;
                  }
                  if (fromIn) {
                    inside.remove(player);
                    restoreMode(player);
                    forgetReturn(player);
                  }
                  arrived(player);
                  immunity.grant(player, config.get().config().hof().arrivalImmunity());
                  tell(player, "hof.tp.arrived");
                },
                serverThread);
  }

  /** Moves a player within or into the Hall, with arrival immunity. */
  private void moveTo(PlayerId player, Position destination, String messageKey) {
    moveTo(player, destination, messageKey, () -> {});
  }

  /**
   * @param failed run if the teleport didn't happen
   */
  private void moveTo(PlayerId player, Position destination, String messageKey, Runnable failed) {
    moving.add(player);
    var _ =
        platform
            .players()
            .teleportSafely(player, destination)
            .whenCompleteAsync(
                (moved, failure) -> {
                  moving.remove(player);
                  if (failure != null || !Boolean.TRUE.equals(moved)) {
                    failed.run();
                    tell(player, "hof.visit.failed");
                    return;
                  }
                  inside.add(player);
                  lastOutside.remove(player);
                  arrived(player);
                  immunity.grant(player, config.get().config().hof().arrivalImmunity());
                  tell(player, messageKey);
                },
                serverThread);
  }

  // Return

  /**
   * {@code /hof leave}. Only from inside the Hall: a saved spot left over outside it, after a
   * failed teleport or a crash, would otherwise be a teleport from anywhere.
   */
  public void leave(PlayerId player) {
    if (moving.contains(player)) {
      return;
    }
    if (!halls.inHall(platform.players().position(player))) {
      tell(player, "hof.leave.not-visiting");
      return;
    }
    Optional<HallReturn> back = savedReturn(player);
    if (back.isPresent()) {
      returnTo(player, back.get(), "hof.leave.returned");
      return;
    }
    if (state.get().map(s -> !playing(s)).orElse(false) && !roams(player)) {
      tell(player, "hof.leave.to-entry");
      return;
    }
    if (config.get().config().hof().leaveWithoutSpot()
        == SeasonsConfig.LeaveWithoutSpot.SEASON_SPAWN) {
      // Arrived with no spot of their own, such as by a respawn at a Hall spawn that is the world
      // spawn: the season's spawn (questions 246).
      Position spawn = spawn();
      if (!halls.inHall(spawn)) {
        returnTo(player, new HallReturn(spawn, modeOutside(player)), "hof.leave.to-spawn");
        return;
      }
    }
    tell(player, "hof.leave.no-return");
  }

  /**
   * Sends a player back to their saved spot if it is safe, or else down the fallbacks: their bed,
   * the nearest safe spot, the season spawn. Then their game mode is restored, they are immune for
   * a moment and the saved spot is forgotten.
   *
   * @param messageKey what they hear when the saved spot was safe
   */
  private void returnTo(PlayerId player, HallReturn back, String messageKey) {
    moving.add(player);
    Position saved = back.at();
    List<CompletableFuture<Void>> chunks = new ArrayList<>();
    for (int dx : new int[] {-ReturnSafety.NEARBY, ReturnSafety.NEARBY}) {
      for (int dz : new int[] {-ReturnSafety.NEARBY, ReturnSafety.NEARBY}) {
        chunks.add(
            platform
                .world()
                .loadChunk(
                    Position.of(saved.dimension(), saved.x() + dx, saved.y(), saved.z() + dz)));
      }
    }
    var _ =
        CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new))
            .handleAsync(
                (loaded, failure) -> {
                  if (platform.players().find(player).isEmpty()) {
                    moving.remove(player);
                    return null;
                  }
                  Destination where = safeDestination(player, back, messageKey);
                  send(player, back, where);
                  return null;
                },
                serverThread);
  }

  /** Where a returning player ends up and what they hear. */
  private record Destination(Position at, Message message) {}

  private Destination safeDestination(PlayerId player, HallReturn back, String messageKey) {
    SeasonsConfig.Hof.ReturnSafety rules = config.get().config().hof().returnSafety();
    int radius = rules.hostileRadius();
    Function<Position, BlockInfo> blocks = blocks();
    Position saved = back.at();
    Optional<String> unsafe = ReturnSafety.unsafe(saved, blocks, hostiles(saved, radius), radius);
    if (unsafe.isEmpty()) {
      return new Destination(saved, messages().get(messageKey));
    }
    String reason = messages().text("hof.unsafe." + unsafe.get());
    for (SeasonsConfig.ReturnFallback fallback : rules.fallbacks()) {
      Optional<Position> found =
          switch (fallback) {
            case BED ->
                platform
                    .players()
                    .respawnPoint(player)
                    .filter(bed ->
                        ReturnSafety.unsafe(bed, blocks, hostiles(bed, radius), radius).isEmpty());
            case NEARBY ->
                ReturnSafety.nearest(
                    saved, blocks, hostiles(saved, radius + ReturnSafety.NEARBY), radius);
            case SPAWN -> Optional.of(spawn());
          };
      if (found.isPresent()) {
        String key = "hof.leave." + fallback.name().toLowerCase(Locale.ROOT);
        return new Destination(found.get(), messages().get(key, "reason", reason));
      }
    }
    // Every fallback was turned off or failed: the season spawn is always there.
    return new Destination(spawn(), messages().get("hof.leave.spawn", "reason", reason));
  }

  private Position spawn() {
    return state
        .get()
        .flatMap(s -> seasonSpawn.apply(s.season()))
        .orElseGet(() -> platform.world().spawn(OVERWORLD));
  }

  private void send(PlayerId player, HallReturn back, Destination where) {
    var _ =
        platform
            .players()
            .teleportSafely(player, where.at())
            .whenCompleteAsync(
                (moved, failure) -> {
                  moving.remove(player);
                  if (failure != null || !Boolean.TRUE.equals(moved)) {
                    tell(player, "hof.leave.failed");
                    return;
                  }
                  inside.remove(player);
                  arrived(player);
                  immunity.grant(player, config.get().config().hof().arrivalImmunity());
                  if (platform.players().gameMode(player) != back.mode()) {
                    platform.players().setGameMode(player, back.mode());
                  }
                  forgetReturn(player);
                  platform.players().sendMessage(player, where.message());
                },
                serverThread);
  }

  /** Left the Hall's bounds by some route: back to the saved spot, or the Hall entry. */
  private void leftHall(PlayerId player) {
    inside.remove(player);
    if (moving.contains(player)) {
      return;
    }
    if (bypasses(player)) {
      // They may leave as they like, so the saved spot is done with.
      dropStaleReturn(player);
      return;
    }
    if (!kept() || roams(player)) {
      if (halls.inHall(platform.players().position(player))) {
        // Back in already, such as by a teleport straight after: nothing to let go of.
        inside.add(player);
        return;
      }
      // They just leave (questions 245): the saved spot is done with, and the sweep gives back
      // survival if Seasons put them in adventure.
      dropStaleReturn(player);
      restoreMode(player);
      tell(player, "hof.leave.walked-out");
      return;
    }
    Optional<HallReturn> back = savedReturn(player);
    if (back.isPresent()) {
      returnTo(player, back.get(), "hof.leave.left-hall");
      return;
    }
    if (state.get().map(s -> !playing(s)).orElse(false)) {
      // Between seasons there is no saved spot: everyone stays in the Hall.
      Optional<Position> entry = hallEntry();
      if (entry.isPresent()) {
        moveTo(player, entry.get(), "hof.leave.to-entry");
      }
    }
  }

  /**
   * Whether leaving the Hall now brings a player back, by {@code hof.sealed} (questions 245):
   * always, never, or only during the Intermission and the archiving before it.
   */
  private boolean kept() {
    boolean between = state.get().map(s -> !playing(s)).orElse(false);
    return switch (config.get().config().hof().sealed()) {
      case ALWAYS -> true;
      case NEVER -> false;
      case INTERMISSION -> between;
    };
  }

  // Sealing

  /** The visits' say on an intent, if they have one. */
  public Optional<Decision> decide(Intent intent) {
    PlayerId player = intent.player();
    return switch (intent) {
      case Intent.UseBlock use -> linkClick(use).or(() -> bedClick(use));
      case Intent.DropItem drop ->
          sealed(player) ? Optional.of(Decision.deny(message("hof.sealed.no-drop"))) : none();
      case Intent.PickupItem pickup ->
          sealed(player) && !decoratingHere(player)
              ? Optional.of(new Decision.Deny(Optional.empty()))
              : none();
      case Intent.UseItem use ->
          use.item().equals(ENDER_PEARL) && sealed(player)
              ? Optional.of(Decision.deny(message("hof.sealed.no-pearl")))
              : none();
      case Intent.Consume eat ->
          eat.item().equals(CHORUS_FRUIT) && sealed(player)
              ? Optional.of(Decision.deny(message("hof.sealed.no-pearl")))
              : none();
      case Intent.Mount mount ->
          !mount.cushion() && sealed(player)
              ? Optional.of(Decision.deny(message("hof.sealed.no-riding")))
              : none();
      case Intent.Teleport teleport -> teleported(teleport);
      case Intent.Move move -> moved(move);
      default -> none();
    };
  }

  private Optional<Decision> bedClick(Intent.UseBlock use) {
    if (!use.bed() || !use.sneaking() || !use.emptyHand()) {
      return none();
    }
    // The off hand's click comes too; both are taken over so the bed isn't slept in.
    if (use.mainHand() && platform.permissions().has(use.player(), PERMISSION_VISIT)) {
      visit(use.player(), Optional.empty());
    }
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  private Optional<Decision> teleported(Intent.Teleport teleport) {
    PlayerId player = teleport.player();
    boolean fromIn = halls.inHall(teleport.from());
    boolean toIn = halls.inHall(teleport.to());
    if (teleport.kind() == Intent.TeleportKind.SEASONS) {
      // Seasons' own moves, such as launch, never count as leaving.
      if (!toIn) {
        inside.remove(player);
      }
      return none();
    }
    Optional<Decision> closed = closedCell(player, teleport.from(), teleport.to());
    if (closed.isPresent()) {
      return closed;
    }
    if ((!fromIn && !toIn) || bypasses(player)) {
      return none();
    }
    if (teleport.kind() == Intent.TeleportKind.PEARL
        || teleport.kind() == Intent.TeleportKind.CHORUS) {
      if (fromIn && !toIn && kept()) {
        later(() -> leftHall(player));
      }
      return Optional.of(Decision.deny(message("hof.sealed.no-pearl")));
    }
    if (fromIn && !toIn) {
      inside.remove(player);
      later(() -> leftHall(player));
    } else if (!fromIn) {
      enteredOtherwise(player, teleport.from());
    }
    return none();
  }

  private Optional<Decision> moved(Intent.Move move) {
    PlayerId player = move.player();
    crossings(player, move.from(), move.to());
    Optional<Decision> closed = closedCell(player, move.from(), move.to());
    if (closed.isPresent()) {
      return closed;
    }
    boolean fromIn = halls.inHall(move.from());
    boolean toIn = halls.inHall(move.to());
    if (fromIn == toIn || bypasses(player)) {
      return none();
    }
    if (fromIn) {
      inside.remove(player);
      later(() -> leftHall(player));
      return none();
    }
    if (platform.players().movement(player).riding()) {
      return Optional.of(Decision.deny(message("hof.sealed.no-riding")));
    }
    enteredOtherwise(player, move.from());
    return none();
  }

  /**
   * Going into a {@code bounds} cell the player may not be in, from outside it, by any route but
   * Seasons' own: refused and audited (questions 168).
   */
  private Optional<Decision> closedCell(PlayerId player, Position from, Position to) {
    Optional<HallService.CellRef> into = halls.cellAt(to);
    if (into.isEmpty() || into.get().cell().contains(from) || halls.mayBeIn(player, into.get())) {
      return none();
    }
    audit.record(
        platform.players().knownName(player).orElse(player.toString()),
        HallAudit.BLOCKED,
        into.get().key(),
        "enter",
        to.blockX() + ", " + to.blockY() + ", " + to.blockZ());
    return Optional.of(Decision.deny(message("hof.cell.closed")));
  }

  /**
   * Came into the Hall without {@code /hof}, such as walking in after Homecoming: where they came
   * from is where leaving takes them back to. A saved spot they already have is kept: stepping out
   * and straight back must not swap it for a spot just outside the Hall.
   */
  private void enteredOtherwise(PlayerId player, Position from) {
    inside.add(player);
    if (moving.contains(player)
        || savedReturn(player).isPresent()
        || state.get().map(s -> !playing(s)).orElse(true)) {
      return;
    }
    var _ = saveReturn(player, Optional.of(new HallReturn(from, modeOutside(player))));
  }

  /**
   * Twice a second: notices players who came into the Hall or left it by a route no event reported,
   * and players standing on an exit anchor.
   */
  void sweep() {
    Set<PlayerId> online = new HashSet<>();
    for (PlayerRef ref : platform.players().online()) {
      PlayerId player = ref.id();
      online.add(player);
      Position at = platform.players().position(player);
      boolean in = halls.inHall(at);
      if (!in) {
        lastOutside.put(player, at);
      }
      if (moving.contains(player)) {
        continue;
      }
      if (known.add(player) && !in) {
        // First seen outside the Hall: a saved spot now is left over, such as from a crash between
        // the teleport back and forgetting it.
        dropStaleReturn(player);
      }
      boolean was = inside.contains(player);
      if (in && !was) {
        inside.add(player);
        Position from = lastOutside.remove(player);
        // Appearing from further than a step away with no move or teleport reported is a respawn
        // or a join: nothing to go back to (questions 247).
        if (from != null && !bypasses(player)) {
          if (platform.players().movement(player).riding()) {
            // Ridden in, which no move event reports: back out the way they came.
            moveOut(player, from);
          } else if (near(from, at)) {
            enteredOtherwise(player, from);
          }
        }
      } else if (!in && was) {
        leftHall(player);
      } else if (in && onExit(at) && !bypasses(player)) {
        savedReturn(player).ifPresent(back -> returnTo(player, back, "hof.leave.returned"));
      } else if (in && config.get().config().cells().moveOut()) {
        moveOutOfClosedCell(player, at);
      }
      steppedOnLink(player, at);
    }
    standingOnLink.keySet().retainAll(online);
    arrivalGrace.keySet().retainAll(online);
    arrivalGrace.replaceAll((p, left) -> left - 1);
    arrivalGrace.values().removeIf(left -> left <= 0);
    inside.retainAll(online);
    known.retainAll(online);
    lastOutside.keySet().retainAll(online);
    moving.retainAll(online);
  }

  /**
   * A player found in a {@code bounds} cell they may not be in, such as an owner whose time ran
   * out, goes to the gallery's entry.
   */
  private void moveOutOfClosedCell(PlayerId player, Position at) {
    Optional<HallService.CellRef> in = halls.cellAt(at);
    if (in.isEmpty() || halls.mayBeIn(player, in.get())) {
      return;
    }
    halls
        .galleryEntry(in.get().gallery())
        .ifPresent(entry -> moveTo(player, entry, "hof.cell.moved-out"));
  }

  /** Puts a player who rode into the Hall back where they stood outside it, off their mount. */
  private void moveOut(PlayerId player, Position from) {
    moving.add(player);
    var _ =
        platform
            .players()
            .teleportSafely(player, from)
            .whenCompleteAsync(
                (moved, failure) -> {
                  moving.remove(player);
                  inside.remove(player);
                  tell(player, "hof.sealed.no-riding");
                },
                serverThread);
  }

  /**
   * After one of Seasons' own moves: a link the player landed on counts as already stood on, so
   * {@code /hof leave} back to the pressure plate they came by doesn't send them in again.
   */
  private void arrived(PlayerId player) {
    long sweeps =
        Math.ceilDiv(config.get().config().links().arrivalGrace().toMillis(), SWEEP.toMillis());
    if (sweeps > 0) {
      arrivalGrace.put(player, (int) sweeps);
    }
    linkUnder(platform.players().position(player))
        .ifPresentOrElse(
            link -> standingOnLink.put(player, link.id()), () -> standingOnLink.remove(player));
  }

  /** Takes the link a player has just stepped onto, once until they step off it. */
  private void steppedOnLink(PlayerId player, Position at) {
    Optional<HallLayout.Link> link = moving.contains(player) ? Optional.empty() : linkUnder(at);
    if (link.isEmpty()) {
      standingOnLink.remove(player);
      return;
    }
    if (inGrace(player)) {
      // Not marked as stood on either: once the grace is over, the link fires if they stay.
      return;
    }
    if (!link.get().id().equals(standingOnLink.put(player, link.get().id()))) {
      takeLink(player, link.get());
    }
  }

  /** Whether a player arrived too recently for step and area triggers to fire (M8). */
  private boolean inGrace(PlayerId player) {
    return arrivalGrace.containsKey(player);
  }

  private static boolean near(Position from, Position at) {
    if (!from.dimension().equals(at.dimension())) {
      return false;
    }
    double dx = from.x() - at.x();
    double dy = from.y() - at.y();
    double dz = from.z() - at.z();
    return dx * dx + dy * dy + dz * dz <= STEP * STEP;
  }

  /** Whether a player stands on an exit anchor, or on the block above one. */
  private boolean onExit(Position at) {
    List<Position> exits = new ArrayList<>(halls.layout().exits());
    for (Gallery gallery : halls.galleries()) {
      exits.addAll(gallery.exits());
    }
    if (exitAreas().stream().anyMatch(area -> area.contains(at))) {
      return true;
    }
    for (Position exit : exits) {
      if (exit.dimension().equals(at.dimension())
          && exit.blockX() == at.blockX()
          && exit.blockZ() == at.blockZ()
          && (exit.blockY() == at.blockY() || exit.blockY() == at.blockY() - 1)) {
        return true;
      }
    }
    return false;
  }

  // Helpers

  private boolean sealed(PlayerId player) {
    return halls.inHall(platform.players().position(player)) && !bypasses(player);
  }

  /**
   * An owner decorating the cell they stand in before the season ends picks up what they broke
   * there; after their archive nothing is picked up, so nothing leaves with them.
   */
  private boolean decoratingHere(PlayerId player) {
    return halls
        .cellAt(platform.players().position(player))
        .filter(ref -> halls.canTakeOut(player, ref))
        .isPresent();
  }

  /** Whether a player is never held in the Hall (questions 263). */
  private boolean roams(PlayerId player) {
    return platform.permissions().has(player, PERMISSION_ROAM);
  }

  private boolean bypasses(PlayerId player) {
    return platform.permissions().has(player, HallService.BYPASS);
  }

  private static boolean playing(SeasonState state) {
    return state.phase() instanceof Phase.Active || state.phase() instanceof Phase.Countdown;
  }

  /** Where a player goes back to from the Hall, if anywhere. */
  public Optional<HallReturn> savedReturn(PlayerId player) {
    Optional<PlayerRecord> record = players.find(player);
    return record.isPresent()
        ? record.get().hallReturn()
        : Optional.ofNullable(returnsWithoutRecord.get(player));
  }

  private CompletableFuture<Void> saveReturn(PlayerId player, Optional<HallReturn> back) {
    Optional<PlayerRecord> record = players.find(player);
    if (record.isEmpty()) {
      back.ifPresentOrElse(
          b -> returnsWithoutRecord.put(player, b), () -> returnsWithoutRecord.remove(player));
      return CompletableFuture.completedFuture(null);
    }
    // Written now, not coalesced: the saved spot must survive a crash right after the teleport.
    return players.putNow(record.get().withHallReturn(back));
  }

  /** Forgets the saved spot, and that Seasons switched them to adventure in the Hall. */
  private void forgetReturn(PlayerId player) {
    returnsWithoutRecord.remove(player);
    halls.forgetSwitched(player);
    Optional<PlayerRecord> record = players.find(player);
    if (record.isPresent()) {
      var _ =
          players.putNow(record.get().withHallReturn(Optional.empty()).withHallAdventure(false));
    }
  }

  /** Drops a saved spot of a player outside the Hall, where it could only be a free teleport. */
  private void dropStaleReturn(PlayerId player) {
    if (savedReturn(player).isPresent()) {
      SeasonsLog.debug(
          DebugArea.HALL, "Dropping {}'s saved Hall return: they are outside the Hall", player);
      var _ = saveReturn(player, Optional.empty());
    }
  }

  private Function<Position, BlockInfo> blocks() {
    return platform.world()::block;
  }

  private List<Position> hostiles(Position around, int radius) {
    return platform.entities().hostiles(around, radius, radius, radius);
  }

  private void later(Runnable task) {
    platform.scheduler().runGlobal(task);
  }

  private static Optional<Decision> none() {
    return Optional.empty();
  }

  private Message message(String key) {
    return messages().get(key);
  }

  private void tell(PlayerId player, String key, String... placeholders) {
    platform.players().sendMessage(player, messages().get(key, placeholders));
  }

  private Messages messages() {
    return config.get().messages();
  }
}
