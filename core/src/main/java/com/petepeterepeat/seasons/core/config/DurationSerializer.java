// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.function.Predicate;
import org.spongepowered.configurate.serialize.ScalarSerializer;
import org.spongepowered.configurate.serialize.SerializationException;

/** Reads and writes {@link Duration}s in the {@link Durations} form. */
final class DurationSerializer extends ScalarSerializer<Duration> {
  static final DurationSerializer INSTANCE = new DurationSerializer();

  private DurationSerializer() {
    super(Duration.class);
  }

  @Override
  public Duration deserialize(Type type, Object value) throws SerializationException {
    try {
      return Durations.parse(value.toString());
    } catch (IllegalArgumentException e) {
      throw new SerializationException(type, e.getMessage());
    }
  }

  @Override
  protected Object serialize(Duration item, Predicate<Class<?>> typeSupported) {
    return Durations.format(item);
  }
}
