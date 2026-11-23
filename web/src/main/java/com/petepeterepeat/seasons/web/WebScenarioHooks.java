// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.time.Duration;

/**
 * Seasons Web's scenario hooks (docs/adr/0003): registered as a service only when the server starts
 * with {@code -Dseasons.scenario=true}, for the test plugin. Nothing turns them on at run time.
 */
public interface WebScenarioHooks {
  /** Reads web.yml again and listens as it says: "listening on 127.0.0.1:8019" or "off (why)". */
  String reload();

  /** Back to a fresh install: web.yml and every session, code and limit gone. */
  void reset();

  /** Moves Seasons Web's clock forward, with Seasons' own. */
  void advance(Duration by);
}
