// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A65. Galleries, cells, links, candidates, markers, zones, the spawn and the schedule can each be
// listed, shown, changed and removed; /hof cell list <gallery> free, /season players and
// /hof locate <player> answer; removing a gallery or cell that is owned is refused; every change is
// audited (questions 269 and 270).
import { assert } from '../lib/context.mjs'
import { GALLERY_FILE } from '../lib/wand.mjs'
import { HOME, hallToVisit, standAt } from '../lib/visits.mjs'
import { existsSync } from 'node:fs'
import { join } from 'node:path'

export default {
  name: 'a65-crud',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/hof cell assign season-2:c1 Visitor', /^Assigned season-2:c1 to Visitor/)

    // The Hall at a glance, its galleries and cells.
    await ctx.command(admin, '/hof list', [
      /^Hub: 8, -61, 20 to 19, -50, 30; Hall spawn 18, -60, 21\.$/,
      /^season-2: Season 2, 2 cells, 1 owned, 1 free\.$/
    ])
    await ctx.command(admin, '/hof gallery list', /^season-2: Season 2, 2 cells, 1 owned, 1 free\.$/)
    await ctx.command(admin, '/hof cell list season-2 free', /^Cell c2: free$/)
    await ctx.command(admin, '/hof cell list season-2 owned', /^Cell c1: Visitor$/)
    await ctx.command(admin, '/hof cell info season-2:c1', /^Cell season-2:c1: /)

    // Removing what is owned is refused; what isn't, goes.
    await ctx.command(admin, '/hof cell remove season-2:c1', /^Cell season-2:c1 is owned by Visitor for Season 2; unassign it first\.$/)
    await ctx.command(admin, '/hof gallery remove season-2', /^Gallery season-2 can't be removed while cell c1 is owned; unassign it first\.$/)
    await ctx.command(admin, '/hof cell new season-2 c3', /^Created cell c3 in gallery season-2\.$/)
    await ctx.command(admin, '/hof cell remove season-2:c3', /^Removed cell season-2:c3\.$/)
    await ctx.command(admin, '/hof gallery create season-9 9', /^Created gallery season-9 for Season 9\.$/)
    await ctx.command(admin, '/hof gallery info season-9', /^Gallery season-9/)
    await ctx.command(admin, '/hof gallery remove season-9', /^Removed gallery season-9\.$/)
    await ctx.eventually(async () => assert(!existsSync(join(ctx.serverDir, GALLERY_FILE('season-9'))), 'season-9.yml is gone'))

    // The hub, its exits and links.
    await ctx.command(admin, '/hof hall info', [/^Hub: 8, -61, 20 to 19, -50, 30; Hall spawn 18, -60, 21\.$/, /^Exits: 0\. Links: 0\.$/])
    await ctx.command(admin, '/hof hall exit list', /^The Hall has no exits\.$/)
    await ctx.command(admin, '/hof hall exit 10 -60 22', /^Added a Hall exit at 10, -60, 22\.$/)
    await ctx.command(admin, '/hof hall exit list', /^Exit at 10, -60, 22$/)
    await ctx.command(admin, '/hof link add home hall 110 -61 100', /^Link home at 110, -61, 100 leads /)
    await ctx.command(admin, '/hof link info home', /^Link home at 110, -61, 100: to the Hall spawn$/)
    await ctx.command(admin, '/hof link move home 111 -61 100', /^Link home is now at 111, -61, 100\.$/)
    await ctx.command(admin, '/hof link list', /^Link home at 111, -61, 100: to the Hall spawn$/)
    // Every change is audited.
    await ctx.command(admin, '/season audit', [/ cell-remove season-2:c3 /, / gallery-remove season-9 /, / link-move home /])

    // Zones: shown, redrawn, removed.
    await ctx.command(admin, '/season zone add base box 200 -64 200 210 -50 210', /^Added zone base: /)
    await ctx.command(admin, '/season zone info base', /^base: a box from 200, -64, 200 to 210, -50, 210 /)
    await ctx.command(admin, '/season zone add base box 200 -64 200 220 -50 220', /^There's already a zone called base\./)
    await ctx.command(admin, '/season zone add base box 200 -64 200 220 -50 220 --replace', /^Redrew zone base: a box from 200, -64, 200 to 220, -50, 220/)
    await ctx.command(admin, '/season zone remove base', /^Removed zone base\.$/)

    // The spawn, candidates and waypoints.
    await ctx.command(admin, '/season spawn show', /^Season 3 has no spawn yet\./)
    await ctx.command(admin, '/season spawn set at 300 -60 300', /^Season 3's spawn is now 300, -60, 300/)
    await ctx.command(admin, '/season spawn show', /^Season 3's spawn: 300, -60, 300 in minecraft:overworld, 424 blocks from 0, 0\.$/)
    await ctx.command(admin, '/season spawn clear', /^Season 3 has no spawn now; \/season launch waits for one\.$/)
    await ctx.command(admin, '/season spawn candidate add north 0 -60 400', /^Added spawn candidate north/)
    await ctx.command(admin, '/season spawn candidate list', /^- north: 0, -60, 400/)
    await ctx.command(admin, '/season marker add hubs Big Tree --at 50 -60 50', /^Added waypoint Big Tree to hubs at 50, -60, 50/)
    await ctx.command(admin, '/season marker move hubs Big Tree --at 51 -60 50', /^Moved waypoint Big Tree of hubs to 51, -60, 50/)
    await ctx.command(admin, '/season marker list', /^hubs: Big Tree at 51, -60, 50 in minecraft:overworld$/)
    await ctx.command(admin, '/season marker remove hubs Big Tree', /^Removed waypoint Big Tree from hubs\.$/)

    // The planned end.
    await ctx.command(admin, '/season schedule show', /^No planned end set\. \/season schedule end sets one\.$/)
    await ctx.command(admin, `/season schedule end ${ctx.isoIn(8 * 24 * 3600 * 1000)}`, /^Season 2 now ends at /)
    await ctx.command(admin, '/season schedule show', /^Planned end: /)
    await ctx.command(admin, '/season schedule clear', /^Season 2 has no planned end now\.$/)
    await ctx.command(admin, '/season schedule show', /^No planned end set\./)
    // These changes are audited too: the spawn, candidates, waypoints and the schedule.
    await ctx.command(admin, '/season audit', [/ schedule-clear season-2 /, / schedule-set season-2 /, / config seasons\/3\.yml spawn /, / config seasons\/3\.yml spawnCandidates\.north /])

    // Players and seasons.
    await ctx.command(admin, '/season players', [/^Visitor: played Season 2; not archived yet; cell season-2:c1$/, new RegExp(`^${me}: played Season 2; not archived yet; no cell$`)])
    await ctx.command(admin, '/season players 2 online', /^Visitor: /)
    await ctx.command(admin, '/season list', /^Season 2 \(on record, Active, Open stage\): no spawn; galleries season-2; 0 archived\.$/)

    // Where things are.
    await ctx.command(admin, '/hof locate Visitor', /^Visitor's cell season-2:c1: 20, -61, 22 to 24, -54, 30\.$/)
    await ctx.command(admin, '/hof locate season-2', /^Gallery season-2: 20, -61, 20 to 38, -50, 30\.$/)
    await ctx.command(admin, '/hof locate home', /^Link home: 111, -61, 100\.$/)
    await standAt(ctx, admin, me, { x: 12, y: -60, z: 25 })
    await ctx.command(admin, '/hof locate', /^You're in the hub\.$/)
    await standAt(ctx, admin, me, HOME)
    await ctx.command(admin, '/hof locate', /^You're outside the Hall\.$/)
  }
}
