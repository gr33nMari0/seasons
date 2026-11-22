// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A69. Two cells tagged in each other's places are put right with /hof cell swap, ids and owners
// staying; move, rename, redefine and remove each change only that cell; /hof undo puts back the
// last edit and is refused once someone else has changed the file; a cell an owner has been
// archived into is refused without --force (questions 273 to 275).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { anchorOf, cellIds, cellInFile } from '../lib/wand.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'

export default {
  name: 'a69-cell-fixes',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/hof cell assign season-2:c1 Visitor', /^Assigned season-2:c1 to Visitor/)

    // Swapped places: ids and owners stay, bounds and anchors trade.
    await ctx.command(admin, '/hof cell swap season-2:c1 season-2:c2', /^Swapped the places of season-2:c1 and season-2:c2\.$/)
    assert(cellInFile(ctx, 'c1').bounds.min[0] === 26, `c1 is where c2 was: ${JSON.stringify(cellInFile(ctx, 'c1').bounds)}`)
    assert(anchorOf(ctx, 'c1', 'figure').x === 28, 'c1 took c2\'s figure anchor')
    await ctx.command(admin, '/hof cell list season-2 owned', /^Cell c1: Visitor$/)
    await ctx.command(admin, '/hof cell swap season-2:c1 season-2:c2', /^Swapped the places of season-2:c1 and season-2:c2\.$/)
    assert(cellInFile(ctx, 'c1').bounds.min[0] === 20, 'and back again')

    // Move, rename, redefine.
    await ctx.command(admin, '/hof cell new season-2 c3', /^Created cell c3 /)
    await ctx.command(admin, `/hof cell bounds season-2:c3 32 ${FLOOR - 1} 22 36 ${FLOOR + 6} 30`, /^Cell season-2:c3 now spans /)
    await ctx.command(admin, `/hof cell anchor season-2:c3 figure 34 ${FLOOR} 28 180`, /^Added a figure anchor /)
    await ctx.command(admin, '/hof cell move season-2:c3 1 0 0', /^Moved season-2:c3 by 1, 0, 0\.$/)
    assert(anchorOf(ctx, 'c3', 'figure').x === 35, 'the anchor moved with the cell')
    await ctx.command(admin, '/hof cell move season-2:c3 10 0 0', /^season-2:c3 would reach outside season-2/)
    await ctx.command(admin, '/hof cell rename season-2:c3 c9', /^Renamed season-2:c3 to season-2:c9\.$/)
    assert(cellIds(ctx).includes('c9') && !cellIds(ctx).includes('c3'), `renamed in the file: ${cellIds(ctx)}`)
    await ctx.command(admin, '/hof cell redefine season-2:c9', /^Your next selection sets season-2:c9's bounds again/)
    await standAt(ctx, admin, me, { x: 33, y: FLOOR, z: 22 })
    await ctx.command(admin, '/hof sel pos1 ~ ~-1 ~', /^Corner 1: /)
    await ctx.command(admin, '/hof sel pos2 ~4 ~6 ~4', [/^Cell season-2:c9 now spans 33, -61, 22 to 37, -54, 26\./, /^Took off the anchors left outside: figure at 35, -60, 28\.$/])

    // Undo puts the redefined cell back, anchor and all.
    await ctx.command(admin, '/hof undo', /^Put galleries\/season-2\.yml back as it was before your last edit\.$/)
    assert(cellInFile(ctx, 'c9').bounds.max[2] === 30, 'the bounds came back')
    assert(anchorOf(ctx, 'c9', 'figure').x === 35, 'the anchor came back')
    await ctx.command(admin, '/hof undo', /^You have no Hall edit to undo\.$/)

    // Refused once someone else has changed the file.
    await ctx.command(admin, '/scenario permit Visitor seasons.admin.hof', /^Permitted /)
    await ctx.command(admin, '/hof cell rename season-2:c9 c3', /^Renamed season-2:c9 to season-2:c3\.$/)
    await ctx.command(visitor, '/hof cell new season-2 c4', /^Created cell c4 /)
    await ctx.command(admin, '/hof undo', /^galleries\/season-2\.yml changed after your last edit, so it can't be undone\.$/)

    // Remove only what isn't owned.
    await ctx.command(admin, '/hof cell remove season-2:c4', /^Removed cell season-2:c4\.$/)

    // Archived into: refused without --force; with it, the figure is drawn in the new place.
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/hof cell move season-2:c1 0 0 -1', /^season-2:c1 holds Visitor's Season 2 archive; add --force/)
    await ctx.command(admin, '/hof cell move season-2:c1 0 0 -1 --force', /^Moved season-2:c1 by 0, 0, -1\.$/)
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity @e[type=mannequin,x=22,y=${FLOOR},z=27,dx=0,dy=1,dz=0]`), 'the figure stands at its new anchor'))
  }
}
