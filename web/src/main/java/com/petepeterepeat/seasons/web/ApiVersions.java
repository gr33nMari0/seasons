// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

/**
 * The Seasons API versions this Seasons Web works with: {@code >=1.2 <2.0}. A new major version of
 * the API may break it, so Seasons Web then disables itself and says which it needs.
 */
final class ApiVersions {
  static final int MAJOR = 1;
  static final int MIN_MINOR = 2;
  static final String RANGE = ">=1.2 <2.0";

  private ApiVersions() {}

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
