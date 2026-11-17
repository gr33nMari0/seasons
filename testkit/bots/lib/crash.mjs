// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A7: the server stops dead at one step of archiving (Runtime.halt, through the scenario hooks in
// docs/adr/0003) and starts again. Whatever the step, every item ends up exactly once: in the
// archive or on the player, never both and never neither.
import { assert } from './context.mjs'
import { ENDER_ITEMS, GEAR_ITEMS, expectEmpty, giveGear } from './gear.mjs'

/**
 * A two-session scenario for one crash point.
 *
 * @param point the step the server halts after, as the hooks name it
 * @param pendingAfterRestart how many of Casey's operations wait for recovery after the restart
 */
export function crashScenario (name, point, pendingAfterRestart) {
  return {
    name,
    sessions: [
      async (ctx) => {
        const admin = await ctx.admin()
        const victim = await ctx.join('Casey')
        await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
        await giveGear(ctx, admin, 'Casey')
        assert(await ctx.itemCount(admin, 'Casey') === GEAR_ITEMS, 'Casey carries the gear')
        await ctx.command(admin, `/scenario crash-at ${point} Casey`, /^Armed crash point /)
        const gone = ctx.disconnected(victim)
        admin.chat('/season end --now')
        await gone
        ctx.log(`the server halted at ${point}`)
      },
      async (ctx) => {
        const admin = await ctx.admin()
        const before = await ctx.archive(admin, 'Casey', 2)
        assert(before.pending === pendingAfterRestart, `${pendingAfterRestart} operation(s) wait for Casey: ${JSON.stringify(before)}`)
        await ctx.join('Casey')
        await ctx.eventually(async () => {
          const archive = await ctx.archive(admin, 'Casey', 2)
          assert(archive.archivedThrough === 2, `Casey is archived after the restart: ${JSON.stringify(archive)}`)
        }, 20_000)
        await expectEmpty(ctx, admin, 'Casey', assert)
        const archive = await ctx.archive(admin, 'Casey', 2)
        assert(archive.items === GEAR_ITEMS, `every item is in the archive exactly once: ${JSON.stringify(archive)}`)
        assert(archive.vault === ENDER_ITEMS, `the ender chest is in the vault exactly once: ${JSON.stringify(archive)}`)
        assert(await ctx.holds(admin, 'entity @e[type=item]') === false, 'nothing was dropped')
        await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
      }
    ]
  }
}
