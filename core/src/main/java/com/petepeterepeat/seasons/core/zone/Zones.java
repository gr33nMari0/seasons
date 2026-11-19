// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.zone;

import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.hall.HallAudit;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Milestones;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.ZoneGuard;
import com.petepeterepeat.seasons.core.season.HumanTime;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonService;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.season.Transition;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The zones over the old world while a season is played: what each stops now, players kept out of
 * blocked zones, nothing changed across their edges, no building in no-build zones, entries
 * announced, unlock conditions, and Homecoming. A season's zones come from its season file and lock
 * at its launch; until Homecoming each is locked unless unlocked on its own, and after it each is
 * in its after-unlock mode unless locked again. Server thread only.
 */
public final class Zones implements ZoneGuard, Milestones {
  /** Exempt from every zone: entering, building, and anything across an edge. */
  public static final String BYPASS = "seasons.zone.bypass";

  /** A refused interaction. */
  static final String BLOCKED = "zone-blocked";

  /** An interaction allowed only by {@link #BYPASS}. */
  static final String BYPASSED = "zone-bypass";

  /** How often the sweep runs: each run checks a tenth of the players, so each every 10 ticks. */
  private static final Duration SWEEP = Duration.ofMillis(50);

  private static final int SWEEP_SLICES = 10;
  private static final Duration CONDITIONS = Duration.ofSeconds(1);
  private static final String THE_END = "minecraft:the_end";

  /** Entities that are building, not using: placing or breaking them is refused in no-build. */
  private static final Set<String> FIXTURES =
      Set.of("item_frame", "glow_item_frame", "painting", "armor_stand", "cushion");

  private final Platform platform;
  private final ConfigHolder config;
  private final Announcer announcer;
  private final HallAudit audit;
  private final AuditLog log;
  private final Clock clock;
  private final Predicate<Position> exempt;
  private final IntFunction<Optional<Position>> seasonSpawn;
  private final Predicate<PlayerId> held;
  private @Nullable SeasonService seasons;

  private @Nullable ConfigSnapshot builtFrom;
  private @Nullable SeasonState builtFor;
  private ZoneMap map = ZoneMap.EMPTY;

  /** Where each player last stood outside every blocked zone, as the sweep and moves saw them. */
  private final Map<PlayerId, Position> lastValid = new HashMap<>();

  /** Players Seasons is sending out of a zone right now. */
  private final Set<PlayerId> sending = new HashSet<>();

  /** The announce zone each player was last seen in, so staying inside isn't entering again. */
  private final Map<PlayerId, String> announcedIn = new HashMap<>();

  /** Which zones each player was announced entering, and on which day. */
  private final Map<PlayerId, Map<String, LocalDate>> announced = new HashMap<>();

  private long sweeps;
  private final List<ScheduledTask> tasks = new ArrayList<>();

  private final FrontierBorder borders;
  private final PlayerStore players;

  /** The zones the sweep last saw, so a change reaches every player's border at once. */
  private @Nullable ZoneMap swept;

  /**
   * @param exempt where no zone ever applies: the Hall
   * @param seasonSpawn the spawn of a season, if it has one
   * @param held players Seasons is archiving or catching up, whom the sweep leaves alone
   * @param players where each player's last valid position is kept between sessions
   */
  public Zones(
      Platform platform,
      ConfigHolder config,
      Announcer announcer,
      AuditLog log,
      Clock clock,
      Predicate<Position> exempt,
      IntFunction<Optional<Position>> seasonSpawn,
      Predicate<PlayerId> held,
      PlayerStore players) {
    this.players = players;
    this.platform = platform;
    this.config = config;
    this.announcer = announcer;
    this.audit = new HallAudit(log, clock);
    this.log = log;
    this.clock = clock;
    this.exempt = exempt;
    this.seasonSpawn = seasonSpawn;
    this.held = held;
    this.borders = new FrontierBorder(platform.players());
  }

  /** Connects the season service, once it exists. */
  public void bind(SeasonService seasons) {
    this.seasons = seasons;
  }

  public void start() {
    tasks.add(platform.scheduler().runGlobalRepeating(SWEEP, this::sweep));
    tasks.add(platform.scheduler().runGlobalRepeating(CONDITIONS, this::checkConditions));
    tasks.add(platform.scheduler().runGlobalRepeating(HallAudit.WINDOW, audit::flush));
  }

