// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.FakePlatform;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.hall.HallBounds;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.FurthestTracker;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The archive hold: what a held player may and may not do (security review M1). */
class IntentRouterTest {
  private static final PlayerId ALEX = new PlayerId(UUID.randomUUID());
  private static final PlayerId ADMIN = new PlayerId(UUID.randomUUID());

  @TempDir Path dir;
  private final ArchiveHold hold = new ArchiveHold();
  private IntentRouter router;

  @BeforeEach
  void setUp() {
    FakePlatform fake = new FakePlatform();
    var platform =
        fake.platform(
            new Capabilities(EnumSet.noneOf(com.petepeterepeat.seasons.core.port.Capability.class)),
            dir);
    Messages messages = Messages.defaults();
    router =
        new IntentRouter(
            hold,
            new Immunity(fake.clock),
            new FurthestTracker(
                Optional::empty,
                () -> {
                  throw new AssertionError("no config needed");
                },
                new PlayerStore(dir.resolve("players"), new SerialWriter(Runnable::run)),
                HallBounds.none(),
                platform.world()),
            intent -> Optional.empty(),
            player -> {},
            player -> Set.of(ADMIN).contains(player),
            () -> messages);
  }

  @Test
  void aHeldPlayersInventoryCantChangeByOrbsWearOrCommands() {
    hold.hold(ALEX);

    assertThat(router.decide(new Intent.PickupExperience(ALEX))).isInstanceOf(Decision.Deny.class);
    assertThat(router.decide(new Intent.WearItem(ALEX))).isInstanceOf(Decision.Deny.class);
    Decision command = router.decide(new Intent.RunCommand(ALEX, "/hat"));
    assertThat(command).isInstanceOf(Decision.Deny.class);
    assertThat(((Decision.Deny) command).message()).isPresent();

    hold.release(ALEX);
    assertThat(router.decide(new Intent.PickupExperience(ALEX))).isEqualTo(Decision.allow());
    assertThat(router.decide(new Intent.WearItem(ALEX))).isEqualTo(Decision.allow());
    assertThat(router.decide(new Intent.RunCommand(ALEX, "/hat"))).isEqualTo(Decision.allow());
  }

  @Test
  void anAdminRunsCommandsWhileHeldAndAnArrivingPlayerToo() {
    hold.hold(ADMIN);
    hold.holdArriving(ALEX);

    assertThat(router.decide(new Intent.RunCommand(ADMIN, "/season status")))
        .isEqualTo(Decision.allow());
    assertThat(router.decide(new Intent.RunCommand(ALEX, "/vault"))).isEqualTo(Decision.allow());
    assertThat(router.decide(new Intent.PickupExperience(ALEX))).isInstanceOf(Decision.Deny.class);
  }
}
