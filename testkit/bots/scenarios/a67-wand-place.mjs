// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A67. In place mode the wand's corners are the blocks against the faces clicked; swap-hands
// toggles the mode and the action bar shows it; /hof sel pos1 and pos2 set corners by typed or
// relative position (questions 272 and 273).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { giveWand, select } from '../lib/wand.mjs'
import { HOME, hallToVisit, standAt } from '../lib/visits.mjs'

export default {
  name: 'a67-wand-place',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    await hallToVisit(ctx, admin, { sealed: null })
    await giveWand(ctx, admin)
    await ctx.waitForMessages(admin, /^Wand \(block mode\): a selection only$/)

    // Block mode: the blocks clicked, the floor itself.
    await select(ctx, admin, admin, { x: 40, y: FLOOR - 1, z: 40 }, { x: 42, y: FLOOR - 1, z: 42 },
      /^Selected 40, -61, 40 to 42, -61, 42 in minecraft:overworld: 3 × 1 × 3 blocks\.$/)

    // Place mode: the blocks against the faces clicked, the air on top of the floor.
    await ctx.command(admin, '/hof wand mode place', /^The wand's corners now take the block against the face you click\.$/)
    await ctx.waitForMessages(admin, /^Wand \(place mode\): a selection only$/)
    await select(ctx, admin, admin, { x: 40, y: FLOOR - 1, z: 40 }, { x: 42, y: FLOOR - 1, z: 42 },
      /^Selected 40, -60, 40 to 42, -60, 42 in minecraft:overworld: 3 × 1 × 3 blocks\.$/)

    // Swap-hands toggles it back, and nothing swaps.
    const toggled = ctx.waitForMessages(admin, /^The wand's corners now take the block you click\.$/)
    await ctx.command(admin, `/scenario swap-hands ${me}`, /^Swapped hands for /)
    await toggled
    assert(admin.heldItem?.name === 'blaze_rod', `the wand stayed in the main hand: ${admin.heldItem?.name}`)

    // The action bar says which cell the next selection sets.
    await ctx.command(admin, '/hof cell new season-2 c3', /^Created cell c3 in gallery season-2\.$/)
    await ctx.waitForMessages(admin, /^Wand \(block mode\): next selection sets season-2:c3's bounds( · figure .*)?$/)

    // Corners by command, typed or relative; the selection then sets c3's bounds.
    await standAt(ctx, admin, me, { x: 32, y: FLOOR, z: 22 })
    await ctx.command(admin, '/hof sel pos1 ~ ~-1 ~', /^Corner 1: 32, -61, 22$/)
    await ctx.command(admin, '/hof sel pos2 ~4 ~6 ~8', [/^Selected 32, -61, 22 to 36, -54, 30 /, /^Cell season-2:c3 now spans 32, -61, 22 to 36, -54, 30\./])
    await ctx.command(admin, '/hof sel info', /^Selection: 32, -61, 22 to 36, -54, 30 in minecraft:overworld: 5 × 8 × 9 blocks\.$/)
    await ctx.command(admin, '/hof sel clear', /^Selection cleared\.$/)
    await ctx.command(admin, '/hof sel info', /^No selection\. /)
    await standAt(ctx, admin, me, HOME)
    await ctx.command(admin, '/hof sel pos1', /^Corner 1: 100, -60, 100$/)
  }
}
