// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A19. A registered pet that died is reported and its registration dropped: at once to an owner
// online, at next login to one who isn't. A pet whose chunk unloads hasn't died, and nor has a
// parrot that lands on its owner's shoulder, which comes with them. One that can't be found at
// travel time is reported the same way.
import { assert } from '../lib/context.mjs'
import { YARD, list, pet, register, sendAway } from '../lib/pets.mjs'
import { standAt } from '../lib/visits.mjs'
import { endSeason2 } from '../lib/zones.mjs'

const beside = (dx, dz = 0) => ({ x: YARD.x + dx, y: YARD.y, z: YARD.z + dz })

export default {
  name: 'a19-pet-died',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    const quinn = await ctx.join('Quinn')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await standAt(ctx, admin, 'Pat', YARD)
    await standAt(ctx, admin, 'Quinn', beside(-4))

    // Online: told at once.
    await pet(ctx, admin, 'wolf', 'rex', beside(2), 'Pat', { name: 'Rex' })
    await register(ctx, admin, pat, 'rex', /^Registered Rex /)
    const died = ctx.waitForMessages(pat, /^Your pet Rex died, so it won't travel\.$/)
    await ctx.command(admin, '/kill @e[tag=rex]', /^Killed /)
    await died
    await list(ctx, pat, 0)

    // A chunk unloading isn't a death.
    await pet(ctx, admin, 'wolf', 'buddy', beside(0, 2), 'Pat', { name: 'Buddy' })
    await register(ctx, admin, pat, 'buddy', /^Registered Buddy /)
    const quiet = ctx.quiet(pat, /Buddy/)
    await sendAway(ctx, admin, 'buddy')
    await ctx.sync(admin, [pat], 20)
    await quiet.verify()
    await list(ctx, pat, 1)

    // Offline: told at next login.
    await pet(ctx, admin, 'cat', 'tom', beside(-6), 'Quinn', { name: 'Tom' })
    await register(ctx, admin, quinn, 'tom', /^Registered Tom /, beside(-4))
    await ctx.leave(quinn)
    await ctx.eventually(async () => assert(!await ctx.holds(admin, 'entity @a[name=Quinn]'), 'Quinn has left'))
    await ctx.command(admin, '/kill @e[tag=tom]', /^Killed /)
    const quinnBack = await ctx.join('Quinn')
    await ctx.waitForMessages(quinnBack, /^Your pet Tom died, so it won't travel\.$/, 20_000)
    await list(ctx, quinnBack, 0)

    // Ghost goes while Seasons isn't watching (as when its chunks are regenerated), so travel
    // can't find it.
    await pet(ctx, admin, 'wolf', 'ghost', beside(0, -2), 'Pat', { name: 'Ghost' })
    await register(ctx, admin, pat, 'ghost', /^Registered Ghost /)
    await ctx.command(admin, '/scenario forget-pet ghost', /^Forgot /)
    await ctx.command(admin, '/kill @e[tag=ghost]', /^Killed /)
    await list(ctx, pat, 2)

    // Polly lands on Pat's shoulder, which takes her out of the world: she hasn't died.
    await pet(ctx, admin, 'parrot', 'polly', beside(1, 1), 'Pat', { name: 'Polly' })
    await register(ctx, admin, pat, 'polly', /^Registered Polly /)
    const calm = ctx.quiet(pat, /Polly/)
    await ctx.command(admin, '/data merge entity @e[tag=polly,limit=1] {NoAI:0b}', /^Modified entity data/)
    await ctx.eventually(async () => {
      const left = await ctx.entityData(admin, 'Pat', 'ShoulderEntityLeft.id')
      const right = await ctx.entityData(admin, 'Pat', 'ShoulderEntityRight.id')
      assert(left !== null || right !== null, 'Polly sits on Pat\'s shoulder')
    }, 60_000)
    assert(!await ctx.holds(admin, 'entity @e[tag=polly]'), 'Polly is out of the world')
    await ctx.sync(admin, [pat], 20)
    await calm.verify()
    await list(ctx, pat, 3)

    await endSeason2(ctx, admin)
    const told = ctx.waitForMessages(pat, [/^Ghost couldn't be found, so it stayed behind\.$/, /^Buddy has come with you\.$/, /^Polly has come with you\.$/], 60_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await told
    await list(ctx, pat, 0)
  }
}
