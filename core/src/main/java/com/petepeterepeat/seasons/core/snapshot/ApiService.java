// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.petepeterepeat.seasons.api.ArchiveView;
import com.petepeterepeat.seasons.api.AuditEntry;
import com.petepeterepeat.seasons.api.GalleryView;
import com.petepeterepeat.seasons.api.Leaderboards;
import com.petepeterepeat.seasons.api.MuseumView;
import com.petepeterepeat.seasons.api.OnlinePlayer;
import com.petepeterepeat.seasons.api.PendingPlayer;
import com.petepeterepeat.seasons.api.PlayerName;
import com.petepeterepeat.seasons.api.PlayerProfile;
import com.petepeterepeat.seasons.api.SeasonSummary;
import com.petepeterepeat.seasons.api.SeasonView;
import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.api.WaypointView;
import com.petepeterepeat.seasons.api.event.SeasonsEvent;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchivePages;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ArchiveSlot;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.hall.Cell;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.MuseumFiles;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.map.Waypoint;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Presence;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.season.DragonState;
import com.petepeterepeat.seasons.core.season.SeasonAwards;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.zone.Zone;
import com.petepeterepeat.seasons.core.zone.Zones;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Seasons API's snapshots and views. The server thread refreshes the season every second and who's
 * online every {@code api.refresh} and at every join and leave, into atomic references; profiles,
 * galleries, archives and leaderboards are read from records and archives on a worker, hopping to
 * the server thread only to read online players' statistics. Nothing here waits on the server
 * thread or changes the world.
 */
public final class ApiService {
  private static final Logger LOG = SeasonsLog.get();

  /** How often the season is looked at, for its view and for events. */
  public static final Duration TICK = Duration.ofSeconds(1);

  /** How long a season's leaderboards are reused before they are worked out again. */
  static final Duration BOARDS_FOR = Duration.ofSeconds(5);

  private final Platform platform;
  private final ConfigHolder config;
  private final Clock clock;
  private final PlayerStore players;
  private final ArchiveFiles archives;
  private final Supplier<Optional<SeasonState>> stateOnServer;
  private final Supplier<List<Zones.InForce>> zonesOnServer;
  private final Executor worker;
  private final ApiEvents events;
  private final MuseumFiles museum;
  private final AuditLog audit;
  private final SerialWriter writer;

  // Written on the server thread, read anywhere.
  private volatile Optional<SeasonState> state = Optional.empty();
  private volatile Optional<SeasonView> season = Optional.empty();
  private volatile List<OnlinePlayer> online = List.of();
  private final Map<Integer, Cached> boards = new ConcurrentHashMap<>();

  private @Nullable ScheduledTask ticker;
  private Instant nextRefresh = Instant.MIN;
  private boolean refreshSoon;
  private Optional<SeasonState> seen = Optional.empty();

  private record Cached(Instant at, CompletableFuture<Leaderboards> boards) {}

  /**
   * Makes the service; {@link #start} takes the first snapshots.
   *
   * @param stateOnServer the season state, read on the server thread
   * @param zonesOnServer the zones in force, read on the server thread
   * @param worker reads records and archives
   * @param museum the museum's trophies, read on the writer thread as the museum writes them
   * @param audit the audit log, read on the writer thread after what it has appended
   */
  public ApiService(
      Platform platform,
      ConfigHolder config,
      Clock clock,
      PlayerStore players,
      ArchiveFiles archives,
      Supplier<Optional<SeasonState>> stateOnServer,
      Supplier<List<Zones.InForce>> zonesOnServer,
      Executor worker,
      ApiEvents events,
      MuseumFiles museum,
      AuditLog audit,
      SerialWriter writer) {
    this.platform = platform;
    this.config = config;
    this.clock = clock;
    this.players = players;
    this.archives = archives;
    this.stateOnServer = stateOnServer;
    this.zonesOnServer = zonesOnServer;
    this.worker = worker;
    this.events = events;
    this.museum = museum;
    this.audit = audit;
    this.writer = writer;
  }

  /** Takes the first snapshots and starts refreshing them. Server thread. */
  public void start() {
    seen = stateOnServer.get();
    tick();
    ticker = platform.scheduler().runGlobalRepeating(TICK, this::tick);
  }

  public void stop() {
    if (ticker != null) {
      ticker.cancel();
      ticker = null;
    }
    events.clear();
  }

