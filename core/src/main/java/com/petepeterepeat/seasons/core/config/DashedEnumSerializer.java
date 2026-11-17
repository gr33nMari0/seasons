// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import io.leangen.geantyref.GenericTypeReflector;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.serialize.TypeSerializer;

/**
 * Enums as config files write them: lower case with dashes, such as {@code archive-and-reset} for
 * {@code ARCHIVE_AND_RESET}. An unknown value lists the accepted ones.
 */
final class DashedEnumSerializer implements TypeSerializer<Enum<?>> {
  static final DashedEnumSerializer INSTANCE = new DashedEnumSerializer();

  static boolean handles(Type type) {
    return GenericTypeReflector.erase(type).isEnum();
  }

  @Override
  public Enum<?> deserialize(Type type, ConfigurationNode node) throws SerializationException {
    Object[] constants = GenericTypeReflector.erase(type).getEnumConstants();
    String value = node.getString();
    if (value != null) {
      String wanted = value.trim().replace('-', '_');
      for (Object constant : constants) {
        Enum<?> candidate = (Enum<?>) constant;
        if (candidate.name().equalsIgnoreCase(wanted)) {
          return candidate;
        }
      }
    }
    String accepted =
        Arrays.stream(constants)
            .map(constant -> dashed((Enum<?>) constant))
            .collect(Collectors.joining(", "));
    throw new SerializationException(type, "'" + value + "' is not one of: " + accepted);
  }

  @Override
  public void serialize(Type type, @Nullable Enum<?> item, ConfigurationNode node)
      throws SerializationException {
    node.raw(item == null ? null : dashed(item));
  }

  private static String dashed(Enum<?> constant) {
    return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
