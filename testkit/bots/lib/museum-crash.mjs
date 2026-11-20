// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server stops dead at one step of /museum add (Runtime.halt, through the scenario hooks in
// docs/adr/0003) and starts again. Whatever the step, the heavy core ends up exactly once: on the
// plinth or with the op, never both and never neither.
import { assert } from './context.mjs'
import { carried } from './vault.mjs'
import { PLINTHS, shownItem, writeMuseum } from './museum.mjs'
import { forceload } from './zones.mjs'

/**
 * A two-session scenario for one crash point.
 *
 * @param stored whether the heavy core is the museum's after recovery, rather than the op's
 */
export function museumCrashScenario (name, point, stored) {
  return {
    name,
    sessions: [
      async (ctx) => {
        const admin = await ctx.admin()
        await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
        await writeMuseum(ctx, admin)
        await ctx.command(admin, '/clear SeasonsAdmin', /./)
        await ctx.command(admin, '/give SeasonsAdmin minecraft:heavy_core', /^Gave 1 /)
        await ctx.command(admin, `/scenario crash-at ${point} SeasonsAdmin`, /^Armed crash point /)
        const gone = ctx.disconnected(admin)
        admin.chat('/museum add museum:core')
        await gone
        ctx.log(`the server halted at ${point}`)
      },
      async (ctx) => {
        const admin = await ctx.admin()
        await forceload(ctx, admin, { x: 207, y: -60, z: 305 }, 16)
        // Recovery waits for the op, whose saved inventory says whether the item moved.
        await ctx.eventually(async () => assert((await ctx.archive(admin, 'SeasonsAdmin', 2)).pending === 0, 'the museum change is settled'), 20_000)
        await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
        const onOp = await carried(ctx, admin, 'SeasonsAdmin', 'heavy_core')
        assert(onOp === (stored ? 0 : 1), `the heavy core is ${stored ? "the museum's" : "the op's"}: ${onOp} on the op`)
        await ctx.eventually(async () => assert(await shownItem(ctx, admin, PLINTHS.core) === (stored ? 'minecraft:heavy_core' : null), `the plinth ${stored ? 'shows' : "doesn't show"} it`))
        await ctx.command(admin, '/museum remove museum:core', stored ? /^Took minecraft:heavy_core from museum:core\.$/ : /^museum:core holds nothing\.$/)
        assert(await carried(ctx, admin, 'SeasonsAdmin', 'heavy_core') === 1, 'the op has it in the end')
      }
    ]
  }
}
