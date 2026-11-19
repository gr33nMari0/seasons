// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.map;

/**
 * Publishes the map to a web map such as BlueMap. Implementations take the latest model on the
 * server thread and publish it on their own thread, again whenever the web map reloads, and never
 * stall the tick. Seasons runs with {@link #NONE} when no web map is installed.
 */
public interface MapPublisher extends AutoCloseable {
  /** No web map installed: nothing is published. */
  MapPublisher NONE =
      new MapPublisher() {
        @Override
        public void publish(MapModel model) {}

        @Override
        public Status status() {
          return new Status.NotInstalled();
        }

        @Override
        public void close() {}
      };

  /** Replaces what is published with this model. On the server thread; returns at once. */
  void publish(MapModel model);

  /** What is published now, for {@code /season status}. */
  Status status();

  /** Stops publishing and takes down what was published. */
  @Override
  void close();

  /** Where publishing stands. */
  sealed interface Status {
    /** No web map is installed. */
    record NotInstalled() implements Status {}

    /** The web map is installed but hasn't started, or is reloading. */
    record Waiting() implements Status {}

    /** Published: so many markers, over so many of the web map's maps. */
    record Published(int markers, int maps) implements Status {}
  }
}
