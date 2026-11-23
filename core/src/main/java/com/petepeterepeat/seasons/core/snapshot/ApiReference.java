// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.petepeterepeat.seasons.api.ArchiveView;
import com.petepeterepeat.seasons.api.AuditEntry;
import com.petepeterepeat.seasons.api.MuseumView;
import com.petepeterepeat.seasons.api.PendingPlayer;
import com.petepeterepeat.seasons.api.PlayerName;
import com.petepeterepeat.seasons.api.SeasonSummary;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.MuseumFiles;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.zone.UnlockCondition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Seasons API 1.3's reference views, worked out from records, galleries, the museum's files and the
 * season state: season history, the museum, players waiting to be archived, the audit log without
 * details, and when Homecoming is planned. Pure, so any thread may call it.
 */
public final class ApiReference {
  private ApiReference() {}

  /**
   * When Homecoming comes by itself: its date, or the time after launch it waits; empty once it has
   * come, or when only a command, the dragon or players reaching the End bring it.
   */
  public static Optional<Instant> plannedHomecoming(
      UnlockCondition homecoming, Optional<Instant> launchedAt, Optional<Instant> homecomingAt) {
    if (homecomingAt.isPresent()) {
      return Optional.empty();
    }
    return switch (homecoming) {
      case UnlockCondition.At at -> Optional.of(at.time().toInstant());
      case UnlockCondition.After after -> launchedAt.map(l -> l.plus(after.after()));
      default -> Optional.empty();
    };
  }

  /** Whether a season has ended, by the state now. */
  static boolean ended(int season, Optional<SeasonState> state) {
    if (state.isEmpty()) {
      return false;
    }
    SeasonState s = state.get();
    return s.season() > season
        || (s.season() == season
            && (s.phase() instanceof Phase.Archiving || s.phase() instanceof Phase.Intermission));
  }

  /** Every season on record, oldest first. */
  public static List<SeasonSummary> seasons(
      Collection<PlayerRecord> records,
      Optional<SeasonState> state,
      Collection<Gallery> galleries) {
    TreeSet<Integer> numbers = new TreeSet<>();
    records.forEach(r -> numbers.addAll(r.seasons().keySet()));
    galleries.stream()
        .filter(g -> g.kind() != Gallery.Kind.MUSEUM)
        .forEach(g -> numbers.add(g.season()));
    state.ifPresent(s -> numbers.add(s.season()));
    List<SeasonSummary> out = new ArrayList<>();
    for (int n : numbers) {
      if (n < 1) {
        continue;
      }
      List<PlayerRecord.SeasonPlay> plays =
          records.stream().map(r -> r.seasons().get(n)).filter(java.util.Objects::nonNull).toList();
      boolean current = state.map(s -> s.season() == n).orElse(false);
      out.add(
          new SeasonSummary(
              n,
              current,
              plays.stream().map(PlayerRecord.SeasonPlay::firstSeen).min(Comparator.naturalOrder()),
              plays.stream().map(PlayerRecord.SeasonPlay::lastSeen).max(Comparator.naturalOrder()),
              plays.size(),
              state.map(s -> s.firsts(n).size()).orElse(0),
              galleries.stream()
                  .anyMatch(g -> g.season() == n && g.kind() != Gallery.Kind.MUSEUM)));
    }
    return List.copyOf(out);
  }

  /**
   * Players who played a season that has ended and aren't archived into it, each with the first
   * such season, by name.
   */
  public static List<PendingPlayer> pending(
      Collection<PlayerRecord> records, Optional<SeasonState> state) {
    List<PendingPlayer> out = new ArrayList<>();
    for (PlayerRecord r : records) {
      r.seasons().keySet().stream()
          .sorted()
          .filter(s -> s > r.archivedThrough() && ended(s, state))
          .findFirst()
          .ifPresent(
              s -> out.add(new PendingPlayer(new PlayerName(r.id().uuid(), r.lastName()), s)));
    }
    out.sort(
        Comparator.comparing(
            (PendingPlayer p) -> p.player().name(), String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(out);
  }

  /**
   * The museum: its trophies by anchor, each item as text, and an egg for every ended season whose
   * dragon a player slew.
   *
   * @param items a trophy's item by anchor
   */
  public static MuseumView museum(
      Map<String, MuseumFiles.Trophy> trophies,
      Function<String, Optional<NeutralItem>> items,
      Optional<SeasonState> state) {
    List<MuseumView.Trophy> shown = new ArrayList<>();
    trophies.values().stream()
        .sorted(Comparator.comparing(MuseumFiles.Trophy::anchor))
        .forEach(t -> {
          Optional<ArchiveView.ItemView> item =
              items.apply(t.anchor()).flatMap(i -> ItemText.describe("museum", i));
          shown.add(
              new MuseumView.Trophy(t.anchor(), t.label(), t.season(), t.credit(), t.at(), item));
        });
    List<MuseumView.Egg> eggs = new ArrayList<>();
    for (SeasonFirst first : state.map(SeasonState::firsts).orElse(List.of())) {
      if (first.key().equals(FirstsTracker.DRAGON) && ended(first.season(), state)) {
        eggs.add(
            new MuseumView.Egg(
                first.season(), new PlayerName(first.player().uuid(), first.name()), first.at()));
      }
    }
    eggs.sort(Comparator.comparingInt(MuseumView.Egg::season));
    return new MuseumView(shown, eggs);
  }

  /** An audit entry without its details. */
  public static AuditEntry audit(AuditLog.Entry e) {
    return new AuditEntry(e.time(), e.actor(), e.action(), e.target(), e.result());
  }
}
