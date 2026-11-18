// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A46 (second half). /season launch before decorating.afterArchive runs out locks the cell at once.
import { assert } from '../lib/context.mjs'
import { buildGallery, cellOf, place } from '../lib/hall.mjs'

export default {
  name: 'a46-launch-locks',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 3 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    // 15 minutes by default: still open.
    await ctx.eventually(async () => assert(await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 4 }), 'the owner can still build'))

    await ctx.command(admin, '/tp SeasonsAdmin 300 -60 300', /^Teleported /)
    await ctx.eventually(async () => assert(Math.floor((await ctx.position(admin, 'SeasonsAdmin')).x) === 300, 'admin moved'))
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now /)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    assert(!await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 5 }), 'launch locked the cell at once')
    // The new season's play goes on outside the Hall.
    assert(await place(ctx, admin, 'Owner', { x: 310, y: -60, z: 300 }), 'building outside the Hall still works')
  }
}
