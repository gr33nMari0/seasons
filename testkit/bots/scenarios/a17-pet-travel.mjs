// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A17. After launch, a registered pet whose chunk was unloaded arrives next to its owner at the
// new spawn; an owner offline at launch gets their pets at first login. With them, the edge cases:
// a pet inside a zone launch locks, one on a lead and one in a boat all travel, unleashed and
// dismounted first.
import { assert } from '../lib/context.mjs'
import { FAR, YARD, list, pet, register, sendAway, summon, waitBeside } from '../lib/pets.mjs'
import { standAt } from '../lib/visits.mjs'
import { BASE, INSIDE, drawBase, endSeason2 } from '../lib/zones.mjs'

const beside = (dx, dz = 0) => ({ x: YARD.x + dx, y: YARD.y, z: YARD.z + dz })

export default {
  name: 'a17-pet-travel',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    const quinn = await ctx.join('Quinn')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await standAt(ctx, admin, 'Pat', YARD)
    await standAt(ctx, admin, 'Quinn', beside(-4))

    // Rex is registered, then wanders far off and its chunk unloads.
    await pet(ctx, admin, 'wolf', 'rex', beside(2), 'Pat', { name: 'Rex' })
    await register(ctx, admin, pat, 'rex', /^Registered Rex /)
    await sendAway(ctx, admin, 'rex')
    const [rexLine] = await list(ctx, pat, 1)
    assert(rexLine === `- Rex (wolf), last seen at ${FAR.x}, ${FAR.y}, ${FAR.z} in minecraft:overworld`, `the unload moved Rex's last known position: ${rexLine}`)

    // Leashy ends up on a lead, tied to a cow.
    await pet(ctx, admin, 'wolf', 'leashy', beside(0, 2), 'Pat', { name: 'Leashy' })
    await register(ctx, admin, pat, 'leashy', /^Registered Leashy /)
    await summon(ctx, admin, 'cow', 'post', beside(1, 3))
    await ctx.command(admin, '/scenario leash leashy post', /^Leashed /)
    assert(await ctx.entityData(admin, '@e[tag=leashy,limit=1]', 'leash') !== null, 'Leashy is on a lead')

    // Boaty ends up sitting in a boat.
    await pet(ctx, admin, 'cat', 'boaty', beside(0, -2), 'Pat', { name: 'Boaty' })
    await register(ctx, admin, pat, 'boaty', /^Registered Boaty /)
    await summon(ctx, admin, 'oak_boat', 'boat', beside(0, -3))
    // A boat summoned beside an animal can pick it up by itself, which is as good.
    await ctx.command(admin, '/ride @e[tag=boaty,limit=1] mount @e[tag=boat,limit=1]', /(started riding|is already riding)/)
    const seated = await ctx.command(admin, '/execute as @e[tag=boat,limit=1] on passengers if entity @s[tag=boaty]', /^Test (passed|failed)/)
    assert(seated.startsWith('Test passed'), 'Boaty sits in the boat')

    // Inside sits in the base, which launch locks.
    await drawBase(ctx, admin)
    await standAt(ctx, admin, 'Pat', INSIDE)
    const insideAt = { x: INSIDE.x + 2, y: INSIDE.y, z: INSIDE.z }
    await pet(ctx, admin, 'parrot', 'inside', insideAt, 'Pat', { name: 'Inside' })
    await register(ctx, admin, pat, 'inside', /^Registered Inside \(parrot\) /, INSIDE)

    // Quinn's cat, and Quinn offline from now until after launch.
    await pet(ctx, admin, 'cat', 'tom', beside(-6), 'Quinn', { name: 'Tom' })
    await register(ctx, admin, quinn, 'tom', /^Registered Tom /, beside(-4))
    await ctx.leave(quinn)
    await ctx.eventually(async () => assert(!await ctx.holds(admin, 'entity @a[name=Quinn]'), 'Quinn has left'))

    await standAt(ctx, admin, 'Pat', YARD)
    await endSeason2(ctx, admin)
    const arrived = ctx.waitForMessages(pat, [/^Rex has come with you\.$/, /^Leashy has come with you\.$/, /^Boaty has come with you\.$/, /^Inside has come with you\.$/], 60_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await arrived
    for (const tag of ['rex', 'leashy', 'boaty', 'inside']) {
      await waitBeside(ctx, admin, tag, 'Pat')
    }
    assert(await ctx.entityData(admin, '@e[tag=leashy,limit=1]', 'leash') === null, 'Leashy was unleashed')
    assert(await ctx.holds(admin, `entity @e[tag=boat,x=${YARD.x},y=${YARD.y},z=${YARD.z},distance=..8]`), 'the boat stayed behind')
    assert(!await ctx.holds(admin, `entity @e[tag=inside,x=${BASE.x},y=${BASE.y},z=${BASE.z},distance=..60]`), 'Inside left the locked base')
    // Registrations clear after travel.
    await list(ctx, pat, 0)

    // Quinn catches up at first login: archived, sent to the spawn, and Tom comes along.
    const quinnBack = await ctx.join('Quinn')
    const tom = ctx.waitForMessages(quinnBack, /^Tom has come with you\.$/, 60_000)
    await ctx.eventually(async () => assert((await ctx.archive(admin, 'Quinn', 2)).archivedThrough === 2, 'Quinn is archived'), 30_000)
    await tom
    await waitBeside(ctx, admin, 'tom', 'Quinn')
    await list(ctx, quinnBack, 0)
    await ctx.command(admin, '/season status', /^Pets: 0 waiting to travel$/)
  }
}
