// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A16. /pet add works on the player's own tamed wolf, and is refused on a wild wolf, a villager,
// someone else's cat and a donkey with a chest. Also: one taken out of reach, a pet already
// registered, pets.maxPerPlayer, names from the type for unnamed pets, /pet list and /pet remove,
// and registration closing when the season ends.
import { assert } from '../lib/context.mjs'
import { YARD, chest, list, pet, register, summon } from '../lib/pets.mjs'
import { standAt } from '../lib/visits.mjs'
import { endSeason2 } from '../lib/zones.mjs'

const beside = (dx, dz = 0) => ({ x: YARD.x + dx, y: YARD.y, z: YARD.z + dz })

export default {
  name: 'a16-pet-add',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await ctx.join('Quinn')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\npets:\n  enabled: true\n  maxPerPlayer: 3\n')
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await standAt(ctx, admin, 'Pat', YARD)
    await standAt(ctx, admin, 'Quinn', beside(-3))

    // Pat's own tamed wolf.
    await pet(ctx, admin, 'wolf', 'rex', beside(2), 'Pat', { name: 'Rex' })
    await register(ctx, admin, pat, 'rex', /^Registered Rex \(wolf\) to travel with you to the next season's spawn \(1 of 3\)\.$/)
    await register(ctx, admin, pat, 'rex', /^Rex is already registered\.$/)

    // Refused: a wild wolf, a villager, Quinn's cat, a donkey carrying a chest.
    await summon(ctx, admin, 'wolf', 'wild', beside(0, 2))
    await register(ctx, admin, pat, 'wild', /^That wolf isn't tamed, so it can't travel\.$/)
    await summon(ctx, admin, 'villager', 'villager', beside(0, -2))
    await register(ctx, admin, pat, 'villager', /^A villager can't be tamed, so it can't travel\.$/)
    await pet(ctx, admin, 'cat', 'tom', beside(-2), 'Quinn', { name: 'Tom' })
    await register(ctx, admin, pat, 'tom', /^Tom belongs to someone else\.$/)
    await pet(ctx, admin, 'donkey', 'daisy', beside(2, 2), 'Pat', { name: 'Daisy' })
    await chest(ctx, admin, 'daisy')
    await register(ctx, admin, pat, 'daisy', /^Daisy carries a chest\. Take it off first: animals carrying chests can't travel\.$/)
    // Taken off, the donkey can come.
    await chest(ctx, admin, 'daisy', false)
    await register(ctx, admin, pat, 'daisy', /^Registered Daisy \(donkey\) to travel with you to the next season's spawn \(2 of 3\)\.$/)

    // Nothing within 5 blocks: a tamed wolf 8 blocks off doesn't count.
    await pet(ctx, admin, 'wolf', 'distant', beside(8), 'Pat')
    await register(ctx, admin, pat, 'distant', /^Look at a tamed animal of yours within 5 blocks\.$/)

    // Unnamed pets are named from their type.
    await pet(ctx, admin, 'wolf', 'pup', beside(2, -2), 'Pat')
    await register(ctx, admin, pat, 'pup', /^Registered Wolf \(wolf\) to travel with you to the next season's spawn \(3 of 3\)\.$/)
    await pet(ctx, admin, 'wolf', 'pup2', beside(-2, -2), 'Pat')
    await register(ctx, admin, pat, 'pup2', /^You have registered 3 pets, the most you can\.$/)

    let lines = await list(ctx, pat, 3)
    assert(lines.some((line) => /^- Rex \(wolf\), last seen at 202, -60, 200 in minecraft:overworld$/.test(line)), `Rex is listed where it was registered: ${JSON.stringify(lines)}`)
    assert(lines.some((line) => /^- Daisy \(donkey\)/.test(line)) && lines.some((line) => /^- Wolf \(wolf\)/.test(line)), `Daisy and Wolf are listed: ${JSON.stringify(lines)}`)

    await ctx.command(pat, '/pet remove Nobody', /^You have no pet called Nobody\.$/)
    await ctx.command(pat, '/pet remove Daisy', /^Daisy won't travel\.$/)
    // With room again, a second unnamed wolf is Wolf 2, since Wolf is taken.
    await register(ctx, admin, pat, 'pup2', /^Registered Wolf 2 \(wolf\) to travel with you to the next season's spawn \(3 of 3\)\.$/)
    lines = await list(ctx, pat, 3)
    assert(!lines.some((line) => /^- Daisy /.test(line)), `Daisy is gone: ${JSON.stringify(lines)}`)
    await ctx.command(pat, '/pet remove Wolf 2', /^Wolf 2 won't travel\.$/)
    await list(ctx, pat, 2)

    // Registration closes when the season ends; the list stays.
    await endSeason2(ctx, admin)
    await pet(ctx, admin, 'wolf', 'late', beside(0, 3), 'Pat')
    await register(ctx, admin, pat, 'late', /^Registration is closed until the next season starts\.$/)
    await list(ctx, pat, 2)
  }
}
