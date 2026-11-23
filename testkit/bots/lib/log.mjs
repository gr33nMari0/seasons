// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Reading Seasons' lines in the server log.

/** Seasons' own lines in the log since the scenario started, as { level, text }. */
export function seasonsLines (ctx) {
  return ctx.serverLog().split('\n')
    .map((line) => line.match(/^\[[\d:]+ (INFO|WARN|ERROR)\]: \[Seasons\] (.*)$/))
    .filter(Boolean)
    .map(([, level, text]) => ({ level, text }))
}
