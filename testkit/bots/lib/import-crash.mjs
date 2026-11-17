// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server stops dead at one step of /hof import (Runtime.halt, through the scenario hooks in
// docs/adr/0003) and starts again. Recovery finishes the import at startup: the record holds every
// item, the cell holds none, and the figure stands.
import { assert } from './context.mjs'
import { at, blockItems, buildSeasonOneCell } from './hall.mjs'

export function importCrashScenario (name, point) {
  return {
    name,
    sessions: [
      async (ctx) => {
        const admin = await ctx.admin()
        await ctx.join('Owner')
        await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
        await buildSeasonOneCell(ctx, admin, { x: 60, z: 40, head: 'Owner', chests: 2 })
        await ctx.command(admin, '/hof gallery create season-1 1', /^Created gallery season-1 /)
        await ctx.command(admin, '/hof gallery bounds season-1 58 -61 38 76 -50 50', /^Gallery season-1 now spans /)
        await ctx.command(admin, '/hof cell new season-1 owner', /^Created cell owner /)
        await ctx.command(admin, '/hof cell bounds season-1:owner 59 -61 39 75 -52 49', /^Cell season-1:owner now spans /)
        // The cell was built long before the import: on disk, as a halt keeps only what is saved.
        await ctx.command(admin, '/save-all flush', /^Saved the game/)
        await ctx.command(admin, `/scenario crash-at ${point} Owner`, /^Armed crash point /)
        const gone = ctx.disconnected(admin)
        admin.chat('/hof import season-1')
        await gone
        ctx.log(`the server halted at ${point}`)
      },
      async (ctx) => {
        const admin = await ctx.admin()
        const built = { stand: { x: 65, y: -60, z: 44 }, chests: [{ x: 62, y: -60, z: 47 }, { x: 65, y: -60, z: 47 }], frame: { x: 61, y: -59, z: 47 } }
        await ctx.eventually(async () => {
          const archive = await ctx.archive(admin, 'Owner', 1)
          assert(archive.present && archive.pending === 0, `the import finished at startup: ${JSON.stringify(archive)}`)
        }, 20_000)
        const archive = await ctx.archive(admin, 'Owner', 1)
        assert(archive.stacks === 2 * 3 + 5 + 1 && archive.pages === 3, `every stack is in the record once: ${JSON.stringify(archive)}`)
        for (const chest of built.chests) {
          assert(await blockItems(ctx, admin, chest) === null, 'the chest is empty')
        }
        assert(!await ctx.holds(admin, `entity ${at('minecraft:armor_stand', built.stand)}`), 'the stand is gone')
        assert(!await ctx.holds(admin, `entity ${at('minecraft:item_frame', built.frame)}`), 'the frame is gone')
        await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${at('minecraft:mannequin', built.stand)}`), 'the figure stands'))
        assert(!await ctx.holds(admin, 'entity @e[type=item]'), 'nothing was dropped')
        await ctx.command(admin, '/hof cell info season-1:owner', /^Cell season-1:owner: owner Owner for Season 1; anchors: figure 1, archive 2, trophy 1$/)
        await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
      }
    ]
  }
}
