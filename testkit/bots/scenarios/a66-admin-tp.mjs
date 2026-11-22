// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A66. /hof tp to the hub, a gallery, a cell, a player's cell and a link, and /season tp to a
// candidate, go there with no bed or entry checks; /hof leave returns from the Hall; out of the Hall
// during the Intermission only with seasons.hof.roam; into a locked zone refused without the zone
// bypass (questions 271).
import { FLOOR, cellOf } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { HOME, hallToVisit, inGallery, middle, standAt, waitNear } from '../lib/visits.mjs'
import { assert } from '../lib/context.mjs'

const THERE = /^You're there\.$/
const WEST = { x: 60, y: FLOOR, z: 100 }
const LINK = { x: 110, y: FLOOR - 1, z: 100 }

export default {
  name: 'a66-admin-tp',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    await ctx.join('Visitor')
    const cells = await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/hof cell assign season-2:c2 Visitor', /^Assigned season-2:c2 to Visitor/)
    await ctx.command(admin, `/hof link add home hall ${LINK.x} ${LINK.y} ${LINK.z}`, /^Link home at /)

    // From outside, with a monster beside and no bed: into the Hall, and back with /hof leave.
    await standAt(ctx, admin, me, HOME)
    await ctx.command(admin, `/summon minecraft:zombie ${HOME.x - 2} ${FLOOR} ${HOME.z} {NoAI:1b,Tags:["a66"]}`, /^Summoned /)
    await ctx.command(admin, '/hof tp hub', THERE)
    await waitNear(ctx, admin, me, middle(HALL_SPAWN))
    await ctx.command(admin, '/kill @e[tag=a66]', /^Killed /)
    await ctx.command(admin, '/hof leave', /^Back where you left\.$/)
    await waitNear(ctx, admin, me, middle(HOME))

    // A gallery, a cell, a player's cell, a link.
    await ctx.command(admin, '/hof tp gallery season-2', THERE)
    await waitNear(ctx, admin, me, { x: 21.5, y: FLOOR, z: 21.5 })
    await ctx.command(admin, '/hof tp cell season-2:c1', THERE)
    await waitNear(ctx, admin, me, middle(cells[0].entry))
    const visitors = await cellOf(ctx, admin, 'season-2', cells, 'Visitor')
    await ctx.command(admin, '/hof tp player Visitor', THERE)
    await waitNear(ctx, admin, me, middle(visitors.entry))
    // On top of the link's block, which counts as already stood on, so it doesn't fire.
    await ctx.command(admin, '/hof tp link home', THERE)
    await waitNear(ctx, admin, me, middle({ x: LINK.x, y: LINK.y + 1, z: LINK.z }), 1)
    await ctx.sync(admin, [], 20)
    assert(!inGallery(await ctx.position(admin, me)), 'standing on the link took nobody anywhere')

    // A candidate, and a candidate inside a zone that is locked now.
    await standAt(ctx, admin, me, WEST)
    await ctx.command(admin, '/season spawn candidate add west', /^Added spawn candidate west /)
    await standAt(ctx, admin, me, HOME)
    await ctx.command(admin, '/season tp candidate west', THERE)
    await waitNear(ctx, admin, me, middle(WEST))

    // During the Intermission: out of the Hall only with roam, which ops hold by default.
    await ctx.command(admin, '/season spawn set at 300 -60 300', /^Season 3's spawn is now /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.eventually(async () => assert(inGallery(await ctx.position(admin, me)), 'the op is in the Hall'))
    await ctx.command(admin, '/season tp candidate west', THERE)
    await waitNear(ctx, admin, me, middle(WEST))
  }
}
