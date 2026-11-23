// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.petepeterepeat.seasons.api.SeasonsApi;
import com.petepeterepeat.seasons.api.event.SeasonsEvent;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Seasons API's events: published from the server thread, delivered in order on one events thread,
 * so a slow listener never holds up the game.
 */
public final class ApiEvents {
  private static final Logger LOG = SeasonsLog.get();

  private final Executor delivery;
  private final List<Consumer<SeasonsEvent>> listeners = new CopyOnWriteArrayList<>();

  /**
   * Makes the feed.
   *
   * @param delivery runs deliveries one at a time, in order
   */
  public ApiEvents(Executor delivery) {
    this.delivery = delivery;
  }

  public SeasonsApi.Subscription subscribe(Consumer<SeasonsEvent> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  public void publish(SeasonsEvent event) {
    if (listeners.isEmpty()) {
      return;
    }
    try {
      delivery.execute(() -> {
        for (Consumer<SeasonsEvent> listener : listeners) {
          try {
            listener.accept(event);
          } catch (RuntimeException e) {
            LOG.warn("A Seasons API listener failed on {}", event.getClass().getSimpleName(), e);
          }
        }
      });
    } catch (RejectedExecutionException e) {
      // Stopping.
    }
  }

  /** Forgets every listener, as Seasons stops. */
  public void clear() {
    listeners.clear();
  }
}
