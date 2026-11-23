// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A line to tell a player: a messages.yml key and its placeholders' plain-text values.
 *
 * @param values placeholder name to value
 */
record Line(String key, Map<String, String> values) {
  Line {
    values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  static Line of(String key, String... pairs) {
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      values.put(pairs[i], pairs[i + 1]);
    }
    return new Line(key, values);
  }
}
