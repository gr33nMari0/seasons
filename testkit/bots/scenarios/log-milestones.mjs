// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server log through a season's turn (docs/questions.md 295): each milestone is one INFO line
// (the end, one summary for everyone archived, the launch, Homecoming), never a line per player,
// and the whole turn stays within a small budget of lines, with no warnings or errors.
import { assert } from '../lib/context.mjs'
import { seasonsLines } from '../lib/log.mjs'
import { buildGallery } from '../lib/hall.mjs'
import { endSeason2 } from '../lib/zones.mjs'

const NAMES = ['Lou', 'Mo', 'Ned']

export default {
  name: 'log-milestones',
  async run (ctx) {
    const admin = await ctx.admin()
    const players = []
    for (const name of NAMES) players.push(await ctx.join(name))
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    // A cell for everyone, so nobody is left without one.
    await buildGallery(ctx, admin, { cells: NAMES.length + 2 })
    await endSeason2(ctx, admin)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await ctx.command(admin, '/season unlock homecoming', /^Homecoming! Every zone is now in its after-unlock mode\.$/)

    const expect = [
      [new RegExp(`^Season 2 ending now, started by ${admin.username}: archiving 4 online players$`), 'the end'],
      [/^Archived 4 players into Season 2 in [\d.]+ s: \d+ item stacks?; nobody is waiting to be archived$/, 'the archive summary'],
      [/^Season 3 launched: 4 players sent to the new spawn$/, 'the launch'],
      [new RegExp(`^Homecoming, by ${admin.username}: every zone is now in its after-unlock mode$`), 'Homecoming']
    ]
    for (const [pattern, what] of expect) {
      await ctx.eventually(async () => assert(seasonsLines(ctx).some((l) => l.level === 'INFO' && pattern.test(l.text)), `${what} is logged:\n${seasonsLines(ctx).map((l) => l.text).join('\n')}`))
    }
    const lines = seasonsLines(ctx)
    const named = lines.filter((l) => NAMES.some((n) => l.text.includes(n)))
    assert(named.length === 0, `no line per player:\n${named.map((l) => l.text).join('\n')}`)
    const loud = lines.filter((l) => l.level !== 'INFO')
    assert(loud.length === 0, `a clean turn warns of nothing:\n${loud.map((l) => `${l.level} ${l.text}`).join('\n')}`)
    assert(lines.length <= 15, `the turn takes at most 15 lines, not ${lines.length}:\n${lines.map((l) => l.text).join('\n')}`)
  }
}