  public void stop() {
    tasks.forEach(ScheduledTask::cancel);
    tasks.clear();
    lastValid.forEach(this::remember);
    audit.flushAll();
    borders.clear();
  }

  // What is in force

  private Optional<SeasonState> state() {
    return seasons == null ? Optional.empty() : seasons.state();
  }

  /**
   * The zones in force now, rebuilt when the files or the season state have changed. Every reload
   * and every change to the state makes a new object, so identity is the cheap test intended.
   */
  @SuppressWarnings("ReferenceEquality")
  ZoneMap map() {
    ConfigSnapshot snapshot = config.get();
    SeasonState current = state().orElse(null);
    if (snapshot != builtFrom || current != builtFor) {
      builtFrom = snapshot;
      builtFor = current;
      map = build(snapshot, current);
    }
    return map;
  }

  private ZoneMap build(ConfigSnapshot snapshot, @Nullable SeasonState current) {
    if (current == null || !playing(current)) {
      return ZoneMap.EMPTY;
    }
    Optional<SeasonFile> file = snapshot.season(current.season());
    if (file.isEmpty() || file.get().zones().isEmpty()) {
      return ZoneMap.EMPTY;
    }
    List<ZoneMap.Live> live = new ArrayList<>();
    for (Zone zone : file.get().zones().values()) {
      boolean locked = locked(zone, current);
      live.add(new ZoneMap.Live(zone, locked ? zone.mode() : zone.afterUnlock(), locked));
    }
    return new ZoneMap(live, exempt);
  }

  /**
   * Whether a zone is locked: as unlocked or locked again on its own, if it was; else until
   * Homecoming. Conditions that come true are recorded as unlocking it on its own.
   */
  private static boolean locked(Zone zone, SeasonState state) {
    Boolean open = state.zones().unlocked().get(zone.id());
    if (open != null) {
      return !open;
    }
    return frontier(state);
  }

  /** Whether a season is being played: its zones are in force. */
  private static boolean playing(SeasonState state) {
    return state.phase() instanceof Phase.Active || state.phase() instanceof Phase.Countdown;
  }

  /** Whether the old world is still locked: before Homecoming. */
  private static boolean frontier(SeasonState state) {
    return switch (state.phase()) {
      case Phase.Active active -> active.stage() == Stage.FRONTIER;
      case Phase.Countdown countdown -> countdown.stageBefore() == Stage.FRONTIER;
      default -> false;
    };
  }

  /**
   * The season whose file zone commands edit: the season being played while its old world is
   * locked, so a zone drawn then locks at once; otherwise the next one, which is being prepared.
   */
  public OptionalInt editingSeason() {
    Optional<SeasonState> current = state();
    if (current.isEmpty()) {
      return OptionalInt.empty();
    }
    SeasonState s = current.get();
    return OptionalInt.of(playing(s) && frontier(s) ? s.season() : s.season() + 1);
  }

  /** Whether a season's zones are in force now. */
  public boolean live(int season) {
    return state().map(s -> s.season() == season && playing(s)).orElse(false);
  }

  /** What a zone of the live season stops now; off for any other. */
  public ZoneMode now(String id) {
    return map().find(id).map(ZoneMap.Live::now).orElse(ZoneMode.OFF);
  }

  /** A zone in force, with what it stops now. */
  public record InForce(Zone zone, ZoneMode now) {}

  /** Every zone in force now, by id, with what it stops: none before launch or between seasons. */
  public List<InForce> inForce() {
    return map().zones().stream()
        .map(live -> new InForce(live.zone(), live.now()))
        .sorted(java.util.Comparator.comparing(z -> z.zone().id()))
        .toList();
  }

  // The world's side

  @Override
  public boolean protects(Position block) {
    return map().at(block).filter(live -> live.now().protects()).isPresent();
  }

  @Override
  public boolean allows(List<Position> blocks) {
    String region = null;
    for (Position block : blocks) {
      String here =
          map().at(block).filter(live -> live.now().protects()).map(l -> l.zone().id()).orElse("");
      if (region != null && !region.equals(here)) {
        return false;
      }
      region = here;
    }
    return true;
  }

  @Override
  public boolean blocks(Position at) {
    return map().blocking(at).isPresent();
  }

  // Players