  /** Every second on the server thread: the season, its events, and who's online when due. */
  void tick() {
    Optional<SeasonState> now = stateOnServer.get();
    state = now;
    season =
        now.map(s ->
            SeasonViews.of(
                s,
                zonesOnServer.get(),
                config.get().season(s.season()).map(SeasonFile::homecoming)));
    changes(seen, now, clock.instant()).forEach(events::publish);
    seen = now;
    Instant at = clock.instant();
    if (refreshSoon || !at.isBefore(nextRefresh)) {
      refreshSoon = false;
      nextRefresh = at.plus(config.get().config().api().refresh());
      refreshOnline();
    }
  }

  /** Who's online, from each player's presence now. Server thread. */
  void refreshOnline() {
    Duration afk = config.get().config().api().afkAfter();
    Map<String, List<Waypoint>> waypoints = config.get().waypoints().sets();
    List<Zone> zones = zonesOnServer.get().stream().map(Zones.InForce::zone).toList();
    List<OnlinePlayer> list = new ArrayList<>();
    for (PlayerRef player : platform.players().online()) {
      Optional<Presence> presence = platform.players().presence(player.id());
      if (presence.isEmpty() || presence.get().vanished()) {
        continue;
      }
      Presence p = presence.get();
      list.add(
          new OnlinePlayer(
              player.id().uuid(),
              player.name(),
              platform.players().skin(player.id()).flatMap(SkinUrl::of),
              p.at().dimension(),
              Regions.of(p.at(), p.biome(), waypoints, zones),
              p.idle().compareTo(afk) >= 0));
    }
    list.sort(java.util.Comparator.comparing(OnlinePlayer::name, String.CASE_INSENSITIVE_ORDER));
    online = List.copyOf(list);
  }

  /**
   * The events between two looks at the season state. A season recorded since the last look (none
   * before, one now) counts as everything in it being new: /season init, end and launch can all
   * land between two looks. At startup the first look is the state already there, so nothing is
   * announced then.
   */
  static List<SeasonsEvent> changes(
      Optional<SeasonState> before, Optional<SeasonState> after, Instant at) {
    if (after.isEmpty()) {
      return List.of();
    }
    SeasonState is = after.get();
    // No season before: a different season from this one, with nothing in it.
    boolean sameSeason = before.map(was -> was.season() == is.season()).orElse(false);
    List<SeasonsEvent> out = new ArrayList<>();
    Optional<String> wasPhase = before.map(was -> SeasonViews.phase(was.phase()));
    String isPhase = SeasonViews.phase(is.phase());
    Optional<String> wasStage =
        before.flatMap(was -> SeasonViews.stage(was.phase())).map(SeasonViews::text);
    Optional<String> isStage = SeasonViews.stage(is.phase()).map(SeasonViews::text);
    if (!sameSeason || !wasPhase.equals(Optional.of(isPhase)) || !wasStage.equals(isStage)) {
      out.add(new SeasonsEvent.PhaseChanged(at, is.season(), isPhase, isStage));
    }
    if (is.homecomingAt().isPresent()
        && (!sameSeason || before.flatMap(SeasonState::homecomingAt).isEmpty())) {
      out.add(new SeasonsEvent.HomecomingStarted(is.homecomingAt().get(), is.season()));
    }
    Set<String> known = new HashSet<>();
    for (SeasonFirst first : before.map(SeasonState::firsts).orElse(List.of())) {
      known.add(first.season() + " " + first.key());
    }
    for (SeasonFirst first : is.firsts()) {
      if (!known.contains(first.season() + " " + first.key())) {
        out.add(
            new SeasonsEvent.SeasonFirst(
                first.at(),
                first.season(),
                first.key(),
                first.label(),
                new PlayerName(first.player().uuid(), first.name())));
      }
    }
    Optional<DragonState.Kill> kill = is.dragon().kill();
    if (kill.isPresent() && (!sameSeason || before.flatMap(was -> was.dragon().kill()).isEmpty())) {
      DragonState.Kill k = kill.get();
      out.add(
          new SeasonsEvent.DragonSlain(
              k.at(), is.season(), k.slayer().map(p -> new PlayerName(p.uuid(), k.name()))));
    }
    return out;
  }

  // Events from elsewhere in Seasons

  /** A player joined: an event, and who's online is refreshed at the next tick. */
  public void joined(PlayerRef player) {
    refreshSoon = true;
    events.publish(
        new SeasonsEvent.PlayerJoined(
            clock.instant(), new PlayerName(player.id().uuid(), player.name())));
  }

