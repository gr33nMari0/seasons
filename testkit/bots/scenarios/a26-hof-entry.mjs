// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A26. /hof works within 3 blocks of any bed (straw beds included), and is refused with the
// reason when a monster is near, after damage in the last 10 s, while falling or gliding, or in
// lava. Sneak-clicking a bed with an empty hand works like /hof, and /hof is rate limited.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { HOME, RATE_LIMIT, bed, hallToVisit, hof, inGallery, middle, sneakUse, standAt, waitInHall, waitNear, whileGliding } from '../lib/visits.mjs'

const ENTRY = { x: HALL_SPAWN.x + 0.5, y: HALL_SPAWN.y, z: HALL_SPAWN.z + 0.5 }

export default {
  name: 'a26-hof-entry',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await ctx.command(admin, '/hof', /^The Hall of Fame opens once a season is recorded\.$/)
    await hallToVisit(ctx, admin)
    await standAt(ctx, admin, 'Visitor', HOME)

    // No bed near: refused, and the rate limit counts that attempt.
    await ctx.command(visitor, '/hof', /^You need to be within 3 blocks of a bed\.$/)
    await ctx.command(visitor, '/hof', /^Wait a few seconds before trying \/hof again\.$/)
    await ctx.advance(admin, RATE_LIMIT)

    // A straw bed two blocks away is a bed.
    const straw = { x: HOME.x + 2, y: FLOOR, z: HOME.z }
    await bed(ctx, admin, straw, 'minecraft:straw_bed')
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/)
    await waitInHall(ctx, admin, 'Visitor')
    await waitNear(ctx, admin, 'Visitor', ENTRY)
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(HOME))

    // Sneak-clicking a bed with an empty hand.
    await sneakUse(ctx, admin, 'Visitor', straw)
    await waitInHall(ctx, admin, 'Visitor')
    await ctx.advance(admin, RATE_LIMIT)
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(HOME))

    // A monster near.
    await ctx.command(admin, `/summon minecraft:zombie ${HOME.x - 3} ${FLOOR} ${HOME.z} {NoAI:1b,Tags:["a26"]}`, /^Summoned /)
    await hof(ctx, visitor, '/hof', /^Monsters are nearby\.$/)
    await ctx.command(admin, '/kill @e[tag=a26]', /^Killed /)

    // Damage in the last 10 s.
    await ctx.command(admin, '/damage Visitor 1 minecraft:generic', /^Applied 1/)
    await hof(ctx, visitor, '/hof', /^You were hurt in the last 10 seconds\.$/)

    // Falling: in the air, with a bed beside them so only the fall is wrong.
    const air = { x: HOME.x, y: FLOOR + 6, z: HOME.z }
    await bed(ctx, admin, { x: air.x + 1, y: air.y, z: air.z })
    await standAt(ctx, admin, 'Visitor', air)
    await hof(ctx, visitor, '/hof', /^You can't use \/hof while falling\.$/)

    // Gliding.
    const glided = ctx.waitForMessages(visitor, /^You can't use \/hof while gliding\.$/)
    await whileGliding(ctx, admin, 'Visitor', 'hof')
    await glided
    // Without the elytra the server stops them gliding, which a still bot never would.
    await ctx.command(admin, '/item replace entity Visitor armor.chest with minecraft:air', /^Replaced /)
    await ctx.sync(admin, [visitor], 20) // game ticks, in which the server stops them gliding
    await ctx.advance(admin, RATE_LIMIT)

    // Fire resistance from here on, so magma and lava do no harm.
    await ctx.command(admin, '/effect give Visitor minecraft:fire_resistance 60 0 true', /^Applied effect /)

    // Magma underfoot: return safety would send them to their bed on the way back, a free
    // teleport home, so the spot is turned down now. Before the lava, which leaves them burning.
    const magma = { x: HOME.x, y: FLOOR, z: HOME.z - 12 }
    await bed(ctx, admin, { x: magma.x + 1, y: FLOOR, z: magma.z })
    await ctx.command(admin, `/setblock ${magma.x} ${magma.y - 1} ${magma.z} minecraft:magma_block`, /^Changed the block/)
    await standAt(ctx, admin, 'Visitor', magma)
    await hof(ctx, visitor, '/hof', /^This spot isn't safe to come back to \(magma\)\.$/)

    // Lava.
    await standAt(ctx, admin, 'Visitor', HOME)
    await ctx.command(admin, `/setblock ${HOME.x} ${HOME.y} ${HOME.z} minecraft:lava`, /^Changed the block/)
    await hof(ctx, visitor, '/hof', /^You're standing in lava\.$/)
    await ctx.command(admin, `/setblock ${HOME.x} ${HOME.y} ${HOME.z} minecraft:air`, /^Changed the block/)

    assert(!inGallery(await ctx.position(admin, 'Visitor')), 'every refusal left the visitor where they were')
  }
}
