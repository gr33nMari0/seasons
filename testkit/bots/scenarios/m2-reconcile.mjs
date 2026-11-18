// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Reconciliation. When a Hall chunk loads, each cell's displays are checked against its records:
// a killed figure is drawn again, a stray tagged display is removed, and each repair is audited.
// /hof reconcile does the same on demand, and puts back gear changed on a figure.
import { assert } from '../lib/context.mjs'
import { FLOOR, at, buildSeasonOneCell, selectorData } from '../lib/hall.mjs'
import { audit, reloadChunks } from '../lib/protection.mjs'

// Far from spawn, so the chunks unload once they aren't forceloaded.
const X = 1000
const Z = 1000

export default {
  name: 'm2-reconcile',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const built = await buildSeasonOneCell(ctx, admin, { x: X, z: Z, head: 'Owner', chests: 1 })
    await ctx.command(admin, `/hof gallery create season-1 1`, /^Created gallery season-1 for Season 1\.$/)
    await ctx.command(admin, `/hof gallery bounds season-1 ${X - 2} -61 ${Z - 2} ${X + 18} -50 ${Z + 12}`, /^Gallery season-1 now spans /)
    await ctx.command(admin, '/hof cell new season-1 owner', /^Created cell owner in gallery season-1\.$/)
    await ctx.command(admin, `/hof cell bounds season-1:owner ${X - 1} -61 ${Z - 1} ${X + 17} -52 ${Z + 11}`, /^Cell season-1:owner now spans /)
    await ctx.command(admin, '/hof import season-1', /^Imported 1 cell from season-1; 0 skipped\.$/, 20_000)
    const figure = at('minecraft:mannequin', built.stand)
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${figure}`), 'the import drew the figure'))

    await ctx.command(admin, '/kill @e[type=minecraft:mannequin]', /^Killed /)
    await ctx.command(admin, `/summon minecraft:text_display ${X + 3} ${FLOOR + 1} ${Z + 3} {Tags:["stray"],BukkitValues:{"seasons:display":"season-1/owner/7/plaque"}}`, /^Summoned /)
    assert(!await ctx.holds(admin, `entity ${figure}`), 'the figure is gone')

    await reloadChunks(ctx, admin, { x: X, z: Z }, { x: X + 16, z: Z + 10 }, { x: X, y: FLOOR, z: Z })
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${figure}`), 'the figure is back once its chunk loads'))
    await ctx.eventually(async () => assert(!await ctx.holds(admin, 'entity @e[tag=stray]'), 'the stray display is removed'))

    await ctx.command(admin, `/item replace entity ${figure} armor.chest with minecraft:dirt`, /^Replaced /)
    await ctx.command(admin, '/hof reconcile season-1', /^Reconciled season-1: 1 repair\.$/, 20_000)
    await ctx.eventually(async () => {
      const chest = await selectorData(ctx, admin, figure, 'equipment.chest.id')
      assert(chest === '"minecraft:iron_chestplate"', `the figure wears its own chestplate again: ${chest}`)
    })
    await ctx.command(admin, '/hof reconcile season-1', /^Reconciled season-1: 0 repairs\.$/, 20_000)

    const repairs = audit(ctx).filter((entry) => entry.action === 'reconcile' && entry.target === 'season-1:owner')
    for (const what of [/drew the missing figure/, /removed a stray display season-1\/owner\/7\/plaque/, /fixed the figure's gear/]) {
      assert(repairs.some((entry) => what.test(entry.details)), `the repair "${what.source}" is audited: ${JSON.stringify(repairs)}`)
    }
  }
}
