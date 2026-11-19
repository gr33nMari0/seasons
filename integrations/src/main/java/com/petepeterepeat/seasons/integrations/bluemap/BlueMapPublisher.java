// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.integrations.bluemap;

import com.petepeterepeat.seasons.core.map.MapModel;
import com.petepeterepeat.seasons.core.map.MapModel.Area;
import com.petepeterepeat.seasons.core.map.MapModel.Outline;
import com.petepeterepeat.seasons.core.map.MapModel.Point;
import com.petepeterepeat.seasons.core.map.MapModel.WaypointSet;
import com.petepeterepeat.seasons.core.map.MapPublisher;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the Seasons map to BlueMap through its API: Seasons' own marker set ({@code seasons})
 * and one per waypoint set ({@code seasons-<set>}), on every map of each world something is shown
 * in. BlueMap drops the markers added through its API whenever it reloads and then starts its API
 * again, so the latest model is published again each time it does.
 *
 * <p>Publishing runs on a thread of its own and the latest model always wins, so the server thread
 * never waits on BlueMap. A failure is tried again after 1, 2, 4 and 8 seconds, then left until the
 * next model or the next start of BlueMap. Labels are plain text: BlueMap escapes a label itself,
 * but shows a marker's detail as HTML, so every detail is the label with HTML escaped.
 */
public final class BlueMapPublisher implements MapPublisher {
  /** Seasons' own marker set, and the start of every other one's id. */
  static final String SET = "seasons";

  private static final Logger LOG = LoggerFactory.getLogger(BlueMapPublisher.class);
  private static final List<Duration> BACKOFF =
      List.of(
          Duration.ofSeconds(1),
          Duration.ofSeconds(2),
          Duration.ofSeconds(4),
          Duration.ofSeconds(8));

  /** How close a circle's outline stays to the true circle, in blocks. */
  private static final double CIRCLE_ERROR = 0.5;

  private static final int MIN_POINTS = 32;
  private static final int MAX_POINTS = 720;

  /** A model, with the platform's world for each dimension in it. */
  private record Snapshot(MapModel model, Map<String, Object> worlds) {}

  private final Function<String, Optional<Object>> worlds;
  private final ScheduledThreadPoolExecutor executor;
  private final Consumer<BlueMapAPI> enabled = this::enabled;
  private final Consumer<BlueMapAPI> disabled = this::disabled;
  private final AtomicReference<@Nullable Snapshot> latest = new AtomicReference<>();
  private final AtomicBoolean queued = new AtomicBoolean();
  private volatile @Nullable BlueMapAPI api;
  private volatile Status status = new Status.Waiting();
  private int failures;

