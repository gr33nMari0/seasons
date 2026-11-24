// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A81. /season spawn record keeps a past season's spawn, inside the old world too; the season in
// progress, a later one and a spot inside the Hall are refused; a two-way link from the hub to
// Season 1's spawn is refused before Homecoming and works after it, and its back link at Season
// 1's spawn takes a player to the hub with no bed near (M8 plan, Commands).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { inBox, HUB } from '../lib/hub.mjs'
import { FOLLOWED, WELCOME, link, setBlock } from '../lib/links.mjs'
import { hallToVisit, middle, standAt, waitNear } from '../lib/visits.mjs'
import { click } from '../lib/wand.mjs'
import { BASE, SPAWN, drawBase, forceload } from '../lib/zones.mjs'

const S1 = { x: BASE.x + 10, y: FLOOR, z: BASE.z }
const S1_BUTTON = { x: S1.x + 3, y: FLOOR, z: S1.z }
const HUB_BUTTON = { x: 10, y: FLOOR, z: 22 }

export default {
  name: 'a81-past-season-spawns',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await forceload(ctx, admin, SPAWN, 12)
    await forceload(ctx, admin, BASE, 40)
    await standAt(ctx, admin, ctx.adminName, SPAWN)
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await drawBase(ctx, admin)

    // Past seasons' spawns: one inside the locked base, one elsewhere.
    await ctx.command(admin, `/season spawn record 1 ${S1.x} ${S1.y} ${S1.z} 90`, /^Season 1's spawn is recorded at 510, -60, 500 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season spawn record 2 3100 -60 50', /^Season 2's spawn is recorded at 3100, -60, 50 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season spawn record 3 3100 -60 60', /^Season 3 isn't over yet: /)
    await ctx.command(admin, '/season spawn record 5 3100 -60 60', /^Season 5 isn't over yet: /)
    await ctx.command(admin, '/season spawn record 1 18 -60 21', /^Season 1's spawn can't be inside the Hall of Fame\.$/)
    await ctx.command(admin, '/season spawn show 1', /^Season 1's spawn: 510, -60, 500 in /)

    // A two-way link between the hub and Season 1's spawn.
    await setBlock(ctx, admin, HUB_BUTTON, 'minecraft:stone_button[face=floor]')
    await link(ctx, admin, `add hof-s1 from ${HUB_BUTTON.x} ${HUB_BUTTON.y} ${HUB_BUTTON.z} to season 1 --two-way`, [
      /^Link hof-s1 leads to Season 1's spawn, from the block at 10, -60, 22, clicked\.$/,
      /^Link hof-s1-back leads back to hof-s1\. /
    ])
    await setBlock(ctx, admin, S1_BUTTON, 'minecraft:stone_button[face=floor]')
    await link(ctx, admin, `trigger add hof-s1-back ${S1_BUTTON.x} ${S1_BUTTON.y} ${S1_BUTTON.z}`, /^Link hof-s1-back gains a trigger: /)

    // Refused before Homecoming.
    await standAt(ctx, admin, 'Visitor', { x: 12, y: FLOOR, z: 22 })
    await ctx.advance(admin, 6_000)
    const refused = ctx.waitForMessages(visitor, /^That leads into land locked until Homecoming\.$/)
    await click(ctx, admin, 'Visitor', HUB_BUTTON)
    await refused
    assert(inBox(HUB, await ctx.position(admin, 'Visitor')), 'the visitor stays in the hub')

    // After Homecoming it takes them to Season 1's spawn, and the back link brings them to the hub.
    await ctx.command(admin, '/season unlock homecoming', /Homecoming/)
    await ctx.advance(admin, 6_000)
    const followed = ctx.waitForMessages(visitor, FOLLOWED, 20_000)
    await click(ctx, admin, 'Visitor', HUB_BUTTON)
    await followed
    await waitNear(ctx, admin, 'Visitor', middle(S1), 1)
    await ctx.advance(admin, 6_000)
    const welcomed = ctx.waitForMessages(visitor, WELCOME)
    await click(ctx, admin, 'Visitor', S1_BUTTON)
    await welcomed
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Visitor')
      assert(Math.hypot(at.x - (HUB_BUTTON.x + 0.5), at.z - (HUB_BUTTON.z + 0.5)) <= 3, `beside the hub's button: ${JSON.stringify(at)}`)
    })
  }
}
