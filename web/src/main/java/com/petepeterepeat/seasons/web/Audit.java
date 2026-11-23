// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Seasons Web's audit log, {@code <world>/seasons-web/audit/YYYY-MM.jsonl}, in the same form as
 * Seasons' own: time, actor, action, target, result, details. Logins, failed codes and revocations
 * go here (docs/questions.md 300). Never a token, a hash or an address.
 */
final class Audit {
  private static final DateTimeFormatter MONTH =
      DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

  private final Path folder;
  private final FileWriter writer;
  private final Clock clock;

  Audit(Path folder, FileWriter writer, Clock clock) {
    this.folder = folder;
    this.writer = writer;
    this.clock = clock;
  }

  void record(String actor, String action, String target, String result, String details) {
    Instant now = clock.instant();
    JsonObject line = new JsonObject();
    line.addProperty("time", now.toString());
    line.addProperty("actor", actor);
    line.addProperty("action", action);
    line.addProperty("target", target);
    line.addProperty("result", result);
    line.addProperty("details", details);
    writer.append(folder.resolve(MONTH.format(now) + ".jsonl"), line.toString());
  }
}
