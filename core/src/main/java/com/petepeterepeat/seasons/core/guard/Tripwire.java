// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.guard;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.port.Inventories;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import com.petepeterepeat.seasons.core.port.TripwireReport;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tripwire: every item shown in an archive view carries the {@code seasons:archived} tag, and
 * one found in a real inventory or on the ground is deleted and audited. The adapter checks
 * pickups, items appearing in the world and menu closes; this sweeps every online player every 30
 * seconds. Reporting hits to the admin webhook waits for M4; until then they are logged as
 * warnings.
 */
public final class Tripwire implements TripwireReport {
  /** How often online players are swept. */
  public static final Duration SWEEP = Duration.ofSeconds(30);

  private static final Logger LOG = LoggerFactory.getLogger(Tripwire.class);

  private final Players players;
  private final Inventories inventories;
  private final Scheduler scheduler;
  private final AuditLog audit;
  private @Nullable ScheduledTask sweep;

  public Tripwire(Players players, Inventories inventories, Scheduler scheduler, AuditLog audit) {
    this.players = players;
    this.inventories = inventories;
    this.scheduler = scheduler;
    this.audit = audit;
  }

  public void start() {
    sweep = scheduler.runGlobalRepeating(SWEEP, this::sweep);
  }

  public void stop() {
    if (sweep != null) {
      sweep.cancel();
      sweep = null;
    }
  }

  /** Sweeps every online player once. */
  public void sweep() {
    for (PlayerRef player : players.online()) {
      List<String> removed = inventories.purgeArchived(player.id());
      if (!removed.isEmpty()) {
        found(Optional.of(player.id()), "in an inventory", removed);
      }
    }
  }

  @Override
  public void found(Optional<PlayerId> player, String where, List<String> items) {
    String name =
        player
            .flatMap(players::find)
            .map(PlayerRef::name)
            .or(() -> player.map(PlayerId::toString))
            .orElse("-");
    String details = "Deleted " + String.join(", ", items) + " " + where;
    LOG.warn("Tripwire: {} ({}). Archived items must never exist in the world.", details, name);
    audit.record(AuditLog.SEASONS, "tripwire", name, "deleted", details);
  }
}