  /** The zones' say on a player's intent, if a zone has one. */
  public Optional<Decision> decide(Intent intent) {
    ZoneMap zones = map();
    if (zones.zones().isEmpty()) {
      return Optional.empty();
    }
    PlayerId player = intent.player();
    return switch (intent) {
      case Intent.Move move -> moved(player, move.from(), move.to());
      case Intent.Teleport teleport -> teleported(teleport);
      case Intent.CreatePortal portal -> portal(player, portal.at());
      case Intent.PlaceBlock place ->
          change(player, place.block(), "place", place.blockType(), true);
      case Intent.BreakBlock broken ->
          change(player, broken.block(), "break", block(broken.block()), true);
      case Intent.ChangeBlock change ->
          change(player, change.block(), "change", change.how(), true);
      case Intent.Bucket bucket -> change(player, bucket.block(), "empty", bucket.bucket(), true);
      case Intent.UseBlock use -> change(player, use.block(), "use", block(use.block()), false);
      case Intent.OpenContainer open ->
          // Either half of a double chest inside a zone keeps both closed.
          open.blocks().stream()
              .map(at -> change(player, at, "open", block(at), false))
              .flatMap(Optional::stream)
              .findFirst();
      case Intent.JoinContainers join ->
          join.blocks().stream()
              .map(at -> change(player, at, "join", block(at), true))
              .flatMap(Optional::stream)
              .findFirst();
      case Intent.UseEntity use -> change(player, use.at(), "use", use.type(), false);
      case Intent.PlaceEntity place -> change(player, place.at(), "place", place.type(), true);
      case Intent.HurtEntity hurt ->
          change(player, hurt.at(), "hit", hurt.type(), FIXTURES.contains(path(hurt.type())));
      case Intent.PickupItem pickup -> pickup(player, pickup.item());
      default -> Optional.empty();
    };
  }

