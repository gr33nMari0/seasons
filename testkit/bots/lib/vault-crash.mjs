// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server stops dead at one step of a vault withdrawal (Runtime.halt, through the scenario
// hooks in docs/adr/0003) and starts again. Whatever the step, the emeralds end up exactly once:
// in the vault or on the player, never both and never neither.
import { assert } from './context.mjs'
import { carried, fillEnderChest, openVault, slotOf } from './vault.mjs'
import { endSeason2 } from './zones.mjs'

/**
 * A two-session scenario for one crash point.
 *
 * @param point the step the server halts after, as the hooks name it
 * @param taken whether the emeralds are Pat's after recovery, rather than back in the vault
 */
export function vaultCrashScenario (name, point, taken) {
  return {
    name,
    sessions: [
      async (ctx) => {
        const admin = await ctx.admin()
        const pat = await ctx.join('Pat')
        await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
        await fillEnderChest(ctx, admin, 'Pat')
        await endSeason2(ctx, admin)
        await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
        await ctx.command(admin, '/season unlock homecoming', /^Homecoming! /)
        const window = await openVault(ctx, pat, /^Click an item to take it\.$/)
        await ctx.command(admin, `/scenario crash-at ${point} Pat`, /^Armed crash point /)
        const gone = ctx.disconnected(pat)
        // The server halts mid-click, so the click itself may never be answered.
        pat.clickWindow(slotOf(window, 'emerald'), 0, 0).catch(() => {})
        await gone
        ctx.log(`the server halted at ${point}`)
      },
      async (ctx) => {
        const admin = await ctx.admin()
        await ctx.join('Pat')
        await ctx.eventually(async () => assert((await ctx.archive(admin, 'Pat', 2)).pending === 0, 'the withdrawal is settled'), 20_000)
        const inVault = (await ctx.archive(admin, 'Pat', 2)).vault
        const onPat = await carried(ctx, admin, 'Pat', 'emerald')
        assert(onPat === (taken ? 5 : 0), `the emeralds are ${taken ? "Pat's" : 'in the vault'}: ${onPat} on Pat, vault holds ${inVault}`)
        assert(inVault === (taken ? 2 : 7), `the vault holds ${taken ? 'the rest' : 'everything'}: ${inVault}`)
        assert(!await ctx.holds(admin, 'entity @e[type=item]'), 'nothing was dropped')
        await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
      }
    ]
  }
}
