// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// The time an owner has to arrange their cell after archiving survives a restart, and
// /season decorate close ends it early.
import { assert } from '../lib/context.mjs'
import { buildGallery, cellOf, inside, place } from '../lib/hall.mjs'

export default {
  name: 'm1-decorating-restart',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.join('Owner')
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      const cells = await buildGallery(ctx, admin, { cells: 2 })
      await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
      const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
      await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
      await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
      await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Owner')), 'the owner is in their cell'))
      assert(await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 4 }), 'the owner builds after archiving')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.join('Owner')
      const cells = sessionOneCells()
      const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
      await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Owner')), 'the owner logged back in inside their cell'))
      assert(await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 5 }), 'the cell is still open after the restart')
      await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 is closed\.$/)
      assert(!await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 6 }), 'closing locks every cell')
    }
  ]
}

/** The cells buildGallery made in session 1, without making them again. */
function sessionOneCells () {
  return ['c1', 'c2'].map((id, i) => {
    const x0 = 20 + 6 * i
    return { id, key: `season-2:${id}`, x0, x1: x0 + 4, z0: 22, z1: 30, figure: { x: x0 + 2, y: -60, z: 28 }, entry: { x: x0 + 2, y: -60, z: 23 } }
  })
}
