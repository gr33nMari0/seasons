// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A95. From the wand's menu on a cell's figure anchor an admin picks it up, sneak-scrolls to turn
// it, and right-clicks the floor to put it down there, facing the new way; /hof undo puts it back.
// The second of two archive anchors is moved to another chest, the first staying. A drop outside
// the cell or into another cell is refused; a left click puts a held anchor back. /hof cell anchors
// lists them numbered, and anchor move and unanchor take one of several by number (M8 plan,
// Moving anchors by dragging).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { pick, setBlock, wandMenu } from '../lib/links.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'
import { cellInFile, click, giveWand } from '../lib/wand.mjs'

const GROUND = FLOOR - 1

async function scroll (ctx, admin, bot, up) {
  const to = (bot.quickBarSlot + (up ? 8 : 1)) % 9
  await ctx.command(admin, `/scenario scroll ${bot.username} ${to} sneak`, /^Scrolled /)
}

const of = (ctx, type) => cellInFile(ctx, 'c1').anchors.filter((a) => a.type === type)

export default {
  name: 'a95-anchor-drag',
  async run (ctx) {
    const admin = await ctx.admin()
    const cells = await hallToVisit(ctx, admin, { sealed: null })
    const c1 = cells[0]
    await giveWand(ctx, admin)
    await standAt(ctx, admin, ctx.adminName, { x: c1.x0 + 2, y: FLOOR, z: c1.z0 + 2 })

    // The figure: picked up, turned, put down.
    await wandMenu(ctx, admin, admin, c1.figure)
    await pick(ctx, admin, 'piston', /^Holding season-2:c1's figure anchor: right-click where it goes, left-click to put it back\.$/)
    await scroll(ctx, admin, admin, true)
    await scroll(ctx, admin, admin, true)
    await Promise.all([ctx.waitForMessages(admin, /^Moved season-2:c1's figure anchor to 21, -60, 26\.$/), click(ctx, admin, ctx.adminName, { x: c1.x0 + 1, y: GROUND, z: c1.z0 + 4 })])
    const figure = of(ctx, 'figure')
    assert(figure.length === 1 && figure[0].x === c1.x0 + 1 && figure[0].z === c1.z0 + 4, `the figure moved: ${JSON.stringify(figure)}`)
    assert(figure[0].yaw !== 180, `and turned: ${JSON.stringify(figure)}`)
    await ctx.command(admin, '/hof undo', /^Put galleries\/season-2\.yml back /)
    assert(of(ctx, 'figure')[0].x === c1.figure.x, 'undo puts it back')

    // Two archives: the second moves, the first stays.
    const second = { x: c1.x0 + 1, y: FLOOR, z: c1.z0 + 6 }
    const third = { x: c1.x0 + 3, y: FLOOR, z: c1.z0 + 5 }
    await setBlock(ctx, admin, second, 'minecraft:chest')
    await setBlock(ctx, admin, third, 'minecraft:barrel')
    await ctx.command(admin, `/hof cell anchor ${c1.key} archive ${second.x} ${second.y} ${second.z}`, /^Added an archive anchor /)
    await ctx.command(admin, `/hof cell anchors ${c1.key}`, [/^1\. figure at /, /^ ?\d+\. archive #1 at 23, -60, 28/, /^ ?\d+\. archive #2 at 21, -60, 28/])
    await wandMenu(ctx, admin, admin, second)
    await pick(ctx, admin, 'piston', /^Holding season-2:c1's archive anchor/)
    // Outside the cell, and in another cell: refused.
    await Promise.all([ctx.waitForMessages(admin, /^That's outside season-2:c1\.$/), click(ctx, admin, ctx.adminName, { x: 40, y: GROUND, z: 30 })])
    await Promise.all([ctx.waitForMessages(admin, /^That's outside season-2:c1\.$/), click(ctx, admin, ctx.adminName, { x: cells[1].x0 + 2, y: FLOOR, z: cells[1].z0 + 2 })])
    await Promise.all([ctx.waitForMessages(admin, /^Moved season-2:c1's archive anchor to 23, -60, 27\.$/), click(ctx, admin, ctx.adminName, third)])
    const archives = of(ctx, 'archive').map((a) => `${a.x},${a.z}`).sort()
    assert(JSON.stringify(archives) === JSON.stringify(['23,27', '23,28']), `the first stays, the second moved: ${archives}`)

    // A left click puts a held anchor back.
    await wandMenu(ctx, admin, admin, c1.plaque)
    await pick(ctx, admin, 'piston', /^Holding season-2:c1's plaque anchor/)
    await Promise.all([ctx.waitForMessages(admin, /^Put season-2:c1's plaque anchor back\.$/), click(ctx, admin, ctx.adminName, { x: c1.x0 + 2, y: GROUND, z: c1.z0 + 3 }, { left: true })])

    // By command.
    await ctx.command(admin, `/hof cell anchor move ${c1.key} archive #2 21 -60 29`, /^Moved season-2:c1's archive anchor to 21, -60, 29\.$/)
    await ctx.command(admin, `/hof cell unanchor ${c1.key} archive #1`, /^Removed the archive anchor at /)
    assert(of(ctx, 'archive').length === 1, 'one archive is left')
  }
}
