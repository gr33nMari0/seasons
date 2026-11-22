// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.scenario;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.Figure;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.scenario.TickMeter.Kind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The platform's displays on a scenario server, each call timed by the {@link TickMeter} as display
 * work (A43). Every other server uses the platform's displays directly.
 */
public final class MeteredDisplays implements Displays {
  private final Displays platform;
  private final TickMeter meter;

  public MeteredDisplays(Displays platform, TickMeter meter) {
    this.platform = platform;
    this.meter = meter;
  }

  private <T> T timed(String call, Supplier<T> work) {
    return meter.measured(Kind.DISPLAY, call, work);
  }

  @Override
  public UUID spawnText(Position position, Message text, String tag) {
    return timed("spawnText", () -> platform.spawnText(position, text, tag));
  }

  @Override
  public UUID spawnText(Position position, Message text, String tag, double scale) {
    return timed("spawnText", () -> platform.spawnText(position, text, tag, scale));
  }

  @Override
  public boolean scaleText(UUID display, double scale) {
    return timed("scaleText", () -> platform.scaleText(display, scale));
  }

  @Override
  public UUID spawnFigure(Figure figure) {
    return timed("spawnFigure", () -> platform.spawnFigure(figure));
  }

  @Override
  public UUID spawnItem(Position position, NeutralItem item, String tag) {
    return timed("spawnItem", () -> platform.spawnItem(position, item, tag));
  }

  @Override
  public void remove(UUID display) {
    meter.measure(Kind.DISPLAY, "remove", () -> platform.remove(display));
  }

  @Override
  public List<Shown> tagged(Position min, Position max) {
    return timed("tagged", () -> platform.tagged(min, max));
  }

  @Override
  public boolean wears(UUID figure, List<Optional<NeutralItem>> equipment) {
    return timed("wears", () -> platform.wears(figure, equipment));
  }

  @Override
  public boolean drawnAs(UUID figure, Figure.Kind kind, Optional<String> pose) {
    return timed("drawnAs", () -> platform.drawnAs(figure, kind, pose));
  }

  @Override
  public int removeTagged(Position min, Position max, String tagPrefix) {
    return timed("removeTagged", () -> platform.removeTagged(min, max, tagPrefix));
  }

  @Override
  public void outline(PlayerId viewer, List<Outline> boxes) {
    meter.measure(Kind.DISPLAY, "outline", () -> platform.outline(viewer, boxes));
  }
}
