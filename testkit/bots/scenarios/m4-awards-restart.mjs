// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// Awards are worked out when the Intermission begins, and kept: a restart neither announces them
// again nor changes them. A player offline at season end is counted once archived at login: with
// more deaths than the winner, they take the award, announced as a change, and the trophy moves to
// their cell (questions 212). Reconciliation draws a removed trophy again.
import { SECOND, assert } from '../lib/context.mjs'
import { buildGallery, cellOf, layout } from '../lib/hall.mjs'
import { shownItem } from '../lib/museum.mjs'
import { addTrophies, setStat, trophies } from '../lib/awards.mjs'

export default {
  name: 'm4-awards-restart',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const alex = await ctx.join('Alex')
      const late = await ctx.join('Late')
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      const cells = await buildGallery(ctx, admin, { cells: 3 })
      await addTrophies(ctx, admin, cells)
      await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
      await setStat(ctx, admin, 'Alex', 'minecraft:deaths', 3)
      await setStat(ctx, admin, 'Late', 'minecraft:deaths', 50)
      await ctx.leave(late)

      const announced = ctx.waitForMessages(alex, [/^Season 2 awards:$/, /^Most deaths: Alex \(3\)$/], 30 * SECOND, 'the awards')
      await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
      await announced
      const cell = await cellOf(ctx, admin, 'season-2', cells, 'Alex')
      await ctx.eventually(async () => assert(await shownItem(ctx, admin, { trophy: trophies(cell).first }) === 'minecraft:skeleton_skull', 'Alex has the trophy'), 10 * SECOND)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const cells = layout({ cells: 3 })
      // Not announced again after the restart.
      const quiet = ctx.quiet(admin, /awards:$/)
      await ctx.command(admin, '/forceload add 20 20 44 30', /^(Marked|No chunks)/)
      await ctx.sync(admin, [], 40)
      quiet.verify()
      await ctx.command(admin, '/season firsts', [/^Season 2 awards:$/, /^Most deaths: Alex \(3\)$/])

      // Late is archived at login during the Intermission, and takes the award from Alex.
      const changed = ctx.waitForMessages(admin, [/^Season 2 awards have changed:$/, /^Most deaths: Late \(50\)$/], 30 * SECOND, 'the change')
      await ctx.join('Late')
      await ctx.eventually(async () => assert((await ctx.archive(admin, 'Late', 2)).present, 'Late is archived'), 20 * SECOND)
      await changed
      await ctx.command(admin, '/season firsts', [/^Season 2 awards:$/, /^Most deaths: Late \(50\)$/])
      const card = await ctx.command(admin, '/scenario card Late 2', /^Card of Late for Season 2: /)
      assert(card.includes(' | Awards | Most deaths (50)'), `Late's card has the award: ${card}`)
      const alexs = await ctx.command(admin, '/scenario card Alex 2', /^Card of Alex for Season 2: /)
      assert(alexs.includes(' | Awards | None this season'), `Alex's card has none now: ${alexs}`)
      const alexCell = await cellOf(ctx, admin, 'season-2', cells, 'Alex')
      const lateCell = await cellOf(ctx, admin, 'season-2', cells, 'Late')
      await ctx.eventually(async () => {
        assert(await shownItem(ctx, admin, { trophy: trophies(lateCell).first }) === 'minecraft:skeleton_skull', 'Late has the trophy')
        assert(await shownItem(ctx, admin, { trophy: trophies(alexCell).first }) === null, 'Alex has none')
      }, 10 * SECOND)

      // Reconciliation draws a removed trophy again.
      await ctx.command(admin, '/kill @e[type=minecraft:item_display]', /^Killed /)
      await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: (1 repair|[2-9]\d* repairs|1\d+ repairs)\.$/, 20 * SECOND)
      assert(await shownItem(ctx, admin, { trophy: trophies(lateCell).first }) === 'minecraft:skeleton_skull', 'the trophy is back')
    }
  ]
}
