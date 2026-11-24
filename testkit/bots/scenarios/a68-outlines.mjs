// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A68. An admin holding the wand sees outlines of the hub, galleries, cells with their ids, gates,
// entries, exits and links nearby, the wand's cell highlighted and named on the action bar, and a
// half-made selection with its size; another player sees none of it; putting the wand away,
// /hof show off, logging out and a restart each leave no outline behind, and reconciliation leaves
// the outlines alone (questions 276).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { click, giveWand } from '../lib/wand.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'

const NEAR = { x: 22, y: FLOOR, z: 21 }

/** The outline entities a bot sees: block displays for edges, text displays for labels. */
function outlines (bot) {
  const all = Object.values(bot.entities).filter((e) => e.name === 'block_display' || e.name === 'text_display')
  return { edges: all.filter((e) => e.name === 'block_display').length, labels: all.filter((e) => e.name === 'text_display').length }
}

export default {
  name: 'a68-outlines',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const me = ctx.adminName
      const visitor = await ctx.join('Visitor')
      await hallToVisit(ctx, admin, { sealed: null })
      await ctx.command(admin, '/hof hall exit 10 -60 22', /^Added a Hall exit /)
      await ctx.command(admin, '/hof link add out 12 -60 28', /^Link out at /)
      await ctx.command(admin, '/hof cell anchor season-2:c1 gate 20 -60 22', /^Added a gate anchor /)
      await ctx.command(admin, '/hof cell anchor season-2:c1 gate 20 -58 24', /^Added a gate anchor /)
      await standAt(ctx, admin, me, NEAR)
      await standAt(ctx, admin, 'Visitor', NEAR)
      const before = outlines(admin)

      // Holding the wand: the outlines appear, cells labelled; the visitor sees none.
      await giveWand(ctx, admin)
      await ctx.eventually(async () => assert(outlines(admin).edges - before.edges >= 12 * 6, `edges for the hub, gallery, cells, gate, entry, exit, link: ${JSON.stringify(outlines(admin))}`))
      await ctx.eventually(async () => assert(outlines(admin).labels - before.labels >= 3, `labels for the gallery and its cells: ${JSON.stringify(outlines(admin))}`))
      await ctx.sync(admin, [visitor], 20)
      assert(outlines(visitor).edges === before.edges && outlines(visitor).labels === before.labels, `the visitor sees no outlines: ${JSON.stringify(outlines(visitor))}`)

      // The wand's cell, named on the action bar; a half selection with its corner.
      await ctx.command(admin, '/hof cell new season-2 c3', /^Created cell c3 in gallery season-2\.$/)
      await ctx.waitForMessages(admin, /^Wand \(block mode\): next selection sets season-2:c3's bounds( · figure .*)?$/)
      const half = outlines(admin).edges
      await click(ctx, admin, me, { x: 32, y: FLOOR - 1, z: 22 }, { left: true, sneak: true })
      await ctx.eventually(async () => assert(outlines(admin).edges >= half + 12, `a box for the half selection: ${JSON.stringify(outlines(admin))}`))

      // Reconciliation leaves them alone.
      const shown = outlines(admin)
      await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2/)
      await ctx.sync(admin, [], 20)
      assert(outlines(admin).edges === shown.edges, `reconciliation kept the outlines: ${JSON.stringify(outlines(admin))}`)

      // Putting the wand away takes them away; /hof show on brings them back without it.
      admin.setQuickBarSlot((admin.quickBarSlot + 1) % 9)
      await ctx.eventually(async () => assert(outlines(admin).edges === before.edges, `no outlines without the wand: ${JSON.stringify(outlines(admin))}`))
      await ctx.command(admin, '/hof show on', /^Showing the Hall's outlines\./)
      await ctx.eventually(async () => assert(outlines(admin).edges > before.edges, 'outlines with /hof show on'))
      await ctx.command(admin, '/hof show off', /^Hiding the Hall's outlines\./)
      await ctx.eventually(async () => assert(outlines(admin).edges === before.edges, 'none after /hof show off'))

      // Shown again, then the admin leaves: the visitor still sees nothing, and none is left.
      await ctx.command(admin, '/hof show on', /^Showing the Hall's outlines\./)
      await ctx.eventually(async () => assert(outlines(admin).edges > before.edges, 'shown again'))
      await ctx.leave(admin)
      const watcher = await ctx.admin()
      await standAt(ctx, watcher, ctx.adminName, NEAR)
      await ctx.sync(watcher, [visitor], 20)
      assert(outlines(watcher).edges === before.edges, `logging out left no outlines: ${JSON.stringify(outlines(watcher))}`)
      await ctx.command(watcher, '/hof show on', /^Showing the Hall's outlines\./)
      await ctx.eventually(async () => assert(outlines(watcher).edges > before.edges, 'shown for the restart'))
    },
    async (ctx) => {
      // After a restart, nothing of the outlines is left in the world.
      const admin = await ctx.admin()
      await standAt(ctx, admin, ctx.adminName, NEAR)
      await ctx.sync(admin, [], 40)
      const line = await ctx.command(admin, '/execute if entity @e[type=block_display]', /^Test (passed|failed)/)
      assert(line.startsWith('Test failed'), `no block display survived the restart: ${line}`)
    }
  ]
}
