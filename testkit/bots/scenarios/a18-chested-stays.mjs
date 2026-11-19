// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A18. A registered donkey that has a chest at travel time stays behind, and the owner is told
// why. A horse's saddle and armour travel with it.
import { assert } from '../lib/context.mjs'
import { YARD, chest, list, near, pet, register, waitBeside } from '../lib/pets.mjs'
import { standAt } from '../lib/visits.mjs'
import { endSeason2 } from '../lib/zones.mjs'

const beside = (dx, dz = 0) => ({ x: YARD.x + dx, y: YARD.y, z: YARD.z + dz })

export default {
  name: 'a18-chested-stays',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await standAt(ctx, admin, 'Pat', YARD)

    const daisyAt = beside(2)
    await pet(ctx, admin, 'donkey', 'daisy', daisyAt, 'Pat', { name: 'Daisy' })
    await register(ctx, admin, pat, 'daisy', /^Registered Daisy \(donkey\) /)
    await pet(ctx, admin, 'horse', 'star', beside(-2), 'Pat', { name: 'Star' })
    await register(ctx, admin, pat, 'star', /^Registered Star \(horse\) /)
    await ctx.command(admin, '/item replace entity @e[tag=star,limit=1] saddle with minecraft:saddle', /^Replaced /)
    await ctx.command(admin, '/item replace entity @e[tag=star,limit=1] armor.body with minecraft:iron_horse_armor', /^Replaced /)
    // After registering, Daisy is given a chest.
    await chest(ctx, admin, 'daisy')
    await ctx.command(admin, '/forceload add 190 190 210 210', /^(Marked|No chunks were marked)/)

    await endSeason2(ctx, admin)
    const told = ctx.waitForMessages(pat, [/^Daisy stayed behind because it carries a chest: animals carrying chests can't travel\.$/, /^Star has come with you\.$/], 60_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await told
    await waitBeside(ctx, admin, 'star', 'Pat')
    assert(await ctx.holds(admin, 'items entity @e[tag=star,limit=1] saddle minecraft:saddle'), 'Star kept its saddle')
    assert(await ctx.holds(admin, 'items entity @e[tag=star,limit=1] armor.body minecraft:iron_horse_armor'), 'Star kept its armour')
    assert(await near(ctx, admin, 'daisy', daisyAt), 'Daisy is still where she was')
    assert(await ctx.entityData(admin, '@e[tag=daisy,limit=1]', 'ChestedHorse') === '1b', 'Daisy still carries her chest')
    await list(ctx, pat, 0)
  }
}
