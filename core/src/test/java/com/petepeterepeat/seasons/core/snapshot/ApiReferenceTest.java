// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.api.AuditEntry;
import com.petepeterepeat.seasons.api.MuseumView;
import com.petepeterepeat.seasons.api.PendingPlayer;
import com.petepeterepeat.seasons.api.PlayerName;
import com.petepeterepeat.seasons.api.SeasonSummary;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.MuseumFiles;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.season.FirstsTracker;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.season.Stage;
import com.petepeterepeat.seasons.core.zone.UnlockCondition;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiReferenceTest {
  private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");
  private final PlayerId alex = new PlayerId(UUID.randomUUID());
  private final PlayerId sam = new PlayerId(UUID.randomUUID());

  private static PlayerRecord played(PlayerId id, String name, int... seasons) {
    PlayerRecord r = PlayerRecord.create(id, name);
    for (int s : seasons) {
      r = r.withPlay(s, T.plus(Duration.ofDays(30L * s)), Duration.ofHours(1));
    }
    return r;
  }

  @Test
  void homecomingIsPlannedByADateOrATimeAfterLaunchAndNothingElse() {
    Instant date = Instant.parse("2027-01-10T08:00:00Z");
    assertThat(
            ApiReference.plannedHomecoming(
                new UnlockCondition.At(date.atZone(ZoneOffset.ofHours(11))),
                Optional.empty(),
                Optional.empty()))
        .contains(date);
    assertThat(
            ApiReference.plannedHomecoming(
                new UnlockCondition.After(Duration.ofDays(21)), Optional.of(T), Optional.empty()))
        .contains(T.plus(Duration.ofDays(21)));
    assertThat(
            ApiReference.plannedHomecoming(
                new UnlockCondition.After(Duration.ofDays(21)), Optional.empty(), Optional.empty()))
        .as("not launched yet")
        .isEmpty();
    assertThat(
            ApiReference.plannedHomecoming(
                new UnlockCondition.DragonKilled(), Optional.of(T), Optional.empty()))
        .isEmpty();
    assertThat(
            ApiReference.plannedHomecoming(
                new UnlockCondition.At(date.atZone(ZoneOffset.UTC)),
                Optional.of(T),
                Optional.of(T)))
        .as("once it has come")
        .isEmpty();
  }

  @Test
  void seasonsFromRecordsGalleriesAndTheStateOldestFirst() {
    SeasonState now =
        SeasonState.start(3, Stage.FRONTIER, Optional.of(T))
            .withFirst(new SeasonFirst(2, "nether", "reached the Nether", alex, "Alex", T))
            .withFirst(new SeasonFirst(2, "end", "reached the End", sam, "Sam", T));
    List<SeasonSummary> seasons =
        ApiReference.seasons(
            List.of(played(alex, "Alex", 2, 3), played(sam, "Sam", 2)),
            Optional.of(now),
            List.of(
                Gallery.create("season-1", 1, "minecraft:overworld"),
                Gallery.create("museum", 0, "minecraft:overworld").withKind(Gallery.Kind.MUSEUM)));
    assertThat(seasons).extracting(SeasonSummary::season).containsExactly(1, 2, 3);
    SeasonSummary one = seasons.get(0);
    assertThat(one.players()).isZero();
    assertThat(one.gallery()).isTrue();
    assertThat(one.firstSeen()).isEmpty();
    SeasonSummary two = seasons.get(1);
    assertThat(two.players()).isEqualTo(2);
    assertThat(two.firsts()).isEqualTo(2);
    assertThat(two.gallery()).isFalse();
    assertThat(two.current()).isFalse();
    assertThat(two.firstSeen()).isPresent();
    assertThat(seasons.get(2).current()).isTrue();
    assertThat(seasons.get(2).players()).isEqualTo(1);
  }

  @Test
  void pendingPlayersPlayedASeasonThatEndedAndArentArchivedIntoIt() {
    PlayerRecord archived = played(alex, "Alex", 2).withArchived(2);
    PlayerRecord waiting = played(sam, "Sam", 2);
    SeasonState active = SeasonState.init(2);
    assertThat(ApiReference.pending(List.of(archived, waiting), Optional.of(active)))
        .as("nothing has ended")
        .isEmpty();
    SeasonState ended = active.withPhase(new Phase.Intermission());
    assertThat(ApiReference.pending(List.of(archived, waiting), Optional.of(ended)))
        .containsExactly(new PendingPlayer(new PlayerName(sam.uuid(), "Sam"), 2));
    assertThat(ApiReference.pending(List.of(waiting), Optional.empty())).isEmpty();
  }

  @Test
  void theMuseumsTrophiesAndAnEggForEachEndedSeasonsSlayer() {
    MuseumFiles.Trophy core =
        new MuseumFiles.Trophy("museum:core", Optional.of("The first core"), 2, "Peter", T, "t1");
    SeasonState now =
        SeasonState.start(3, Stage.FRONTIER, Optional.of(T))
            .withFirst(new SeasonFirst(2, FirstsTracker.DRAGON, "", alex, "Alex", T))
            .withFirst(new SeasonFirst(3, FirstsTracker.DRAGON, "", sam, "Sam", T));
    MuseumView museum =
        ApiReference.museum(Map.of(core.anchor(), core), a -> Optional.empty(), Optional.of(now));
    assertThat(museum.trophies())
        .singleElement()
        .satisfies(t -> {
          assertThat(t.label()).contains("The first core");
          assertThat(t.credit()).isEqualTo("Peter");
          assertThat(t.item()).as("an item that can't be read").isEmpty();
        });
    assertThat(museum.eggs())
        .as("Season 3 hasn't ended, so its egg isn't on the wall yet")
        .containsExactly(new MuseumView.Egg(2, new PlayerName(alex.uuid(), "Alex"), T));
  }

  @Test
  void anAuditEntryWithoutItsDetails() {
    AuditEntry e =
        ApiReference.audit(
            new AuditLog.Entry(T, "Peter", "zone-add", "old-base", "ok", "box 500 -61 500"));
    assertThat(e).isEqualTo(new AuditEntry(T, "Peter", "zone-add", "old-base", "ok"));
  }
}
