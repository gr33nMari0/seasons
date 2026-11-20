// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server stops dead at one step of handing the dragon egg over (Runtime.halt, through the
// scenario hooks in docs/adr/0003) and starts again. Whatever the step, the egg ends up exactly
// once: off the portal and on the slayer's Returned page, since they are offline when Seasons
// finishes the job at startup.
import { assert } from './context.mjs'
import { carried } from './vault.mjs'
import { launchFrontier } from './zones.mjs'
import { EGG_WAIT, dragonInfo, hurt, toTheEnd, waitForDragon } from './dragon.mjs'

/** A two-session scenario for one crash point, armed for the slayer. */
export function eggCrashScenario (name, point) {
  return {
    name,
    sessions: [
      async (ctx) => {
        const admin = await ctx.admin()
        const pat = await ctx.join('Pat')
        await launchFrontier(ctx, admin)
        await toTheEnd(ctx, admin, ['Pat'])
        await waitForDragon(ctx, admin)
        await ctx.command(admin, `/scenario crash-at ${point} Pat`, /^Armed crash point /)
        const gone = ctx.disconnected(pat, EGG_WAIT)
        await hurt(ctx, admin, 'Pat', 1000, 'melee')
        await gone
        ctx.log(`the server halted at ${point}`)
      },
      async (ctx) => {
        const admin = await ctx.admin()
        await ctx.join('Pat')
        // The Returned page is page 0 of the vault.
        await ctx.eventually(async () => assert((await ctx.archive(admin, 'Pat', 0)).vault === 1, 'the egg is on the Returned page'), 30_000)
        assert(await carried(ctx, admin, 'Pat', 'dragon_egg') === 0, 'and not on Pat as well')
        assert((await dragonInfo(ctx, admin)).egg === 'none', 'nor on the portal')
        assert(!await ctx.holds(admin, 'entity @e[type=minecraft:item]'), 'nothing was dropped')
        await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
      }
    ]
  }
}
