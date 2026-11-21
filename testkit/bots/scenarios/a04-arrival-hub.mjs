// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A4, widened: with archive.arrival: hub, archiving at season end sends everyone to the Hall spawn
// instead of their cell, still with an empty inventory (questions 257).
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, giveGear } from '../lib/gear.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { hallToVisit, middle, waitNear } from '../lib/visits.mjs'

export default {
  name: 'a04-arrival-hub',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Alex')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/season config set archive.arrival hub', /^Set archive\.arrival to hub in config\.yml \(it was cell\)\.$/)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    await giveGear(ctx, admin, 'Alex')
    assert(await ctx.itemCount(admin, 'Alex') === GEAR_ITEMS, 'Alex carries the gear before the end')

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\. Welcome to the Intermission\.$/, 20_000)
    await waitNear(ctx, admin, 'Alex', middle(HALL_SPAWN))
    assert(await ctx.itemCount(admin, 'Alex') === 0, 'Alex carries nothing after the end')
  }
}
