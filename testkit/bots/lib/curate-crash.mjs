// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server stops dead at one step of a curator's Take (Runtime.halt, through the scenario hooks
// in docs/adr/0003) and starts again. Whatever the step, the sword ends up exactly once: in the
// archive or with the curator, never both and never neither.
import { assert } from './context.mjs'
import { GEAR_ITEMS, archiveWithGear, choose, openCurate, pick } from './curate.mjs'
import { carried } from './vault.mjs'

/**
 * A two-session scenario for one crash point.
 *
 * @param taken whether the sword is the curator's after recovery, rather than the archive's
 */
export function curateCrashScenario (name, point, taken) {
  return {
    name,
    sessions: [
      async (ctx) => {
        const admin = await ctx.admin()
        await ctx.join('Owner')
        await archiveWithGear(ctx, admin, ['Owner'])
        await ctx.command(admin, '/clear SeasonsAdmin', /./)
        await openCurate(ctx, admin, 'Owner')
        await pick(ctx, admin, 'diamond_sword')
        await choose(ctx, admin, 'take')
        await ctx.command(admin, `/scenario crash-at ${point} SeasonsAdmin`, /^Armed crash point /)
        const gone = ctx.disconnected(admin)
        admin.chat('Crash test')
        await gone
        ctx.log(`the server halted at ${point}`)
      },
      async (ctx) => {
        const admin = await ctx.admin()
        // Recovery waits for the curator, whose saved inventory says whether the sword moved.
        await ctx.eventually(async () => assert((await ctx.archive(admin, 'SeasonsAdmin', 2)).pending === 0, 'the curation is settled'), 20_000)
        await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
        const withCurator = await carried(ctx, admin, 'SeasonsAdmin', 'diamond_sword')
        const archived = (await ctx.archive(admin, 'Owner', 2)).items
        assert(withCurator === (taken ? 1 : 0), `the sword is ${taken ? "the curator's" : "the archive's"}: ${withCurator} with the curator`)
        assert(archived === (taken ? GEAR_ITEMS - 1 : GEAR_ITEMS), `the archive holds ${archived} items`)
        await ctx.command(admin, '/season audit Owner', /curate take Owner \(recovered\)/)
      }
    ]
  }
}
