// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A5. A player offline at season end is archived at their next login: into their cell during the
// Intermission, and straight to the new spawn in survival after launch.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, expectEmpty, giveGear } from '../lib/gear.mjs'
import { buildGallery, cellOf, inside } from '../lib/hall.mjs'

export default {
  name: 'a05-catch-up',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const early = await ctx.join('Early')
    const late = await ctx.join('Late')
    await giveGear(ctx, admin, 'Early')
    await giveGear(ctx, admin, 'Late')
    const cells = await buildGallery(ctx, admin, { cells: 3 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 3 players have cells\.$/)
    await ctx.leave(early)
    await ctx.leave(late)
    // The bots are gone at once; wait until the server has seen them leave too.
    await ctx.eventually(async () => assert(!await ctx.holds(admin, 'entity @a[name=Early]') && !await ctx.holds(admin, 'entity @a[name=Late]'), 'Early and Late have left'))

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/season status', /^Players: \d+ archived into Season 2, 2 still to archive at login$/)

    // During the Intermission.
    await ctx.join('Early')
    await ctx.eventually(async () => assert((await ctx.archive(admin, 'Early', 2)).archivedThrough === 2, 'Early is archived'))
    await expectEmpty(ctx, admin, 'Early', assert)
    assert((await ctx.archive(admin, 'Early', 2)).items === GEAR_ITEMS, 'Early\'s gear is in the archive')
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Early')
    await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Early')), `Early is in their cell ${cell.key}`))
    assert(await ctx.holds(admin, `entity @e[type=minecraft:mannequin,x=${cell.figure.x},y=${cell.figure.y},z=${cell.figure.z},dx=0,dy=1,dz=0]`), 'Early\'s figure stands in their cell')

    // After launch: a late joiner goes straight to the new spawn.
    await ctx.command(admin, '/tp SeasonsAdmin 500 -60 300', /^Teleported /)
    await ctx.eventually(async () => assert(Math.floor((await ctx.position(admin, 'SeasonsAdmin')).x) === 500, 'admin moved'))
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now 500, -60, 300 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await ctx.join('Late')
    await ctx.eventually(async () => assert((await ctx.archive(admin, 'Late', 2)).archivedThrough === 2, 'Late is archived'))
    await expectEmpty(ctx, admin, 'Late', assert)
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Late')
      assert(Math.abs(at.x - 500.5) <= 1 && Math.abs(at.z - 300.5) <= 1, `Late is at the new spawn, not ${JSON.stringify(at)}`)
    })
    assert(await ctx.entityData(admin, 'Late', 'playerGameType') === '0', 'Late is in survival')
    await ctx.command(admin, '/season status', /^Players: 3 archived into Season 2, 0 still to archive at login$/)
  }
}
