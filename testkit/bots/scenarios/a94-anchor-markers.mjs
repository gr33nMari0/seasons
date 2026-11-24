// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A94. An admin holding the wand sees a marker and a label for each nearby cell's figure, archive,
// plaque, trophy and entry, a link's trigger and landing and a named point; the wand cell's line on
// the action bar says what it has and lacks; a visitor sees none of it; putting the wand away takes
// them away (M8 plan, Seeing anchors).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { link, setBlock } from '../lib/links.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'
import { giveWand } from '../lib/wand.mjs'

const NEAR = { x: 22, y: FLOOR, z: 24 }

function outlines (bot) {
  const all = Object.values(bot.entities).filter((e) => e.name === 'block_display' || e.name === 'text_display')
  return { edges: all.filter((e) => e.name === 'block_display').length, labels: all.filter((e) => e.name === 'text_display').length }
}

/** Whether a label with exactly this text stands within a box around a spot. */
async function label (ctx, admin, at, text, reach = 3) {
  const line = await ctx.command(admin, `/execute if entity @e[type=minecraft:text_display,x=${at.x - reach},y=${at.y - 1},z=${at.z - reach},dx=${2 * reach},dy=4,dz=${2 * reach},nbt={text:"${text}"}]`, /^Test (passed|failed)/)
  return line.startsWith('Test passed')
}

export default {
  name: 'a94-anchor-markers',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    const cells = await hallToVisit(ctx, admin, { sealed: null })
    const c1 = cells[0]
    await ctx.command(admin, `/hof cell anchor ${c1.key} trophy ${c1.x0 + 1} ${FLOOR} ${c1.z0 + 2}`, /^Added a trophy anchor /)
    await setBlock(ctx, admin, { x: 12, y: FLOOR, z: 28 }, 'minecraft:lodestone')
    await link(ctx, admin, 'add marked from 12 -60 28 to hall', /^Link marked leads to /)
    await link(ctx, admin, 'landing marked 13 -60 27', /^Link marked lands at /)
    await ctx.command(admin, '/hof point add yard 15 -60 25', /^Point yard is at /)
    await standAt(ctx, admin, ctx.adminName, NEAR)
    await standAt(ctx, admin, 'Visitor', NEAR)
    const before = outlines(admin)

    await giveWand(ctx, admin)
    await ctx.eventually(async () => assert(outlines(admin).labels - before.labels >= 8, `labels for the anchors, link, landing and point: ${JSON.stringify(outlines(admin))}`))
    for (const text of ['figure', 'archive 1', 'plaque', 'trophy 1', 'entry']) {
      assert(await label(ctx, admin, c1.figure, text, 6), `a "${text}" label near c1`)
    }
    for (const text of ['marked · use', 'marked lands', 'yard']) {
      assert(await label(ctx, admin, { x: 13, y: FLOOR, z: 27 }, text), `a "${text}" label in the hub`)
    }
    await ctx.sync(admin, [visitor], 20)
    assert(outlines(visitor).labels === before.labels, `the visitor sees no labels: ${JSON.stringify(outlines(visitor))}`)

    // The wand cell's line says what it has and lacks.
    await ctx.command(admin, '/hof cell new season-2 c5', /^Created cell c5 /)
    await ctx.waitForMessages(admin, /^Wand \(block mode\): next selection sets season-2:c5's bounds · figure ✗ plaque ✗ archive 0 trophy 0 entry ✗$/)

    // Putting the wand away takes them away.
    admin.setQuickBarSlot((admin.quickBarSlot + 1) % 9)
    await ctx.eventually(async () => assert(outlines(admin).labels === before.labels, `no labels without the wand: ${JSON.stringify(outlines(admin))}`))
  }
}
