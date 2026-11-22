// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A71. Sneaking and scrolling with the wand while looking at a plaque grows and shrinks its text
// without changing the held slot; /hof cell plaque and /hof gallery plaque set it; the size
// survives reconciliation and a restart (questions 278).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { giveWand } from '../lib/wand.mjs'
import { hallToVisit } from '../lib/visits.mjs'

// c1's plaque anchor is at 21, -60, 27; its text stands 1.2 above it.
const PLAQUE = { x: 21, y: FLOOR, z: 27 }
const SELECTOR = `@e[type=text_display,x=${PLAQUE.x},y=${PLAQUE.y},z=${PLAQUE.z},dx=0,dy=2,dz=0,limit=1]`

async function scale (ctx, admin) {
  const line = await ctx.command(admin, `/data get entity ${SELECTOR} transformation.scale`, /(has the following entity data: |^No entity was found|^Found no elements)/)
  const match = /\[([\d.]+)f/.exec(line)
  return match ? Number(match[1]) : null
}

export default {
  name: 'a71-plaque-scale',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const me = ctx.adminName
      await ctx.join('Visitor')
      await hallToVisit(ctx, admin, { sealed: null })
      await ctx.command(admin, '/hof cell assign season-2:c1 Visitor', /^Assigned /)
      await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
      await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
      await ctx.eventually(async () => assert(await scale(ctx, admin) === 1, 'the plaque is drawn at its size'))

      // Facing the plaque from three blocks off, holding the wand: sneak-scrolling resizes it.
      await giveWand(ctx, admin)
      const slot = admin.quickBarSlot
      await ctx.command(admin, `/tp ${me} ${PLAQUE.x + 0.5} ${FLOOR} ${PLAQUE.z - 2.5} 0 25`, /^Teleported /)
      const up = (slot + 8) % 9
      const down = (slot + 1) % 9
      await ctx.command(admin, `/scenario scroll ${me} ${up} sneak`, /^Scrolled .*: refused$/)
      await ctx.command(admin, `/scenario scroll ${me} ${up} sneak`, /^Scrolled .*: refused$/)
      await ctx.eventually(async () => assert(await scale(ctx, admin) === 1.2, 'two scrolls up: 1.2'))
      await ctx.command(admin, `/scenario scroll ${me} ${down} sneak`, /^Scrolled .*: refused$/)
      await ctx.eventually(async () => assert(await scale(ctx, admin) === 1.1, 'one down: 1.1'))
      assert(admin.quickBarSlot === slot, 'the held slot didn\'t change')
      // Not sneaking, the slot changes as usual.
      await ctx.command(admin, `/scenario scroll ${me} ${down}`, /^Scrolled .*: done$/)

      // By command, for a cell and a gallery; out of range refused.
      await ctx.command(admin, '/hof cell plaque season-2:c1 scale 0.2', /^A plaque's scale is 0\.25 to 4\.$/)
      await ctx.command(admin, '/hof cell plaque season-2:c1 scale 5', /^A plaque's scale is 0\.25 to 4\.$/)
      await ctx.command(admin, '/hof cell plaque season-2:c1 scale 2', /^The plaque of season-2:c1 is now 2\.0 times its size\.$/)
      await ctx.eventually(async () => assert(await scale(ctx, admin) === 2, 'set to 2'))
      await ctx.command(admin, '/hof gallery plaque season-2 scale 1.5', /^Plaques in season-2 are now 1\.5 times their size/)
      // The cell's own size wins over the gallery's.
      await ctx.sync(admin, [], 5)
      assert(await scale(ctx, admin) === 2, 'the cell keeps its own size')

      // Reconciliation keeps it.
      await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2/)
      await ctx.sync(admin, [], 5)
      assert(await scale(ctx, admin) === 2, 'still 2 after reconciliation')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.command(admin, `/forceload add 20 20 40 30`, /^(Marked|No chunks were marked)/)
      await ctx.eventually(async () => assert(await scale(ctx, admin) === 2, 'still 2 after a restart'), 20_000)
    }
  ]
}
