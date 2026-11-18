// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.guard.Immunity;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerRecord.HallReturn;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.BlockInfo;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(HallVisits.class);
  private static final Duration SWEEP = Duration.ofMillis(500);
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String ENDER_PEARL = "minecraft:ender_pearl";
  private static final String CHORUS_FRUIT = "minecraft:chorus_fruit";

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService halls;
  private final PlayerStore players;
  private final Immunity immunity;
  private final Clock clock;
  private final IntFunction<Optional<Position>> seasonSpawn;
  private final Executor serverThread;
  private Supplier<Optional<SeasonState>> state = Optional::empty;

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
      PlayerStore players,
      Immunity immunity,
      Clock clock,
      IntFunction<Optional<Position>> seasonSpawn) {
    this.platform = platform;
    this.config = config;
    this.halls = halls;
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
    SeasonsConfig.Hof.Entry rules = config.get().config().hof().entry();
    Instant now = clock.instant();
    Instant attempted = lastAttempt.get(player);
    if (attempted != null && now.isBefore(attempted.plus(rules.rateLimit()))) {
      tell(player, "hof.entry.rate-limit");
      return;
    }
    lastAttempt.put(player, now);
    if (moving.contains(player)) {
      return;
    }
    Optional<Position> destination = destination(player, target);
    if (destination.isEmpty()) {
      return;
    }
    Position at = platform.players().position(player);
    if (halls.inHall(at)) {
      // Already inside: just a walk across the Hall, so no checks and the saved spot stays.
      moveTo(player, destination.get(), "hof.visit.arrived");
      return;
    }
    Instant visited = lastVisit.get(player);
    if (rules.cooldown().isPositive()
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
                now));
    if (failed.isPresent()) {
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
    if (unsafe.isPresent()) {
      tell(player, "hof.entry.unsafe", "reason", messages().text("hof.unsafe." + unsafe.get()));
      return;
    }
    HallReturn back = new HallReturn(at, platform.players().gameMode(player));
    moving.add(player);
    var _ =
        saveReturn(player, Optional.of(back))
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
                      destination.get(),
                      "hof.visit.arrived",
                      // They never left: a saved spot outside the Hall would be a free teleport.
                      () -> {
                        var _ = saveReturn(player, Optional.empty());
                      });
                },
                serverThread);
  }

  /** Where {@code /hof} with this target lands, telling the player if nowhere. */
  private Optional<Position> destination(PlayerId player, Optional<String> target) {
    if (target.isEmpty()) {
      Optional<Position> entry = hallEntry();
      if (entry.isEmpty()) {
        tell(player, "hof.visit.no-entry");
      }
      return entry;
    }
    String text = target.get();
    if (text.chars().allMatch(Character::isDigit) && !text.isEmpty() && text.length() < 9) {
      int season = Integer.parseInt(text);
      Optional<Position> entry =
          halls.seasonGalleries(season).stream()
              .flatMap(g -> g.entry().stream())
              .findFirst()
              .map(HallService::standOn);
      if (entry.isEmpty()) {
        tell(player, "hof.visit.no-season", "season", text);
      }
      return entry;
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
    }
    return arrival;
  }

  /** The Hall entry: that of the newest season's gallery that has one. */
  public Optional<Position> hallEntry() {
    return halls.galleries().stream()
        .filter(g -> g.kind() == Gallery.Kind.SEASON && g.entry().isPresent())
        .max(Comparator.comparingInt(Gallery::season))
        .flatMap(Gallery::entry)
        .map(HallService::standOn);
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
    if (state.get().map(s -> !playing(s)).orElse(false)) {
      tell(player, "hof.leave.to-entry");
    } else {
      tell(player, "hof.leave.no-return");
    }
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

  // Sealing

  /** The visits' say on an intent, if they have one. */
  public Optional<Decision> decide(Intent intent) {
    PlayerId player = intent.player();
    return switch (intent) {
      case Intent.UseBlock use -> bedClick(use);
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
    if ((!fromIn && !toIn) || bypasses(player)) {
      return none();
    }
    if (teleport.kind() == Intent.TeleportKind.PEARL
        || teleport.kind() == Intent.TeleportKind.CHORUS) {
      if (fromIn && !toIn) {
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
    var _ =
        saveReturn(player, Optional.of(new HallReturn(from, platform.players().gameMode(player))));
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
        if (from != null && !bypasses(player)) {
          if (platform.players().movement(player).riding()) {
            // Ridden in, which no move event reports: back out the way they came.
            moveOut(player, from);
          } else {
            enteredOtherwise(player, from);
          }
        }
      } else if (!in && was) {
        leftHall(player);
      } else if (in && onExit(at) && !bypasses(player)) {
        savedReturn(player).ifPresent(back -> returnTo(player, back, "hof.leave.returned"));
      }
    }
    inside.retainAll(online);
    known.retainAll(online);
    lastOutside.keySet().retainAll(online);
    moving.retainAll(online);
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

  /** Whether a player stands on an exit anchor, or on the block above one. */
  private boolean onExit(Position at) {
    for (Gallery gallery : halls.galleries()) {
      for (Position exit : gallery.exits()) {
        if (exit.dimension().equals(at.dimension())
            && exit.blockX() == at.blockX()
            && exit.blockZ() == at.blockZ()
            && (exit.blockY() == at.blockY() || exit.blockY() == at.blockY() - 1)) {
          return true;
        }
      }
    }
    return false;
  }

  // Helpers

  private boolean sealed(PlayerId player) {
    return halls.inHall(platform.players().position(player)) && !bypasses(player);
  }

  /** An owner decorating the cell they stand in picks up what they broke there. */
  private boolean decoratingHere(PlayerId player) {
    return halls
        .cellAt(platform.players().position(player))
        .filter(ref -> halls.canDecorate(player, ref))
        .isPresent();
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
    Optional<PlayerRecord> record = players.find(player);
    if (record.isPresent()) {
      var _ =
          players.putNow(record.get().withHallReturn(Optional.empty()).withHallAdventure(false));
    }
  }

  /** Drops a saved spot of a player outside the Hall, where it could only be a free teleport. */
  private void dropStaleReturn(PlayerId player) {
    if (savedReturn(player).isPresent()) {
      LOG.info("Dropping {}'s saved Hall return: they are outside the Hall", player);
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
