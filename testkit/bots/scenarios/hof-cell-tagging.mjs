// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Quick tagging (questions 254): /hof cell new <gallery> with no id makes c1, c2, c3 in turn, each
// taking the next wand selection as its bounds and the wand's figure, from the hub outwards, with
// every earlier cell's bounds left where they were.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { anchorMenu, cellInFile, giveWand, pickAnchor, select } from '../lib/wand.mjs'
import { forceload } from '../lib/zones.mjs'

const GROUND = FLOOR - 1

export default {
  name: 'hof-cell-tagging',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await forceload(ctx, admin, { x: 40, z: 30 }, 30)
    await ctx.command(admin, '/hof gallery create season-3 3', /^Created gallery season-3 for Season 3\.$/)
    await ctx.command(admin, `/hof gallery bounds season-3 20 ${GROUND} 20 60 -50 40`, /^Gallery season-3 now spans /)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 40, y: FLOOR, z: 38 })
    await giveWand(ctx, admin)

    const cells = [['c1', 22], ['c2', 30], ['c3', 38]]
    for (const [id, x] of cells) {
      await ctx.command(admin, '/hof cell new season-3', new RegExp(`^Created cell ${id} in gallery season-3\\.$`))
      await Promise.all([
        ctx.waitForMessages(admin, new RegExp(`^Cell season-3:${id} now spans ${x}, -60, 22 to ${x + 6}, -55, 28\\.$`)),
        select(ctx, admin, admin, { x, y: FLOOR, z: 22 }, { x: x + 6, y: FLOOR + 5, z: 28 })
      ])
      await anchorMenu(ctx, admin, admin, { x: x + 3, y: GROUND, z: 27 })
      await pickAnchor(ctx, admin, 'figure', new RegExp(`^Added a figure anchor to season-3:${id} at ${x + 3}, -60, 27\\.$`))
    }
    for (const [id, x] of cells) {
      const cell = cellInFile(ctx, id, 'season-3')
      assert(JSON.stringify(cell.bounds) === JSON.stringify({ min: [x, FLOOR, 22], max: [x + 6, FLOOR + 5, 28] }), `${id} keeps its 7 × 6 × 7 bounds: ${JSON.stringify(cell.bounds)}`)
      assert(cell.anchors.some((a) => a.type === 'figure'), `${id} has its figure`)
    }
  }
}
