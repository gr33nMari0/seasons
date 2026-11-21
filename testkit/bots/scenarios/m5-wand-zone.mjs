// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A30 and A41 widened for the wand (questions 92, 156, 229): /season zone add <id> box with no
// corners takes the wand selection, in the selection's dimension, the Nether included; outside box
// takes its x and z; corners typed in still win; with no selection, or one left half-made by a
// corner in another dimension, it asks for corners as before. A box drawn that way around the
// spawn is refused, and one drawn away from it keeps a player out.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { middle, move, near, standAt } from '../lib/visits.mjs'
import { click, giveWand, select } from '../lib/wand.mjs'
import { LOCKED, forceload, launchFrontier, zoneList } from '../lib/zones.mjs'

const GIVE_CORNERS = (id, shape) => new RegExp(`^Give both corners: /season zone add ${id} ${shape} .*\\. Or select two corners with /hof wand first\\.$`)

export default {
  name: 'm5-wand-zone',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await giveWand(ctx, admin)

    // No selection yet.
    await ctx.command(admin, '/season zone add far-base box', GIVE_CORNERS('far-base', 'box'))

    // A box from the selection, which then keeps a player out.
    await forceload(ctx, admin, { x: 500, z: 500 }, 40)
    await select(ctx, admin, admin, { x: 480, y: -64, z: 480 }, { x: 520, y: 0, z: 520 })
    await ctx.command(admin, '/season zone add far-base box', /^Added zone far-base: a box from 480, -64, 480 to 520, 0, 520 in minecraft:overworld, block until Homecoming and off after\.$/)
    const outside = { x: 476, y: FLOOR, z: 500 }
    await standAt(ctx, admin, 'Pat', outside)
    const told = ctx.waitForMessages(pat, LOCKED('far-base'), 10 * SECOND, 'the refusal')
    await move(ctx, admin, 'Pat', middle({ x: 490, y: FLOOR, z: 500 }), 'walk')
    await told
    await ctx.sync(admin, [pat], 20)
    assert(near(await ctx.position(admin, 'Pat'), middle(outside), 2), 'Pat is kept out of the box drawn with the wand')

    // Corners typed in win over the selection.
    await ctx.command(admin, '/season zone add typed box 600 -64 600 610 0 610', /^Added zone typed: a box from 600, -64, 600 to 610, 0, 610 in minecraft:overworld, /)

    // A box around the spawn is refused (A41).
    await select(ctx, admin, admin, { x: 2990, y: -64, z: -10 }, { x: 3010, y: 0, z: 10 })
    await ctx.command(admin, '/season zone add around box', /^That zone would hold Season 3's spawn at 3000, -60, 0; the spawn must stay outside every zone\.$/)

    // In the Nether, the selection's dimension.
    await ctx.command(admin, '/execute in minecraft:the_nether run forceload add 80 80 120 120', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/execute in minecraft:the_nether run tp SeasonsAdmin 100 70 100', /^Teleported /)
    await select(ctx, admin, admin, { x: 90, y: 30, z: 90 }, { x: 110, y: 100, z: 110 }, /^Selected 90, 30, 90 to 110, 100, 110 in minecraft:the_nether: 21 × 71 × 21 blocks\.$/)
    await ctx.command(admin, '/season zone add nether-base box', /^Added zone nether-base: a box from 90, 30, 90 to 110, 100, 110 in minecraft:the_nether, /)

    // A corner in another dimension starts a new selection, so there are no two corners.
    await ctx.command(admin, '/execute in minecraft:overworld run tp SeasonsAdmin 3000.5 -60 0.5', /^Teleported /)
    const corner = ctx.waitForMessages(admin, /^Corner 2: 3005, -61, 5$/)
    await click(ctx, admin, 'SeasonsAdmin', { x: 3005, y: -61, z: 5 }, { sneak: true })
    await corner
    await ctx.command(admin, '/season zone add half box', GIVE_CORNERS('half', 'box'))

    // outside box takes the selection's x and z.
    await select(ctx, admin, admin, { x: 2900, y: -64, z: -100 }, { x: 3100, y: 0, z: 100 })
    await ctx.command(admin, '/season zone add edge outside box', /^Added zone edge: everywhere beyond a box from 2900, -100 to 3100, 100 in minecraft:overworld, block until Homecoming and off after\.$/)

    const zones = await zoneList(ctx, admin, ['far-base', 'typed', 'nether-base', 'edge'])
    ctx.log(JSON.stringify(zones))
    assert(!/around|half/.test(ctx.readFile('world/dimensions/minecraft/overworld/seasons/seasons/3.yml')), 'the refused zones aren\'t in the season file')
  }
}
