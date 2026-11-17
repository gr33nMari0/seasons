// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * {@code <world>/seasons/seasons/<n>.yml}: one season's command-editable definition. M1 reads the
 * planned end; spawn, candidates, Homecoming and zones arrive with their milestones and are kept
 * untouched when a command rewrites the file.
 *
 * @param plannedEnd the time reminders count down to; the source of truth for it
 */
public record SeasonFile(int season, Optional<ZonedDateTime> plannedEnd) {}