  /**
   * Starts listening for BlueMap's API, which may already be enabled.
   *
   * @param worlds the platform's world for a dimension id, such as Paper's {@code World}, which
   *     BlueMap's {@link BlueMapAPI#getWorld} accepts; asked on the server thread only
   */
  public BlueMapPublisher(Function<String, Optional<Object>> worlds) {
    this.worlds = worlds;
    this.executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().name("seasons-bluemap").daemon(true).factory());
    // A retry still waiting when Seasons stops is dropped, not waited for.
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    BlueMapAPI.onEnable(enabled);
    BlueMapAPI.onDisable(disabled);
  }

  @Override
  public void publish(MapModel model) {
    Map<String, Object> resolved = new HashMap<>();
    for (String dimension : model.dimensions()) {
      worlds.apply(dimension).ifPresent(world -> resolved.put(dimension, world));
    }
    latest.set(new Snapshot(model, Map.copyOf(resolved)));
    schedule();
  }

  @Override
  public Status status() {
    return status;
  }

  /** Stops listening, then takes Seasons' marker sets off every map. */
  @Override
  public void close() {
    BlueMapAPI.unregisterListener(enabled);
    BlueMapAPI.unregisterListener(disabled);
    var _ =
        executor.submit(() -> {
          BlueMapAPI current = api;
          if (current != null) {
            for (BlueMapMap map : current.getMaps()) {
              map.getMarkerSets().keySet().removeIf(BlueMapPublisher::ours);
            }
          }
        });
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Waits until everything queued so far has been published; for tests. */
  void flush() throws InterruptedException, ExecutionException, TimeoutException {
    executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
  }

  private void enabled(BlueMapAPI started) {
    api = started;
    schedule();
  }

  /** BlueMap is stopping this API instance; markers wait for the next one. */
  @SuppressWarnings("ReferenceEquality")
  private void disabled(BlueMapAPI stopping) {
    if (api == stopping) {
      api = null;
      status = new Status.Waiting();
    }
  }

  /** Publishes the latest model on the publishing thread, once however often it is asked. */
  private void schedule() {
    if (queued.compareAndSet(false, true)) {
      executor.execute(() -> {
        queued.set(false);
        failures = 0;
        apply();
      });
    }
  }

  /** On the publishing thread. */
  private void apply() {
    BlueMapAPI current = api;
    Snapshot snapshot = latest.get();
    if (current == null || snapshot == null) {
      return;
    }
    try {
      Map<BlueMapMap, Map<String, MarkerSet>> wanted = new LinkedHashMap<>();
      Set<String> shown = new HashSet<>();
      snapshot
          .worlds()
          .forEach((dimension, world) -> {
            Optional<BlueMapWorld> found = current.getWorld(world);
            if (found.isEmpty()) {
              return;
            }
            for (BlueMapMap map : found.get().getMaps()) {
              Map<String, MarkerSet> sets = sets(snapshot.model(), dimension, shown);
              if (!sets.isEmpty()) {
                wanted.put(map, sets);
              }
            }
          });
      for (BlueMapMap map : current.getMaps()) {
        Map<String, MarkerSet> sets = wanted.getOrDefault(map, Map.of());
        map.getMarkerSets().keySet().removeIf(id -> ours(id) && !sets.containsKey(id));
        map.getMarkerSets().putAll(sets);
      }
      status = new Status.Published(shown.size(), wanted.size());
    } catch (RuntimeException | LinkageError e) {
      // A LinkageError: a BlueMap whose API lacks a call made here.
      if (failures >= BACKOFF.size()) {
        LOG.error("Can't publish the map to BlueMap; trying again with the next change", e);
        return;
      }
      Duration wait = BACKOFF.get(failures++);
      LOG.warn("Publishing the map to BlueMap failed; trying again in {} s", wait.toSeconds(), e);
      var _ = executor.schedule(this::apply, wait.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * The marker sets one map of a dimension gets: Seasons' own and one per waypoint set, each only
   * if it has something to show there.
   *
   * @param shown collects every marker shown, by set and id, for the status
   */
  private static Map<String, MarkerSet> sets(MapModel model, String dimension, Set<String> shown) {
    Map<String, MarkerSet> sets = new LinkedHashMap<>();
    MarkerSet own = MarkerSet.builder().label(model.label()).toggleable(true).build();
    for (Point point : model.points()) {
      if (point.at().dimension().equals(dimension)) {
        own.put(point.id(), poi(point));
        shown.add(SET + "/" + point.id());
      }
    }
    for (Area area : model.areas()) {
      if (area.dimension().equals(dimension)) {
        own.put(area.id(), area(area));
        shown.add(SET + "/" + area.id());
      }
    }
    if (!own.getMarkers().isEmpty()) {
      sets.put(SET, own);
    }
    for (WaypointSet waypoints : model.waypointSets()) {
      String id = SET + "-" + waypoints.id();
      MarkerSet set = MarkerSet.builder().label(waypoints.label()).toggleable(true).build();
      for (Point point : waypoints.points()) {
        if (point.at().dimension().equals(dimension)) {
          set.put(point.id(), poi(point));
          shown.add(id + "/" + point.id());
        }
      }
      if (!set.getMarkers().isEmpty()) {
        sets.put(id, set);
      }
    }
    return sets;
  }

  /** Whether a marker set is one of Seasons'. */
  static boolean ours(String id) {
    return id.equals(SET) || id.startsWith(SET + "-");
  }

  private static POIMarker poi(Point point) {
    return POIMarker.builder()
        .label(point.label())
        .detail(escape(point.label()))
        .position(point.at().x(), point.at().y(), point.at().z())
        .defaultIcon()
        .build();
  }

  private static ExtrudeMarker area(Area area) {
    Color line = colour(area.style(), 1f);
    return ExtrudeMarker.builder()
        .label(area.label())
        .detail(escape(area.label()))
        .shape(shape(area.outline()), (float) area.minY(), (float) area.maxY())
        .holes(area.holes().stream().map(BlueMapPublisher::shape).toArray(Shape[]::new))
        .centerPosition()
        .lineWidth(2)
        .lineColor(line)
        .fillColor(colour(area.style(), 0.15f))
        .build();
  }

  private static Color colour(MapModel.Style style, float alpha) {
    return switch (style) {
      case LOCKED -> new Color(220, 50, 50, alpha);
      case NO_BUILD -> new Color(235, 150, 30, alpha);
      case ANNOUNCE -> new Color(230, 210, 60, alpha);
    };
  }

  static Shape shape(Outline outline) {
    return switch (outline) {
      case Outline.Rectangle r -> Shape.createRect(r.minX(), r.minZ(), r.maxX(), r.maxZ());
      case Outline.Circle c ->
          Shape.createCircle(c.centerX(), c.centerZ(), c.radius(), points(c.radius()));
    };
  }

  /** Enough points that a circle's outline stays within {@link #CIRCLE_ERROR} of the circle. */
  static int points(double radius) {
    if (radius <= CIRCLE_ERROR) {
      return MIN_POINTS;
    }
    int needed = (int) Math.ceil(Math.PI / Math.acos(1 - CIRCLE_ERROR / radius));
    return Math.clamp(needed, MIN_POINTS, MAX_POINTS);
  }

  /** Text as HTML shows it literally. */
  static String escape(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
