// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /hof stamp (M5, questions 232 to 234): copies of the built-in alcove side by side from where the
// admin stands, each block as the template has it, the markers turned to air, a cell each with the
// template's footprint as its bounds and its markers as anchors. --step spaces them; --rotate turns
// them, and every anchor lands on the block the game put its marker on (the barrel the archive
// anchor names, the air where the figure marker was) with the figure turned too. A copy outside
// the gallery, over a cell or over a build, or a bad option, changes nothing. Stamped cells are
// ordinary cells: handed out, drawn at season end, the figure facing the way the stamp turned it,
// and the archive anchor opening the archive view. Stamps are audited.
import { assert } from '../lib/context.mjs'
import { FLOOR, at } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { ALCOVE, checkCopy, place } from '../lib/stamp.mjs'
import { GALLERY_FILE, blockIs, cellIds } from '../lib/wand.mjs'
import { forceload } from '../lib/zones.mjs'

async function stampAt (ctx, admin, origin, command, expect) {
  await standAt(ctx, admin, 'SeasonsAdmin', origin)
  return ctx.command(admin, command, expect)
}

export default {
  name: 'm5-stamp',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await ctx.join('Bea')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await forceload(ctx, admin, { x: 80, z: 50 }, 65)
    await ctx.command(admin, '/hof gallery create season-2 2', /^Created gallery season-2 /)
    await ctx.command(admin, `/hof gallery bounds season-2 20 ${FLOOR - 1} 20 140 -40 80`, /^Gallery season-2 now spans /)
    await ctx.command(admin, `/hof gallery entry season-2 21 ${FLOOR} 21`, /^Gallery season-2's entry is now at /)

    // Three side by side: the default step is the template's width.
    const row = { x: 22, y: FLOOR, z: 22 }
    await stampAt(ctx, admin, row, '/hof stamp alcove season-2 3', /^Stamped 3 copies of alcove \(built in\) into season-2: c1, c2, c3\.$/)
    for (const [i, cell] of ['c1', 'c2', 'c3'].entries()) {
      await checkCopy(ctx, admin, cell, { ...row, x: row.x + 5 * i })
    }

    // Each refusal leaves the world and the file as they were.
    const before = ctx.readFile(GALLERY_FILE())
    await stampAt(ctx, admin, row, '/hof stamp alcove season-2 1', /^Copy 1 would overlap cell season-2:c1\.$/)
    await stampAt(ctx, admin, { x: 137, y: FLOOR, z: 22 }, '/hof stamp alcove season-2 1', /^Copy 1 would reach outside gallery season-2\.$/)
    await stampAt(ctx, admin, { x: 132, y: FLOOR, z: 22 }, '/hof stamp alcove season-2 2', /^Copy 2 would reach outside gallery season-2\.$/)
    assert(await blockIs(ctx, admin, { x: 132, y: FLOOR, z: 22 }, 'minecraft:air'), 'copy 1 wasn\'t placed when copy 2 couldn\'t be')
    await ctx.command(admin, `/setblock 60 ${FLOOR + 2} 62 minecraft:stone`, /^Changed the block/)
    await stampAt(ctx, admin, { x: 60, y: FLOOR, z: 60 }, '/hof stamp alcove season-2 1', /^Copy 1 would replace minecraft:stone at 60, -58, 62; stamps only go where there's air\.$/)
    await ctx.command(admin, `/setblock 60 ${FLOOR + 2} 62 minecraft:air`, /^Changed the block/)
    await ctx.command(admin, '/hof stamp alcove season-2 1 --rotate 45', /^--rotate takes 0, 90, 180 or 270\.$/)
    await ctx.command(admin, '/hof stamp alcove season-2 1 --step 1 2', /^--step takes three whole numbers: --step <x> <y> <z>\.$/)
    await ctx.command(admin, '/hof stamp alcove season-2 1 --mirror', /^Unknown option --mirror\. \/hof stamp takes --step <x> <y> <z> and --rotate <degrees>\.$/)
    await ctx.command(admin, '/hof stamp alcove nowhere 1', /^There is no gallery nowhere\.$/)
    assert(ctx.readFile(GALLERY_FILE()) === before, 'no refused stamp changed the gallery file')
    assert(await blockIs(ctx, admin, { x: 60, y: FLOOR, z: 60 }, 'minecraft:air'), 'no refused stamp placed anything')

    // --step spaces the copies.
    const column = { x: 22, y: FLOOR, z: 40 }
    await stampAt(ctx, admin, column, '/hof stamp alcove season-2 2 --step 0 0 8', /^Stamped 2 copies of alcove \(built in\) into season-2: c4, c5\.$/)
    await checkCopy(ctx, admin, 'c4', column)
    await checkCopy(ctx, admin, 'c5', { ...column, z: column.z + 8 })

    // --rotate turns each copy about where the admin stands, anchors and figure with it.
    const turned = { 90: { x: 70, y: FLOOR, z: 30 }, 180: { x: 90, y: FLOOR, z: 30 }, 270: { x: 110, y: FLOOR, z: 30 } }
    for (const [i, [rotate, origin]] of Object.entries(turned).entries()) {
      const cell = `c${6 + i}`
      await stampAt(ctx, admin, origin, `/hof stamp alcove season-2 1 --rotate ${rotate}`, new RegExp(`^Stamped 1 copy of alcove \\(built in\\) into season-2: ${cell}\\.$`))
      await checkCopy(ctx, admin, cell, origin, Number(rotate))
    }
    assert(JSON.stringify(cellIds(ctx)) === JSON.stringify(['c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7', 'c8']), `the gallery has the stamped cells: ${cellIds(ctx)}`)
    await ctx.command(admin, '/season validate', /^All config files are valid\.$/)
    await ctx.command(admin, '/season audit SeasonsAdmin', [/^The last \d+ audit entries for SeasonsAdmin:$/, /stamp .*alcove/])

    // Stamped cells are ordinary cells.
    await ctx.command(admin, '/hof cell assign season-2:c6 Alex', /^Assigned season-2:c6 to Alex for Season 2\.$/)
    await ctx.command(admin, '/hof cell assign season-2:c1 Bea', /^Assigned season-2:c1 to Bea for Season 2\.$/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    const alexFigure = place(turned[90], ALCOVE.figure, 90)
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${at('minecraft:mannequin', alexFigure)}`), 'Alex\'s figure stands in c6'))
    const rotation = await ctx.entityData(admin, at('minecraft:mannequin', alexFigure), 'Rotation[0]')
    assert(Number(String(rotation).replace('f', '')) === 90, `Alex's figure faces as the stamp turned it: ${rotation}`)
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${at('minecraft:mannequin', place(row, ALCOVE.figure))}`), 'Bea\'s figure stands in c1'))
    const opened = new Promise((resolve) => alex.once('windowOpen', resolve))
    await ctx.command(admin, `/scenario use Alex ${Object.values(place(turned[90], ALCOVE.archive, 90)).join(' ')}`, /^Used /)
    const view = await opened
    assert(/Alex · Season 2/.test(JSON.stringify(view.title)), `the barrel opens Alex's archive: ${JSON.stringify(view.title)}`)
  }
}
