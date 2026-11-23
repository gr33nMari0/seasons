// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @isolated it measures ticks, so nothing else may share its server, and the tick meter wraps Seasons' listeners for the rest of the server's life
// A43. With 20 bots online, 30 zones and a full rollover, the average tick time Seasons adds stays
// under 1 ms, and rendering displays never adds more than 5 ms to a single tick. Seasons' server
// log stays within a small budget of lines over the whole run.
//
// The scenario hooks' tick meter (docs/adr/0003) times, on the server thread and tick by tick,
// every task Seasons schedules, every one of its event listeners and every display it draws or
// removes, from just before the end until after Homecoming: the end with everyone archived and
// their cells drawn, the Intermission, the launch with everyone moved to the new spawn and 30
// zones locked, a stretch of the Frontier with the zone sweep over everyone, and Homecoming.
// Paper's bundled spark profiler samples the server thread over the same stretch; its report is
// saved beside the server's files for a person to read (docs/questions.md 289).
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR, buildGallery } from '../lib/hall.mjs'
import { seasonsLines } from '../lib/log.mjs'
import { standAt } from '../lib/visits.mjs'
import { SPAWN } from '../lib/zones.mjs'

const PLAYERS = 20
const ZONES = 30

/** A43's limits, in nanoseconds. */
const AVERAGE_LIMIT = 1_000_000
const DISPLAY_LIMIT = 5_000_000

/**
 * The most lines Seasons may write to the server log over the whole run, with 20 players archived,
 * 22 cells drawn, 30 zones and the zone sweep: milestones are one line each, never one per player
 * or per tick (docs/questions.md 295).
 */
const LOG_LINE_LIMIT = 20

/**
 * How long the Frontier runs with everyone online and every zone locked, in ticks: a minute. The
 * average is over every tick from the end to Homecoming, a stretch far shorter than a real
 * rollover's, whose 10-minute countdown and 15-minute decorating window the scenario skips, so
 * the archiving and drawing weigh more here than they would (docs/questions.md 289).
 */
const FRONTIER_TICKS = 1200

/** Seasons' share of the ticks the meter watched, from /scenario perf stop. */
async function stopMeter (ctx, admin) {
  const line = await ctx.command(admin, '/scenario perf stop', /^Perf ticks=/)
  const number = (key) => Number(new RegExp(`${key}=(\\d+)`).exec(line)?.[1] ?? NaN)
  return {
    line,
    ticks: number('ticks'),
    average: number('average-ns'),
    max: number('max-ns'),
    displayMax: number('display-max-ns'),
    displayTicks: number('display-ticks'),
    tasks: number('tasks'),
    listeners: number('listeners')
  }
}

/** The 30 zones: boxes in a ring around the new spawn, near enough that the sweep weighs each. */
function zones () {
  const ring = []
  for (let i = 0; i < ZONES; i++) {
    const angle = (2 * Math.PI * i) / ZONES
    const x = Math.round(SPAWN.x + 200 * Math.cos(angle))
    const z = Math.round(SPAWN.z + 200 * Math.sin(angle))
    ring.push({ id: `perf-${i + 1}`, from: { x: x - 10, y: FLOOR - 4, z: z - 10 }, to: { x: x + 10, y: FLOOR + 40, z: z + 10 } })
  }
  return ring
}

export default {
  name: 'a43-performance',
  timeoutMs: 240 * SECOND,
  async run (ctx) {
    const admin = await ctx.admin()
    const bots = []
    for (let i = 1; i <= PLAYERS; i++) {
      bots.push(await ctx.join(`Perf${String(i).padStart(2, '0')}`))
    }
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    // A cell for everyone online, the admin included, and one to spare.
    await buildGallery(ctx, admin, { cells: PLAYERS + 2 })

    // The game's own first figure and text display, away from the Hall: the first of each kind a
    // server ever spawns loads the game's classes for it, which is no work of Seasons'.
    // The first figure and display a server ever spawns loads Paper's classes for them: a one-off
    // of the game's, which the test plugin pays here (docs/questions.md 289).
    await ctx.command(admin, '/scenario perf warm', /^Perf warmed$/)
    await ctx.command(admin, '/scenario perf start', /^Perf meter started$/)
    await ctx.command(admin, '/spark profiler start', /Profiler is now running/)

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 60 * SECOND)
    for (const bot of bots) {
      await ctx.eventually(async () => {
        const { archivedThrough } = await ctx.archive(admin, bot.username, 2)
        assert(archivedThrough === 2, `${bot.username} is archived`)
      }, 30 * SECOND)
    }
    // Every cell's displays are drawn: a figure for each player.
    await ctx.eventually(async () => {
      const line = await ctx.command(admin, '/execute if entity @e[type=minecraft:mannequin]', /^Test (passed|failed)/)
      const count = Number(/count: (\d+)/i.exec(line)?.[1] ?? 0)
      assert(count >= PLAYERS + 1, `a figure in every owned cell: ${line}`)
    }, 30 * SECOND)

    await standAt(ctx, admin, admin.username, SPAWN)
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now /)
    for (const zone of zones()) {
      const { from, to } = zone
      await ctx.command(admin, `/season zone add ${zone.id} box ${from.x} ${from.y} ${from.z} ${to.x} ${to.y} ${to.z}`, new RegExp(`^Added zone ${zone.id}: `))
    }
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 60 * SECOND)
    // A sync answers within ten seconds, so the minute goes by in steps.
    for (let ticks = 0; ticks < FRONTIER_TICKS; ticks += 100) {
      await ctx.sync(admin, [], 100)
    }
    await ctx.command(admin, '/season unlock homecoming', /^Homecoming! Every zone is now in its after-unlock mode\.$/)
    await ctx.sync(admin, [], 40)

    const spark = await ctx.command(admin, '/spark profiler stop --save-to-file', /(written to|saved)/i, 60 * SECOND)
    ctx.log(`spark: ${spark}`)
    const perf = await stopMeter(ctx, admin)
    ctx.log(`A43 meter: ${perf.line}`)

    // The meter saw the rollover, so a meter that measures nothing can't pass.
    assert(perf.ticks >= FRONTIER_TICKS, `the meter watched the whole stretch: ${perf.line}`)
    assert(perf.tasks > 0 && perf.listeners > 0, `the meter timed Seasons' tasks and listeners: ${perf.line}`)
    assert(perf.displayTicks > 0, `the meter timed displays being drawn: ${perf.line}`)
    assert(perf.average < AVERAGE_LIMIT, `Seasons adds under 1 ms a tick on average: ${perf.line}`)
    assert(perf.displayMax <= DISPLAY_LIMIT, `drawing displays never adds more than 5 ms to a tick: ${perf.line}`)
    const lines = seasonsLines(ctx)
    assert(lines.length <= LOG_LINE_LIMIT, `Seasons wrote ${lines.length} log lines, over ${LOG_LINE_LIMIT}:\n${lines.map((l) => `${l.level} ${l.text}`).join('\n')}`)
  }
}
