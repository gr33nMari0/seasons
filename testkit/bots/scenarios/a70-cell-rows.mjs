// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A70. With one 7 × 7 × 6 cell selected, /hof cell row with --across and --like shows the row's
// cells as outlines sharing one-block walls on both sides of a 3-wide hallway, and confirm writes
// them numbered from the hub outwards, each with the cell's anchors, mirrored on the far side; one
// /hof undo takes them all back; a row reaching outside the gallery or over another cell is refused
// whole; a preview expires after 60 seconds (questions 277).
import { MINUTE, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { anchorOf, cellIds, cellInFile, giveWand } from '../lib/wand.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'

const Y0 = FLOOR
const Y1 = FLOOR + 5

function outlines (bot) {
  return Object.values(bot.entities).filter((e) => e.name === 'block_display').length
}

export default {
  name: 'a70-cell-rows',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    await hallToVisit(ctx, admin, { sealed: null })
    // A wing east of the hub: cells 7 × 6 × 7 on either side of a hallway along x.
    await ctx.command(admin, '/forceload add 40 40 80 60', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/hof gallery create season-3 3`, /^Created gallery season-3/)
    await ctx.command(admin, `/hof gallery bounds season-3 40 ${FLOOR - 1} 40 80 ${FLOOR + 10} 60`, /^Gallery season-3 now spans /)
    await ctx.command(admin, '/hof cell new season-3 c1', /^Created cell c1 /)
    await ctx.command(admin, `/hof cell bounds season-3:c1 41 ${Y0} 41 47 ${Y1} 47`, /^Cell season-3:c1 now spans /)
    await ctx.command(admin, `/hof cell anchor season-3:c1 figure 44 ${Y0} 42 0`, /^Added a figure anchor /)
    await ctx.command(admin, `/hof cell anchor season-3:c1 entry 44 ${Y0} 46 180`, /^Added an entry anchor /)
    await ctx.command(admin, `/hof sel pos1 41 ${Y0} 41`, /^Corner 1: /)
    await ctx.command(admin, `/hof sel pos2 47 ${Y1} 47`, /^Selected /)

    // The preview, as outlines.
    await standAt(ctx, admin, me, { x: 56, y: FLOOR, z: 49 })
    await giveWand(ctx, admin)
    await ctx.eventually(async () => assert(outlines(admin) > 0, 'outlines show'))
    const before = outlines(admin)
    await ctx.command(admin, '/hof cell row season-3 4 --across +z 3 --like c1', /^A row of 7 new cells in season-3, c2 to c8, is shown as outlines for 60 seconds: \/hof cell row confirm writes it\.$/)
    await ctx.eventually(async () => assert(outlines(admin) >= before + 7 * 12, `the row's boxes show: ${outlines(admin)} from ${before}`))
    await ctx.command(admin, '/hof cell row confirm', /^Added 7 cells to season-3: c2 to c8\.$/)

    // Numbered from the hub outwards (+x here), alternating sides, sharing one-block walls.
    assert(JSON.stringify(cellIds(ctx, 'season-3')) === JSON.stringify(['c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7', 'c8']), `ids: ${cellIds(ctx, 'season-3')}`)
    const near = cellInFile(ctx, 'c3', 'season-3').bounds
    assert(near.min[0] === 49 && near.min[2] === 41 && near.max[2] === 47, `c3 is the next near cell: ${JSON.stringify(near)}`)
    const far = cellInFile(ctx, 'c2', 'season-3').bounds
    assert(far.min[0] === 41 && far.min[2] === 51 && far.max[2] === 57, `c2 faces c1 across the hallway: ${JSON.stringify(far)}`)
    const last = cellInFile(ctx, 'c8', 'season-3').bounds
    assert(last.min[0] === 65 && last.max[0] === 71 && last.min[2] === 51, `c8 is the last far cell: ${JSON.stringify(last)}`)

    // Anchors copied, mirrored across the hallway.
    const nearFigure = anchorOf(ctx, 'c3', 'figure', 'season-3')
    assert(nearFigure.x === 52 && nearFigure.z === 42 && nearFigure.yaw === 0, `c3's figure as c1's: ${JSON.stringify(nearFigure)}`)
    const farFigure = anchorOf(ctx, 'c2', 'figure', 'season-3')
    assert(farFigure.x === 44 && farFigure.z === 56 && farFigure.yaw === 180, `c2's figure mirrored: ${JSON.stringify(farFigure)}`)
    const farEntry = anchorOf(ctx, 'c2', 'entry', 'season-3')
    assert(farEntry.z === 52 && farEntry.yaw === 0, `c2's entry mirrored: ${JSON.stringify(farEntry)}`)

    // One undo takes the whole row back.
    await ctx.command(admin, '/hof undo', /^Put galleries\/season-3\.yml back /)
    assert(JSON.stringify(cellIds(ctx, 'season-3')) === JSON.stringify(['c1']), `only c1 left: ${cellIds(ctx, 'season-3')}`)

    // Refused whole: over another cell, or outside the gallery.
    await ctx.command(admin, '/hof cell new season-3 x', /^Created cell x /)
    await ctx.command(admin, `/hof cell bounds season-3:x 50 ${Y0} 41 56 ${Y1} 47`, /^Cell season-3:x now spans /)
    await ctx.command(admin, '/hof cell row season-3 4 --across +z 3', /^The row's cell at 49, -60, 41 to 55, -55, 47 overlaps season-3:x; nothing was added\.$/)
    await ctx.command(admin, '/hof cell remove season-3:x', /^Removed cell season-3:x\.$/)
    await ctx.command(admin, '/hof cell row season-3 6', /^The row's cell at 81, -60, 41 to 87, -55, 47 reaches outside season-3; nothing was added\.$/)

    // A preview not confirmed within a minute is dropped.
    await ctx.command(admin, '/hof cell row season-3 2', /^A row of 1 new cell in season-3, c2, is shown /)
    await ctx.advance(admin, MINUTE + 1000)
    await ctx.command(admin, '/hof cell row confirm', /^No row is waiting; run \/hof cell row first\.$/)
    assert(JSON.stringify(cellIds(ctx, 'season-3')) === JSON.stringify(['c1']), 'nothing was added')
  }
}
