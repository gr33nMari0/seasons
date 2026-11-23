// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.petepeterepeat.seasons.api.SeasonsApi;
import java.util.Optional;

/**
 * The Seasons API versions this Seasons Web works with: {@code >=1.3 <2.0}. A new major version of
 * the API may break it, so Seasons Web then disables itself and says which it needs.
 */
final class ApiVersions {
  static final int MAJOR = 1;
  static final int MIN_MINOR = 3;
  static final String RANGE = ">=1.3 <2.0";

  private ApiVersions() {}

  /**
   * The API version a running Seasons reports. Seasons API before 1.2 has no {@code apiVersion()}:
   * the interface Seasons Web runs against is the one in the installed Seasons jar, so on an older
   * Seasons the call fails to link and the version is empty, and Seasons Web can still say which
   * version it needs rather than fail to enable.
   */
  static Optional<String> of(SeasonsApi seasons) {
    try {
      return Optional.of(seasons.apiVersion());
    } catch (LinkageError e) {
      return Optional.empty();
    }
  }

  static boolean supported(String version) {
    String[] parts = version.strip().split("[.-]", -1);
    if (parts.length < 2) {
      return false;
    }
    try {
      int major = Integer.parseInt(parts[0]);
      int minor = Integer.parseInt(parts[1]);
      return major == MAJOR && minor >= MIN_MINOR;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
