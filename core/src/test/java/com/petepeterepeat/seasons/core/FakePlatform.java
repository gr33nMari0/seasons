// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ProgressBar;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import com.petepeterepeat.seasons.core.port.Storage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A platform for core tests: players who record what they're told, a scheduler that runs global
 * work at once and repeating work when the test ticks it, and a clock the test moves.
 */
public final class FakePlatform {
  public final List<PlayerRef> online = new ArrayList<>();
  public final List<Message> broadcasts = new ArrayList<>();
  public final List<Sent> sent = new ArrayList<>();
  public final List<Bar> bars = new ArrayList<>();
  public final List<Runnable> repeating = new ArrayList<>();
  public final List<CommandNode> commands = new ArrayList<>();
  public final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T12:00:00Z"));
  public IntentHandler handler = intent -> com.petepeterepeat.seasons.core.event.Decision.allow();

  /** A message sent to one player. */
  public record Sent(PlayerId player, Message message) {}

  /** A boss bar as shown. */
  public static final class Bar implements ProgressBar {
    public Message title;
    public float progress;
    public boolean hidden;

    Bar(Message title, float progress) {
      this.title = title;
      this.progress = progress;
    }

    @Override
    public void update(Message title, float progress) {
      this.title = title;
      this.progress = progress;
    }

    @Override
    public void hide() {
      hidden = true;
    }
  }

  /** A clock tests move by hand. */
  public static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    public void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Visible bars, not hidden. */
  public List<Bar> visibleBars() {
    return bars.stream().filter(bar -> !bar.hidden).toList();
  }

  /** Runs every repeating task once, as one tick of the server would after its period. */
  public void tick() {
    List.copyOf(repeating).forEach(Runnable::run);
  }

  /** Moves the clock a second at a time, ticking each second. */
  public void runFor(Duration duration) {
    for (long i = 0; i < duration.toSeconds(); i++) {
      clock.advance(Duration.ofSeconds(1));
      tick();
    }
  }

  public Platform platform(Capabilities capabilities, Path dir) {
    Players players =
        new Players() {
          @Override
          public Collection<PlayerRef> online() {
            return List.copyOf(online);
          }

          @Override
          public Optional<PlayerRef> find(PlayerId id) {
            return online.stream().filter(p -> p.id().equals(id)).findFirst();
          }

          @Override
          public void sendMessage(PlayerId id, Message message) {
            sent.add(new Sent(id, message));
          }

          @Override
          public void broadcast(Message message) {
            broadcasts.add(message);
          }

          @Override
          public ProgressBar showProgressBar(Message title, float progress) {
            Bar bar = new Bar(title, progress);
            bars.add(bar);
            return bar;
          }
        };
    Scheduler scheduler =
        new Scheduler() {
          @Override
          public void runGlobal(Runnable task) {
            task.run();
          }

          @Override
          public void runGlobalLater(Duration delay, Runnable task) {
            task.run();
          }

          @Override
          public ScheduledTask runGlobalRepeating(Duration period, Runnable task) {
            repeating.add(task);
            return () -> repeating.remove(task);
          }

          @Override
          public void runForPlayer(PlayerId player, Runnable task) {
            task.run();
          }

          @Override
          public void runAt(Position position, Runnable task) {
            task.run();
          }

          @Override
          public void runAsync(Runnable task) {
            task.run();
          }
        };
    Storage storage =
        new Storage() {
          @Override
          public Path configDirectory() {
            return dir.resolve("plugins/Seasons");
          }

          @Override
          public Path worldDataDirectory() {
            return dir.resolve("world/seasons");
          }
        };
    return new Platform(
        "Test",
        capabilities,
        players,
        null,
        null,
        null,
        null,
        null,
        null,
        commands::add,
        (player, node) -> true,
        h -> handler = h,
        scheduler,
        null,
        null,
        storage);
  }
}
