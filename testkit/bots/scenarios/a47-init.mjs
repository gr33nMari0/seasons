// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A47 (M1 part 2 adds archiving into Season 2; the Season 2 gallery and the untouched imported
// Season 1 cells come with the Hall in part 3). On a server that has never run Seasons, /season
// init 2 records Season 2 as the season in progress, and /season end then archives everyone into
// Season 2. A second /season init is refused.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, giveGear } from '../lib/gear.mjs'

export default {
  name: 'a47-init',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Veteran')
    await ctx.command(admin, '/season status', /^No season recorded yet\b/)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 as the season in progress\.$/)
    await ctx.command(admin, '/season status', [/^Season 2$/, /^Phase: Active, Open stage$/])
    await ctx.command(admin, '/season init 3', /^Season 2 is already recorded\b/)
    await ctx.command(admin, '/season init 2', /^Season 2 is already recorded\b/)

    await giveGear(ctx, admin, 'Veteran')
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now: archiving 2 online players\.$/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    const archive = await ctx.archive(admin, 'Veteran', 2)
    assert(archive.present && archive.items === GEAR_ITEMS && archive.archivedThrough === 2, `archived into Season 2: ${JSON.stringify(archive)}`)
    assert(!(await ctx.archive(admin, 'Veteran', 1)).present, 'nothing is archived into Season 1')
  }
}