  /** A player left: who's online is refreshed at the next tick. */
  public void left(PlayerId player) {
    refreshSoon = true;
  }

  /** A player walked into an announce-mode zone. */
  public void zoneEntered(PlayerId player, Zone zone) {
    events.publish(new SeasonsEvent.ZoneEntered(clock.instant(), name(player), zone.displayName()));
  }

  /** A player was archived into a season. */
  public void archived(PlayerId player, int season) {
    events.publish(new SeasonsEvent.PlayerArchived(clock.instant(), season, name(player)));
  }

  private PlayerName name(PlayerId player) {
    return new PlayerName(
        player.uuid(),
        players.find(player).map(PlayerRecord::lastName).orElse(player.uuid().toString()));
  }

  // The API

  public Optional<SeasonView> season() {
    return season;
  }

  public List<OnlinePlayer> online() {
    return online;
  }

  public List<WaypointView> waypoints() {
    List<WaypointView> list = new ArrayList<>();
    config
        .get()
        .waypoints()
        .sets()
        .forEach((set, points) -> {
          for (Waypoint point : points) {
            list.add(
                new WaypointView(
                    set,
                    point.name(),
                    point.at().dimension(),
                    point.at().blockX(),
                    point.at().blockY(),
                    point.at().blockZ()));
          }
        });
    return List.copyOf(list);
  }

  public SeasonsApi.Subscription subscribe(Consumer<SeasonsEvent> listener) {
    return events.subscribe(listener);
  }

  public CompletableFuture<Optional<GalleryView>> gallery(int seasonNumber) {
    return CompletableFuture.supplyAsync(
        () -> {
          List<Gallery> galleries = galleries(seasonNumber);
          if (galleries.isEmpty()) {
            return Optional.empty();
          }
          Optional<SeasonAwards> awards = state.flatMap(s -> s.awards(seasonNumber));
          List<GalleryView.CellView> cells = new ArrayList<>();
          for (Gallery gallery : galleries) {
            for (Cell cell : gallery.cells()) {
              Optional<PlayerId> owner = cell.owner(seasonNumber);
              cells.add(
                  new GalleryView.CellView(
                      gallery.id(),
                      cell.id(),
                      owner.map(this::name),
                      owner.map(o -> awardIds(awards, o)).orElse(List.of())));
            }
          }
          return Optional.of(new GalleryView(seasonNumber, cells));
        },
        worker);
  }

  public CompletableFuture<Optional<ArchiveView>> archive(int seasonNumber, UUID uuid) {
    PlayerId player = new PlayerId(uuid);
    return CompletableFuture.supplyAsync(
        () -> {
          Optional<ArchiveRecord> read = read(player, seasonNumber);
          if (read.isEmpty()) {
            return Optional.empty();
          }
          ArchiveRecord archive = read.get();
          List<ArchiveView.ItemView> items = new ArrayList<>();
          for (List<Optional<ArchiveSlot>> page : ArchivePages.slots(archive)) {
            for (Optional<ArchiveSlot> slot : page) {
              slot.ifPresent(at ->
                  archive
                      .item(at)
                      .flatMap(item -> ItemText.describe(at.section(), item))
                      .ifPresent(items::add));
            }
          }
          Optional<SeasonAwards> awards = state.flatMap(s -> s.awards(seasonNumber));
          List<String> won =
              archive.honours().awards().isEmpty()
                  ? awardIds(awards, player)
                  : archive.honours().awards();
          return Optional.of(
              new ArchiveView(
                  new PlayerName(uuid, archive.name()),
                  seasonNumber,
                  archive.created(),
                  cellOf(player, seasonNumber),
                  won,
                  firsts(player, seasonNumber, archive.honours().firsts()),
                  items));
        },
        worker);
  }

