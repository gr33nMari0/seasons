// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.log;

/** A part of Seasons whose debug lines {@code logging.debug} in config.yml can switch on. */
public enum DebugArea {
  /** Drawing and repairing cells, visits, Hall rules. */
  HALL,
  /** Each player archived, unarchived or caught up at login. */
  ARCHIVE,
  /** Zone entries refused and the zone sweep. */
  ZONES,
  /** Each Discord post and its answer. */
  DISCORD,
  /** BlueMap markers. */
  MAPS,
  /** Each Seasons command run, with who ran it. */
  COMMANDS
}
