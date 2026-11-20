// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What an award in config.yml counts (see Stats, season cards and awards in the spec): one of the
 * season card's highlights, the dragon slayer, or any General-tab statistic by its id. {@code off}
 * turns a default award off, since the bundled awards are always merged in.
 */
public sealed interface AwardMeasure {
  /** How the measure is written in config.yml. */
  String text();

  /** A measure Seasons names. */
  enum Named implements AwardMeasure {
    DEATHS("deaths"),
    TRAVELLED("travelled"),
    PLAY_TIME("play-time"),
    DIAMONDS("diamonds"),
    DRAGON("dragon"),
    FURTHEST_BUILDER("furthest-builder"),
    ADVANCEMENTS("advancements"),
    OFF("off");

    private final String text;

    Named(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  /** A General-tab statistic, such as {@code minecraft:jump}, as this season's difference. */
  record Statistic(String id) implements AwardMeasure {
    @Override
    public String text() {
      return id;
    }
  }

  Pattern STATISTIC = Pattern.compile("minecraft:[a-z0-9_]+");

  /**
   * The measure written as {@code text}, if Seasons knows it. YAML reads a bare {@code off} as
   * false, so false is off too.
   */
  static Optional<AwardMeasure> parse(String text) {
    String trimmed = text.strip().toLowerCase(Locale.ROOT);
    if (trimmed.equals("false")) {
      return Optional.of(Named.OFF);
    }
    Optional<AwardMeasure> named =
        Arrays.stream(Named.values())
            .filter(n -> n.text.equals(trimmed))
            .<AwardMeasure>map(n -> n)
            .findFirst();
    if (named.isPresent()) {
      return named;
    }
    return STATISTIC.matcher(trimmed).matches()
        ? Optional.of(new Statistic(trimmed))
        : Optional.empty();
  }

  /** Every measure Seasons names, as a problem lists them. */
  static String choices() {
    return String.join(", ", Arrays.stream(Named.values()).map(Named::text).toList())
        + " or a statistic id such as minecraft:jump";
  }
}
