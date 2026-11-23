// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

/**
 * A device as {@code /web sessions} names it, such as "Safari on iPad", from its user agent and the
 * wording in messages.yml when it logs in. Only this label is kept, never the user agent or the
 * address.
 */
final class Devices {
  private Devices() {}

  static String label(Messages messages, String userAgent) {
    String ua = userAgent == null ? "" : userAgent;
    String browser =
        ua.contains("Edg/")
            ? "Edge"
            : ua.contains("Firefox/") || ua.contains("FxiOS/")
                ? "Firefox"
                : ua.contains("Chrome/") || ua.contains("CriOS/")
                    ? "Chrome"
                    : ua.contains("Safari/") ? "Safari" : "";
    String system =
        ua.contains("iPad")
            ? "iPad"
            : ua.contains("iPhone")
                ? "iPhone"
                : ua.contains("Android")
                    ? "Android"
                    : ua.contains("Windows")
                        ? "Windows"
                        : ua.contains("Mac OS X") || ua.contains("Macintosh")
                            ? "Mac"
                            : ua.contains("CrOS")
                                ? "ChromeOS"
                                : ua.contains("Linux") ? "Linux" : "";
    if (browser.isEmpty() && system.isEmpty()) {
      return messages.text("sessions.unknown-device");
    }
    if (browser.isEmpty()) {
      return system;
    }
    return system.isEmpty()
        ? browser
        : messages.text("sessions.device", "browser", browser, "system", system);
  }
}