  public CompletableFuture<Optional<PlayerProfile>> profile(UUID uuid) {
    PlayerId player = new PlayerId(uuid);
    Optional<PlayerRecord> found = players.find(player);
    if (found.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    PlayerRecord record = found.get();
    Map<Integer, PlayerRecord.SeasonPlay> plays = new java.util.TreeMap<>(record.seasons());
    List<Integer> seasons = List.copyOf(plays.keySet());
    return statistics(List.of(record), seasons)
        .thenApply(stats -> {
          Optional<SeasonState> now = state;
          List<PlayerProfile.PlayedSeason> played = new ArrayList<>();
          for (Map.Entry<Integer, PlayerRecord.SeasonPlay> entry : plays.entrySet()) {
            int s = entry.getKey();
            PlayerRecord.SeasonPlay play = entry.getValue();
            Optional<SeasonAwards> awards = now.flatMap(st -> st.awards(s));
            played.add(
                new PlayerProfile.PlayedSeason(
                    s,
                    play.firstSeen(),
                    play.lastSeen(),
                    play.playTime(),
                    cellOf(player, s),
                    awardIds(awards, player),
                    firsts(player, s, List.of()),
                    stats.getOrDefault(new Key(player, s), Map.of())));
          }
          return Optional.of(
              new PlayerProfile(
                  uuid,
                  record.lastName(),
                  online.stream()
                      .filter(p -> p.id().equals(uuid))
                      .findFirst()
                      .flatMap(OnlinePlayer::skin),
                  played,
                  record.leaderboardHidden()));
        });
  }

  public CompletableFuture<Leaderboards> leaderboards(int seasonNumber) {
    Instant now = clock.instant();
    Cached cached = boards.get(seasonNumber);
    if (cached != null && now.isBefore(cached.at().plus(BOARDS_FOR))) {
      return cached.boards();
    }
    List<PlayerRecord> played = players.all().stream().filter(r -> r.played(seasonNumber)).toList();
    CompletableFuture<Leaderboards> worked =
        statistics(played, List.of(seasonNumber))
            .thenApply(stats -> {
              Map<PlayerName, Map<String, Long>> byName = new LinkedHashMap<>();
              Set<UUID> hidden = new HashSet<>();
              for (PlayerRecord record : played) {
                byName.put(
                    new PlayerName(record.id().uuid(), record.lastName()),
                    stats.getOrDefault(new Key(record.id(), seasonNumber), Map.of()));
                if (record.leaderboardHidden()) {
                  hidden.add(record.id().uuid());
                }
              }
              return Boards.of(seasonNumber, byName, hidden);
            });
    boards.put(seasonNumber, new Cached(now, worked));
    return worked;
  }

  /**
   * {@code /web privacy}: saved in the player's record on the server thread, which alone changes
   * records.
   */
  public CompletableFuture<Void> setLeaderboardHidden(UUID uuid, boolean hidden) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    platform
        .scheduler()
        .runGlobal(() -> {
          Optional<PlayerRecord> record = players.find(new PlayerId(uuid));
          if (record.isEmpty()) {
            done.completeExceptionally(
                new IllegalArgumentException("Seasons has no record of " + uuid));
            return;
          }
          boards.clear();
          var _ =
              players
                  .putNow(record.get().withLeaderboardHidden(hidden))
                  .whenComplete((ok, failure) -> {
                    if (failure != null) {
                      done.completeExceptionally(failure);
                    } else {
                      done.complete(null);
                    }
                  });
        });
    return done;
  }

  // Reference (Seasons API 1.3)

  public CompletableFuture<List<SeasonSummary>> seasons() {
    Optional<SeasonState> now = state;
    List<Gallery> galleries = List.copyOf(config.get().allGalleries());
    return CompletableFuture.supplyAsync(
        () -> ApiReference.seasons(players.all(), now, galleries), worker);
  }

  public CompletableFuture<MuseumView> museum() {
    Optional<SeasonState> now = state;
    return writer.submit(() -> {
      Map<String, MuseumFiles.Trophy> trophies = museum.trophies();
      Map<String, Optional<com.petepeterepeat.seasons.core.port.NeutralItem>> items =
          new HashMap<>();
      for (String anchor : trophies.keySet()) {
        items.put(anchor, museum.item(anchor));
      }
      return ApiReference.museum(trophies, a -> items.getOrDefault(a, Optional.empty()), now);
    });
  }

  public CompletableFuture<List<PendingPlayer>> pending() {
    Optional<SeasonState> now = state;
    return CompletableFuture.supplyAsync(() -> ApiReference.pending(players.all(), now), worker);
  }

  public CompletableFuture<List<AuditEntry>> audit(int count) {
    return audit
        .recent(Optional.empty(), Math.max(0, Math.min(count, 500)))
        .thenApply(entries -> entries.stream().map(ApiReference::audit).toList());
  }

  // Statistics

  private record Key(PlayerId player, int season) {}

