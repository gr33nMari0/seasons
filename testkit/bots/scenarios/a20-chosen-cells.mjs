// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A20, widened: a cell assigned before the decorating window opens is kept by its owner, and an
// ordered gallery hands out the rest in the order its cells were tagged (from the hub outwards),
// to players by name (questions 38).
import { assert } from '../lib/context.mjs'
import { buildGallery, owners } from '../lib/hall.mjs'
import { GALLERY_FILE } from '../lib/wand.mjs'

export default {
  name: 'a20-chosen-cells',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Leader')
    await ctx.join('Alex')
    await ctx.join('Sam')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 5 })
    ctx.writeFile(GALLERY_FILE(), ctx.readFile(GALLERY_FILE()).replace(/^cells:/m, 'assignment: ordered\ncells:'))
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // The leader's cell, nearest the hub, is chosen before the window.
    await ctx.command(admin, '/hof cell assign season-2:c1 Leader', /^Assigned season-2:c1 to Leader for Season 2\.$/)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 4 players have cells\.$/)
    const dealt = await owners(ctx, admin, 'season-2', cells)
    assert(JSON.stringify(dealt) === JSON.stringify({ c1: 'Leader', c2: 'Alex', c3: 'Sam', c4: 'SeasonsAdmin', c5: null }),
      `the leader keeps c1 and the rest are dealt in order: ${JSON.stringify(dealt)}`)
  }
}
