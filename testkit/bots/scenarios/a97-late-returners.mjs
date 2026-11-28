// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A97. Players who played before Seasons was installed (/season init) and were away for the whole
// of its part of the season still get a cell and a figure when they come back: one during the
// Intermission, one after the next launch. Moving a cell's figure anchor redraws the figure at
// once, without /hof reconcile (1.4.1 plan, H2).
import { assert } from '../lib/context.mjs'
import { giveGear } from '../lib/gear.mjs'
import { FLOOR, at, buildGallery, cellOf, inside } from '../lib/hall.mjs'

async function figureAt (ctx, admin, spot) {
  return ctx.holds(admin, `entity ${at('minecraft:mannequin', spot)}`)
}

export default {
  name: 'a97-late-returners',
  async run (ctx) {
    const admin = await ctx.admin()
    // Both played here before Seasons, then went away.
    for (const name of ['Back', 'Returner']) {
      const bot = await ctx.join(name)
      await giveGear(ctx, admin, name)
      await ctx.leave(bot)
    }
    await ctx.eventually(async () => assert(!await ctx.holds(admin, 'entity @a[name=Back]') && !await ctx.holds(admin, 'entity @a[name=Returner]'), 'Back and Returner have left'))
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 4 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    // Back during the Intermission: archived, given a cell, their figure drawn, sent there.
    await ctx.join('Back')
    await ctx.eventually(async () => assert((await ctx.archive(admin, 'Back', 2)).archivedThrough === 2, 'Back is archived'))
    const backCell = await ctx.eventually(() => cellOf(ctx, admin, 'season-2', cells, 'Back'))
    await ctx.eventually(async () => assert(await figureAt(ctx, admin, backCell.figure), `Back's figure stands in ${backCell.key}`))
    await ctx.eventually(async () => assert(inside(backCell, await ctx.position(admin, 'Back')), `Back is in their cell ${backCell.key}`))

    // After launch, as A5 launches.
    const adminCell = await cellOf(ctx, admin, 'season-2', cells, 'SeasonsAdmin')
    await ctx.eventually(async () => assert(inside(adminCell, await ctx.position(admin, 'SeasonsAdmin')), 'the admin is in their cell'))
    await ctx.command(admin, '/scenario permit SeasonsAdmin seasons.hof.bypass', /^Permitted /)
    await ctx.command(admin, '/tp SeasonsAdmin 500 -60 300', /^Teleported /)
    await ctx.eventually(async () => assert(Math.floor((await ctx.position(admin, 'SeasonsAdmin')).x) === 500, 'admin moved'))
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now 500, -60, 300 /)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)

    // Returner after launch: archived into Season 2, with a cell and a figure there.
    await ctx.join('Returner')
    await ctx.eventually(async () => assert((await ctx.archive(admin, 'Returner', 2)).archivedThrough === 2, 'Returner is archived'))
    const cell = await ctx.eventually(() => cellOf(ctx, admin, 'season-2', cells, 'Returner'))
    await ctx.eventually(async () => assert(await figureAt(ctx, admin, cell.figure), `Returner's figure stands in ${cell.key}`))

    // An anchor moved: the figure follows at once.
    const moved = { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 5 }
    await ctx.command(admin, `/hof cell anchor ${cell.key} figure ${moved.x} ${moved.y} ${moved.z} 90`, /^Added a figure anchor /)
    await ctx.eventually(async () => assert(await figureAt(ctx, admin, moved), 'the figure stands at the new anchor'))
    await ctx.eventually(async () => assert(!await figureAt(ctx, admin, cell.figure), 'and not at the old one'))
  }
}
