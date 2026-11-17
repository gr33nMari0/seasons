// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A47. On a server that has never run Seasons, /season init 2 records Season 2 as the season in
// progress, and /season end then archives everyone into the Season 2 gallery, while the imported
// Season 1 cells are untouched. A second /season init is refused.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, giveGear } from '../lib/gear.mjs'
import { at, buildGallery, buildSeasonOneCell, cellOf, inside, selectorData } from '../lib/hall.mjs'

export default {
  name: 'a47-init',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Veteran')
    await ctx.command(admin, '/season status', /^No season recorded yet\b/)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 as the season in progress\.$/)
    await ctx.command(admin, '/season status', [/^Season 2$/, /^Phase: Active, Open stage$/])
    await ctx.command(admin, '/season init 3', /^Season 2 is already recorded\b/)
    await ctx.command(admin, '/season init 2', /^Season 2 is already recorded\b/)

    // TheRanch's hand-built Season 1 hallway, imported.
    const built = await buildSeasonOneCell(ctx, admin, { x: 60, z: 40, head: 'Veteran', chests: 2 })
    await ctx.command(admin, '/hof gallery create season-1 1', /^Created gallery season-1 for Season 1\.$/)
    await ctx.command(admin, '/hof gallery bounds season-1 58 -61 38 76 -50 50', /^Gallery season-1 now spans /)
    await ctx.command(admin, '/hof cell new season-1 veteran', /^Created cell veteran in gallery season-1\.$/)
    await ctx.command(admin, '/hof cell bounds season-1:veteran 59 -61 39 75 -52 49', /^Cell season-1:veteran now spans /)
    await ctx.command(admin, '/hof import season-1', /^Imported 1 cell from season-1; 0 skipped\.$/, 20_000)
    const seasonOne = await ctx.archive(admin, 'Veteran', 1)
    assert(seasonOne.present, `Veteran's Season 1 cell is imported: ${JSON.stringify(seasonOne)}`)
    const oldFigure = at('minecraft:mannequin', built.stand)
    const oldGear = await ctx.eventually(async () => {
      const gear = await selectorData(ctx, admin, oldFigure, 'equipment')
      assert(gear, 'the Season 1 figure stands')
      return gear
    })

    // The Season 2 gallery, built new; the first rollover fills it.
    const cells = await buildGallery(ctx, admin, { cells: 3 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 2 players have cells\.$/)
    await giveGear(ctx, admin, 'Veteran')
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now: archiving 2 online players\.$/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    const archive = await ctx.archive(admin, 'Veteran', 2)
    assert(archive.present && archive.items === GEAR_ITEMS && archive.archivedThrough === 2, `archived into Season 2: ${JSON.stringify(archive)}`)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Veteran')
    await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Veteran')), 'Veteran is in their Season 2 cell'))
    await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${at('minecraft:mannequin', cell.figure)}`), 'Veteran\'s Season 2 figure stands'))

    // Season 1 is untouched: the same record, the same figure.
    assert(JSON.stringify(await ctx.archive(admin, 'Veteran', 1)) === JSON.stringify({ ...seasonOne, archivedThrough: 2 }), 'the Season 1 record is unchanged')
    assert(await selectorData(ctx, admin, oldFigure, 'equipment') === oldGear, 'the Season 1 figure is unchanged')
    for (const chest of built.chests) {
      assert(await ctx.holds(admin, `block ${chest.x} ${chest.y} ${chest.z} minecraft:chest`), 'the Season 1 archive anchors stand')
    }
  }
}