  private Optional<Decision> moved(PlayerId player, Position from, Position to) {
    Optional<ZoneMap.Live> into = map().at(to);
    Optional<ZoneMap.Live> was = map().at(from);
    boolean entering =
        into.isPresent() && !into.get().zone().id().equals(was.map(l -> l.zone().id()).orElse(""));
    if (!entering || bypasses(player)) {
      return Optional.empty();
    }
    ZoneMap.Live zone = into.get();
    if (zone.now() == ZoneMode.BLOCK) {
      platform.players().stopMoving(player);
      refusedEntry(player, zone.zone(), "walk in", to);
      keptOut(player, zone.zone());
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (zone.now() == ZoneMode.ANNOUNCE) {
      entered(player, zone.zone());
    }
    return Optional.empty();
  }

  private Optional<Decision> teleported(Intent.Teleport teleport) {
    PlayerId player = teleport.player();
    if (teleport.kind() == Intent.TeleportKind.SEASONS || bypasses(player)) {
      return Optional.empty();
    }
    Optional<ZoneMap.Live> into = map().blocking(teleport.to());
    if (into.isEmpty()) {
      return Optional.empty();
    }
    String how = "teleport in by " + teleport.kind().name().toLowerCase(Locale.ROOT);
    refusedEntry(player, into.get().zone(), how, teleport.to());
    keptOut(player, into.get().zone());
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  private Optional<Decision> portal(PlayerId player, Position at) {
    Optional<ZoneMap.Live> into = map().blocking(at);
    if (into.isEmpty() || bypasses(player)) {
      return Optional.empty();
    }
    Zone zone = into.get().zone();
    refusedEntry(player, zone, "make a portal", at);
    return Optional.of(
        Decision.deny(message("zone.portal", "name", zone.displayName(), "when", until(zone))));
  }

  /**
   * Picking up an item lying in a blocked zone, as from just outside its edge, is refused quietly:
   * the game asks every tick while the player stands there.
   */
  private Optional<Decision> pickup(PlayerId player, Position item) {
    Optional<ZoneMap.Live> into = map().blocking(item);
    if (into.isEmpty() || bypasses(player)) {
      return Optional.empty();
    }
    audit.record(name(player), BLOCKED, into.get().zone().id(), "pick up", where(item));
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  /** A route into a blocked zone refused, in the audit log; repeats are counted. */
  private void refusedEntry(PlayerId player, Zone zone, String how, Position at) {
    audit.record(name(player), BLOCKED, zone.id(), how, where(at));
  }

  /**
   * Anything done to a position inside a zone: refused in a blocked zone, from outside its edge or
   * in, and refused in a no-build zone if it builds.
   *
   * @param builds whether it places, breaks or changes something, rather than using it
   */
  private Optional<Decision> change(
      PlayerId player, Position at, String verb, String what, boolean builds) {
    Optional<ZoneMap.Live> found = map().at(at);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    ZoneMap.Live live = found.get();
    boolean refused = live.now() == ZoneMode.BLOCK || (live.now() == ZoneMode.NO_BUILD && builds);
    if (!refused) {
      return Optional.empty();
    }
    Zone zone = live.zone();
    if (bypasses(player)) {
      audit.record(name(player), BYPASSED, zone.id(), verb + " " + what, where(at));
      return Optional.empty();
    }
    audit.record(name(player), BLOCKED, zone.id(), verb + " " + what, where(at));
    Message why =
        live.now() == ZoneMode.BLOCK
            ? message("zone.edge", "name", zone.displayName(), "when", until(zone))
            : message("zone.no-build", "name", zone.displayName());
    return Optional.of(Decision.deny(why));
  }

  /** Tells a player a route into a zone is refused: a title and the action bar. */
  private void keptOut(PlayerId player, Zone zone) {
    Message bar =
        zone.message()
            .map(text -> new Message(text, Map.of()))
            .orElseGet(
                () -> message("zone.blocked", "name", zone.displayName(), "when", until(zone)));
    platform.players().alert(player, message("zone.blocked-title"), bar);
  }

  // The sweep

  /**
   * Every 50 ms, a tenth of the players: anyone inside a blocked zone is put back where they last
   * stood outside every blocked zone, or at the season spawn if there is no such place; anyone
   * outside is remembered there; and entering an announce zone is announced. Each player checked is
   * sent the frontier border they should see, and everyone is when the zones in force change.
   */
  @SuppressWarnings("ReferenceEquality")
  void sweep() {
    long slice = sweeps++ % SWEEP_SLICES;
    Set<PlayerId> online = new HashSet<>();
    int index = 0;
    ZoneMap zones = map();
    boolean changed = zones != swept;
    swept = zones;
    boolean border = config.get().config().zones().frontierBorder();
    for (PlayerRef ref : platform.players().online()) {
      PlayerId player = ref.id();
      online.add(player);
      boolean due = index++ % SWEEP_SLICES == slice;
      if (due || changed) {
        borders.update(
            player, platform.players().position(player), zones, border, bypasses(player));
      }
      if (!due || sending.contains(player) || held.test(player)) {
        continue;
      }
      Position at = platform.players().position(player);
      Optional<ZoneMap.Live> here = zones.at(at);
      if (here.isPresent() && here.get().now() == ZoneMode.BLOCK && !bypasses(player)) {
        refusedEntry(player, here.get().zone(), "found inside", at);
        sendOut(player, here.get().zone());
        continue;
      }
      // A bypass holder inside a blocked zone isn't somewhere to send them back to.
      if (zones.blocking(at).isEmpty()) {
        lastValid.put(player, at);
      }
      if (here.isPresent() && here.get().now() == ZoneMode.ANNOUNCE) {
        entered(player, here.get().zone());
      } else {
        announcedIn.remove(player);
      }
    }
    lastValid.forEach((player, at) -> {
      if (!online.contains(player)) {
        remember(player, at);
      }
    });
    lastValid.keySet().retainAll(online);
    announcedIn.keySet().retainAll(online);
    sending.retainAll(online);
    borders.retain(online);
  }

  /**
   * Keeps where a player last stood outside every blocked zone in their record, as they leave and
   * as Seasons stops, so it outlasts the session (questions 177).
   */
  private void remember(PlayerId player, Position at) {
    players
        .find(player)
        .filter(record -> !record.lastValid().equals(Optional.of(at)))
        .ifPresent(record -> players.put(record.withLastValid(Optional.of(at))));
  }

  /**
   * Puts a player inside a blocked zone back where they last stood outside this session, or at the
   * spawn. One who logs in inside has no such place yet and goes to the spawn, as the spec says,
   * whatever their record keeps from an earlier session (questions 177).
   */
  private void sendOut(PlayerId player, Zone zone) {
    ZoneMap zones = map();
    Optional<Position> back =
        Optional.ofNullable(lastValid.get(player)).filter(at -> zones.blocking(at).isEmpty());
    Optional<Position> destination = back.or(this::spawn);
    if (destination.isEmpty()) {
      return;
    }
    sending.add(player);
    var _ =
        platform
            .players()
            .teleportSafely(player, destination.get())
            .whenCompleteAsync(
                (moved, failure) -> {
                  sending.remove(player);
                  if (failure != null || !Boolean.TRUE.equals(moved)) {
                    return;
                  }
                  if (back.isPresent()) {
                    keptOut(player, zone);
                  } else {
                    lastValid.put(player, destination.get());
                    platform
                        .players()
                        .sendMessage(
                            player, message("zone.sent-to-spawn", "name", zone.displayName()));
                  }
                },
                platform.scheduler()::runGlobal);
  }

  /**
   * A player who has just arrived in another dimension, as through a portal, is checked at once
   * rather than at the next sweep: the game picks a portal's exit only after asking whether the
   * trip may go, and may pick an old portal inside a blocked zone. They go back where they last
   * stood outside, which is on the other side of the portal.
   */
  @Override
  public void respawned(PlayerId player) {
    resendBorder(player);
  }

  /**
   * The game has just sent the client its world's border, on a change of dimension or a respawn:
   * the frontier's is sent again at once if it applies, rather than left to a sweep, which sees no
   * change and would never send it.
   */
  private void resendBorder(PlayerId player) {
    borders.forget(player);
    borders.update(
        player,
        platform.players().position(player),
        map(),
        config.get().config().zones().frontierBorder(),
        bypasses(player));
  }

  private void arrived(PlayerId player) {
    if (sending.contains(player) || held.test(player) || bypasses(player)) {
      return;
    }
    Position at = platform.players().position(player);
    Optional<ZoneMap.Live> here = map().blocking(at);
    if (here.isPresent()) {
      refusedEntry(player, here.get().zone(), "arrive by portal", at);
      sendOut(player, here.get().zone());
    }
  }

  private Optional<Position> spawn() {
    return state().flatMap(s -> seasonSpawn.apply(s.season()));
  }

  /** Entering an announce zone: broadcast once per player per zone per day. */
  private void entered(PlayerId player, Zone zone) {
    if (zone.id().equals(announcedIn.put(player, zone.id()))) {
      return;
    }
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.systemDefault()));
    Map<String, LocalDate> seen = announced.computeIfAbsent(player, _ -> new HashMap<>());
    if (today.equals(seen.get(zone.id()))) {
      return;
    }
    seen.put(zone.id(), today);
    String who = name(player);
    String when = opens(zone);
    announcer.broadcast("zone.announce", "player", who, "name", zone.displayName(), "when", when);
    if (config.get().config().zones().announceToDiscord()) {
      announcer.discord(
          "zone.announce-discord", "player", who, "name", zone.displayName(), "when", when);
    }
  }

  // Unlocking

  /** Once a second: zones whose conditions came true unlock, and so does Homecoming. */
  void checkConditions() {
    Optional<SeasonState> current = state();
    if (current.isEmpty() || !playing(current.get()) || !frontier(current.get())) {
      return;
    }
    SeasonState s = current.get();
    Optional<SeasonFile> file = config.get().season(s.season());
    if (file.isEmpty()) {
      return;
    }
    UnlockCondition.Facts facts = facts(s);
    if (file.get().homecoming().met(facts)) {
      homecoming(AuditLog.SEASONS);
      return;
    }
    for (Zone zone : file.get().zones().values()) {
      if (!s.zones().unlocked().containsKey(zone.id()) && zone.lockedUntil().met(facts)) {
        setUnlocked(zone, true);
        log.record(AuditLog.SEASONS, "zone-unlock", zone.id(), "ok", "its condition came true");
        announceOpened(zone);
      }
    }
  }

  private UnlockCondition.Facts facts(SeasonState s) {
    return new UnlockCondition.Facts(
        clock.instant(),
        s.launchedAt(),
        s.homecomingAt().isPresent(),
        s.zones().dragonKilled(),
        s.zones().reachedEnd().size());
  }

  /**
   * Homecoming, for everyone at once: every zone switches to its after-unlock mode in this tick, as
   * the state that decides them changes; once that state is on disk, a title and a sound for
   * everyone online, a broadcast, a Discord post and fireworks at the spawn.
   */
  public Transition homecoming(String actor) {
    SeasonService service = Objects.requireNonNull(seasons, "not bound");
    Transition transition = service.homecoming();
    if (transition instanceof Transition.Changed) {
      log.record(
          actor,
          "homecoming",
          "season " + state().map(SeasonState::season).orElse(0),
          "ok",
          "every zone to its after-unlock mode");
      service.afterSave(this::celebrate);
    }
    return transition;
  }

  private void celebrate() {
    Message title = message("homecoming.title");
    Message subtitle = message("homecoming.subtitle");
    for (PlayerRef player : platform.players().online()) {
      platform.players().celebrate(player.id(), title, subtitle);
    }
    spawn().ifPresent(at -> platform.players().fireworks(at));
    announcer.broadcast("homecoming.broadcast");
    announcer.discord("homecoming.discord");
  }

  /** What {@code /season unlock <zone>} and {@code /season lock <zone>} found. */
  public enum Switch {
    DONE,
    ALREADY,
    UNKNOWN,
    NOT_LIVE
  }

  /** Unlocks or locks one zone of the live season on its own, whatever its condition says. */
  public Switch set(String id, boolean open, String actor) {
    Optional<ZoneMap.Live> live = map().find(id);
    if (live.isEmpty()) {
      return editingZone(id).isPresent() ? Switch.NOT_LIVE : Switch.UNKNOWN;
    }
    if (live.get().locked() != open) {
      return Switch.ALREADY;
    }
    setUnlocked(live.get().zone(), open);
    log.record(actor, open ? "zone-unlock" : "zone-lock", id, "ok", "by command");
    if (open) {
      announceOpened(live.get().zone());
    }
    return Switch.DONE;
  }

  /** A zone in the file zone commands edit, if it has one by that id. */
  public Optional<Zone> editingZone(String id) {
    OptionalInt season = editingSeason();
    if (season.isEmpty()) {
      return Optional.empty();
    }
    return config
        .get()
        .season(season.getAsInt())
        .flatMap(file -> Optional.ofNullable(file.zones().get(id)));
  }

  private void setUnlocked(Zone zone, boolean open) {
    Objects.requireNonNull(seasons, "not bound").updateZones(z -> z.with(zone.id(), open));
  }

  private void announceOpened(Zone zone) {
    announcer.broadcast("zone.opened", "name", zone.displayName());
    announcer.discord("zone.opened-discord", "name", zone.displayName());
  }

  // Milestones

  @Override
  public void advancementDone(PlayerId player, String advancement, String title) {}

  @Override
  public void enteredDimension(PlayerId player, String dimension) {
    resendBorder(player);
    arrived(player);
    if (!dimension.equals(THE_END) || seasons == null) {
      return;
    }
    Optional<SeasonState> current = state();
    if (current.isPresent()
        && playing(current.get())
        && !current.get().zones().reachedEnd().contains(player)) {
      seasons.updateZones(z -> z.withReachedEnd(player));
    }
  }

  @Override
  public void dragonKilled() {
    Optional<SeasonState> current = state();
    if (seasons != null
        && current.isPresent()
        && playing(current.get())
        && !current.get().zones().dragonKilled()) {
      seasons.updateZones(z -> z.withDragonKilled());
    }
  }

  // Text

  /** Until when a zone is locked, as players read it: "Homecoming", or a date. */
  public String until(Zone zone) {
    return when(zone, "zone.until.");
  }

  /** When a zone opens, as players read it: "at Homecoming", or "on" a date. */
  public String opens(Zone zone) {
    return when(zone, "zone.opens.");
  }

  private String when(Zone zone, String prefix) {
    Messages messages = config.get().messages();
    UnlockCondition condition = zone.lockedUntil();
    Optional<ZonedDateTime> date =
        condition instanceof UnlockCondition.After after
            ? state()
                .flatMap(SeasonState::launchedAt)
                .map(at -> at.plus(after.after()).atZone(ZoneId.systemDefault()))
            : condition.firstDate();
    return switch (condition) {
      case UnlockCondition.Homecoming h -> messages.text(prefix + "homecoming");
      case UnlockCondition.Manual m -> messages.text(prefix + "manual");
      default ->
          date.map(d -> messages.text(prefix + "date", "time", HumanTime.time(d)))
              .orElseGet(() -> messages.text(prefix + "later"));
    };
  }

  // Helpers

  private boolean bypasses(PlayerId player) {
    return platform.permissions().has(player, BYPASS);
  }

  private String block(Position at) {
    return platform.world().block(at).type();
  }

  private String name(PlayerId player) {
    return platform.players().knownName(player).orElse(player.toString());
  }

  private static String where(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
  }

  /** A namespaced id without its namespace: {@code minecraft:painting} is {@code painting}. */
  private static String path(String id) {
    int colon = id.indexOf(':');
    return colon < 0 ? id : id.substring(colon + 1);
  }

  private Message message(String key, String... placeholders) {
    return config.get().messages().get(key, placeholders);
  }
}
