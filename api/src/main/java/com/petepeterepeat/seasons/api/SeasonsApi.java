// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.api;

import com.petepeterepeat.seasons.api.event.SeasonsEvent;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Read-only queries about the running Seasons plugin, and its events. Every method is safe from any
 * thread and never waits on the server thread: what needs the game is read from snapshots the
 * server thread refreshes, and what needs the disk completes a future on a worker.
 *
 * <p>Only Seasons implements this interface; other plugins call it. Methods may be added in a minor
 * version.
 */
public interface SeasonsApi {
  /** This API's version, which Seasons Web checks against the range it supports. */
  String API_VERSION = "1.5.0";

  /**
   * The Seasons plugin version.
   *
   * @return the version, such as {@code 1.0.0}
   */
  String version();

  /**
   * The season in progress.
   *
   * @return its number, or empty on a server that has never recorded a season
   */
  OptionalInt currentSeason();

  /** This API's version, such as {@code 1.3.0}. */
  default String apiVersion() {
    return API_VERSION;
  }

  /** The season now, or empty on a server that has never recorded a season. */
  Optional<SeasonView> season();

  /**
   * Who is online, from the latest snapshot, which the server thread refreshes every few seconds
   * and whenever someone joins or leaves. Players a vanish plugin hides are left out.
   */
  List<OnlinePlayer> online();

  /** Whether a player is online now, by the latest snapshot. */
  default boolean isOnline(UUID player) {
    return online().stream().anyMatch(p -> p.id().equals(player));
  }

  /** A player Seasons has a record of, with every season they played; empty for anyone else. */
  CompletableFuture<Optional<PlayerProfile>> profile(UUID player);

  /** Every cell of the galleries for a season, with their owners; empty if it has none. */
  CompletableFuture<Optional<GalleryView>> gallery(int season);

  /** What a player was archived with at the end of a season; empty if they have no archive. */
  CompletableFuture<Optional<ArchiveView>> archive(int season, UUID player);

  /**
   * A season's leaderboards: deaths, play time and distance, best first, over everyone who played
   * it. Players who hide themselves are included with {@link LeaderboardEntry#hidden()} set, so a
   * caller can leave them out.
   */
  CompletableFuture<Leaderboards> leaderboards(int season);

  /** The shared waypoints: server-wide places published on the map. */
  List<WaypointView> waypoints();

  /**
   * Calls {@code listener} with every event from now on, on a Seasons thread; the listener must
   * return quickly. Closing the subscription stops the calls.
   */
  Subscription subscribe(Consumer<SeasonsEvent> listener);

  /**
   * Hides a player from leaderboards, or shows them again: their choice, kept in their player
   * record. The future completes once it is saved; it fails if Seasons has no record of them.
   */
  CompletableFuture<Void> setLeaderboardHidden(UUID player, boolean hidden);

  /**
   * Every season on record, oldest first, from the players' records and the galleries: who played
   * each, the time between the first and last of them being seen, and its firsts (since 1.3).
   */
  default CompletableFuture<List<SeasonSummary>> seasons() {
    return CompletableFuture.completedFuture(List.of());
  }

  /** The museum's trophies and its egg wall (since 1.3). */
  default CompletableFuture<MuseumView> museum() {
    return CompletableFuture.completedFuture(new MuseumView(List.of(), List.of()));
  }

  /**
   * Players who played a season that has ended and aren't archived into it yet, such as those
   * offline at the end, whom catch-up archives when they next log in (since 1.3).
   */
  default CompletableFuture<List<PendingPlayer>> pending() {
    return CompletableFuture.completedFuture(List.of());
  }

  /**
   * The last {@code count} entries of Seasons' audit log, oldest first, without their details,
   * which can hold positions (since 1.3).
   */
  default CompletableFuture<List<AuditEntry>> audit(int count) {
    return CompletableFuture.completedFuture(List.of());
  }

  /**
   * A page of Seasons' audit log, filtered by kind and player, paging back with a cursor (since
   * 1.5). Without it, the last {@link AuditQuery#count()} entries as {@link #audit(int)} reads
   * them.
   */
  default CompletableFuture<AuditPage> audit(AuditQuery query) {
    return audit(query.count()).thenApply(entries -> new AuditPage(entries, Optional.empty()));
  }

  /**
   * The player last seen with a name, ignoring case, such as for a short link to their profile
   * (since 1.4).
   */
  default CompletableFuture<Optional<UUID>> playerId(String name) {
    return CompletableFuture.completedFuture(Optional.empty());
  }

  /** A subscription to events. */
  interface Subscription extends AutoCloseable {
    @Override
    void close();
  }
}