  /**
   * Each player's season difference of the General-tab statistics for each season asked: from their
   * archive once archived, otherwise for the season in progress the game's statistics now less the
   * season's baseline (read on the server thread for online players, from disk for the rest).
   */
  private CompletableFuture<Map<Key, Map<String, Long>>> statistics(
      Collection<PlayerRecord> records, List<Integer> seasons) {
    // First on the server thread, which alone knows the season now (the snapshot can be a second
    // behind) and can read online players' statistics; then the disk on a worker.
    CompletableFuture<Live> live = new CompletableFuture<>();
    platform
        .scheduler()
        .runGlobal(() -> {
          try {
            int current = stateOnServer.get().map(SeasonState::season).orElse(-1);
            Set<UUID> onlineNow = new HashSet<>();
            for (PlayerRef ref : platform.players().online()) {
              onlineNow.add(ref.id().uuid());
            }
            Map<PlayerId, Map<String, Long>> general = new HashMap<>();
            if (seasons.contains(current)) {
              for (PlayerRecord record : records) {
                if (record.played(current) && onlineNow.contains(record.id().uuid())) {
                  Map<String, Long> now = platform.statistics().general(record.id());
                  if (!now.isEmpty()) {
                    general.put(record.id(), now);
                  }
                }
              }
            }
            live.complete(new Live(current, general));
          } catch (RuntimeException e) {
            live.completeExceptionally(e);
          }
        });
    return live.thenApplyAsync(
        now -> {
          Map<Key, Map<String, Long>> out = new HashMap<>();
          for (PlayerRecord record : records) {
            for (int s : seasons) {
              Optional<ArchiveRecord> archive = read(record.id(), s);
              if (archive.isPresent()) {
                out.put(new Key(record.id(), s), archive.get().statistics());
              } else if (s == now.season() && record.played(s)) {
                Optional<Map<String, Long>> stats =
                    Optional.ofNullable(now.general().get(record.id()))
                        .or(() -> platform.statistics().stored(record.id()));
                stats.ifPresent(st -> out.put(new Key(record.id(), s), difference(st, record, s)));
              }
            }
          }
          return out;
        },
        worker);
  }

  /** The season now and online players' statistics, as the server thread read them. */
  private record Live(int season, Map<PlayerId, Map<String, Long>> general) {}

  private static Map<String, Long> difference(
      Map<String, Long> now, PlayerRecord record, int seasonNumber) {
    Map<String, Long> baseline = record.baseline(seasonNumber).orElse(Map.of());
    Map<String, Long> out = new HashMap<>();
    now.forEach((id, value) -> out.put(id, Math.max(0, value - baseline.getOrDefault(id, 0L))));
    return out;
  }

  // Helpers

  private List<Gallery> galleries(int seasonNumber) {
    return config.get().allGalleries().stream()
        .filter(g -> g.season() == seasonNumber)
        .sorted(java.util.Comparator.comparing(Gallery::id))
        .toList();
  }

  private Optional<String> cellOf(PlayerId player, int seasonNumber) {
    for (Gallery gallery : galleries(seasonNumber)) {
      Optional<Cell> cell = gallery.cellOf(player);
      if (cell.isPresent()) {
        return Optional.of(gallery.id() + "/" + cell.get().id());
      }
    }
    return Optional.empty();
  }

  private static List<String> awardIds(Optional<SeasonAwards> awards, PlayerId player) {
    return awards
        .map(a -> a.wonBy(player).stream().map(SeasonAwards.Award::id).toList())
        .orElse(List.of());
  }

  private List<PlayerProfile.First> firsts(
      PlayerId player, int seasonNumber, List<SeasonFirst> fallback) {
    List<SeasonFirst> all =
        state.map(s -> s.firsts(seasonNumber)).filter(l -> !l.isEmpty()).orElse(fallback);
    return all.stream()
        .filter(f -> f.player().equals(player))
        .map(f -> new PlayerProfile.First(f.key(), f.label(), f.at()))
        .toList();
  }

  private Optional<ArchiveRecord> read(PlayerId player, int seasonNumber) {
    try {
      return archives.read(player, seasonNumber);
    } catch (IOException e) {
      LOG.warn("Can't read {}'s Season {} archive for Seasons API", player, seasonNumber, e);
      return Optional.empty();
    } catch (UncheckedIOException e) {
      return Optional.empty();
    }
  }
}
