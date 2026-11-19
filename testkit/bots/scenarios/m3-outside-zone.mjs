// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// An `outside` zone keeps the rules of every other shape (A30, A35): a frontier circle around the
// spawn, where everything beyond the circle is locked. Every route across its edge is refused and
// the player put back; nothing beyond it is touched from inside, and water doesn't flow across,
// with a control that does flow inside; a long flight inside the circle isn't stopped; a player
// who logs in beyond it is sent to the spawn. An `outside box` around it locks what lies beyond
// the box once the circle is unlocked on its own, and Homecoming opens both (A33).
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { middle, move, near, standAt } from '../lib/visits.mjs'
import { EDGE, LOCKED, SPAWN, breakBlock, expectEveryRouteRefused, forceload, inCircle, launchFrontier, outsideCircle, place } from '../lib/zones.mjs'

const RADIUS = 40
const inside = (at) => inCircle(SPAWN, RADIUS, at)
/** Just inside the circle's east edge, and a little beyond it. */
const EDGE_IN = { x: SPAWN.x + 35, y: FLOOR, z: SPAWN.z }
const BEYOND = { x: SPAWN.x + 46, y: FLOOR, z: SPAWN.z }
/** Where Quinn logs out, before the frontier is drawn. */
const AWAY = { x: SPAWN.x + 55, y: FLOOR, z: SPAWN.z + 10 }

export default {
  name: 'm3-outside-zone',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    let quinn = await ctx.join('Quinn')
    await launchFrontier(ctx, admin)
    await forceload(ctx, admin, SPAWN, 120)
    await standAt(ctx, admin, 'Quinn', AWAY)
    await ctx.leave(quinn)
    await outsideCircle(ctx, admin, 'frontier', SPAWN, RADIUS)

    // Every route across the edge is refused, and the player is put back inside.
    await expectEveryRouteRefused(ctx, admin, pat, EDGE_IN, BEYOND, 'frontier', inside)

    // A long flight inside the circle, from one edge to the other, isn't stopped.
    const notStopped = ctx.quiet(pat, LOCKED('frontier'))
    const across = middle({ x: SPAWN.x - 35, y: FLOOR + 4, z: SPAWN.z })
    await move(ctx, admin, 'Pat', across, 'fly')
    await ctx.sync(admin, [pat], 20)
    notStopped.verify()
    assert(near(await ctx.position(admin, 'Pat'), across, 1), 'Pat flies across the circle')

    // From just inside the edge, nothing beyond it can be touched.
    const setblock = (x, y, z, block) => ctx.command(admin, `/setblock ${x} ${y} ${z} ${block}`, /^Changed the block/)
    const is = (x, y, z, block) => ctx.holds(admin, `block ${x} ${y} ${z} ${block}`)
    const x = SPAWN.x
    await setblock(x + 41, FLOOR, 0, 'minecraft:stone')
    await standAt(ctx, admin, 'Pat', { x: x + 39, y: FLOOR, z: 0 })
    const told = ctx.waitForMessages(pat, [EDGE('frontier'), EDGE('frontier')], 10 * SECOND)
    assert(!await breakBlock(ctx, admin, 'Pat', { x: x + 41, y: FLOOR, z: 0 }), 'breaking beyond the edge is refused')
    assert(!await place(ctx, admin, 'Pat', { x: x + 41, y: FLOOR, z: 1 }, 'minecraft:stone'), 'placing beyond the edge is refused')
    await told
    assert(await is(x + 41, FLOOR, 0, 'minecraft:stone') && await is(x + 41, FLOOR, 1, 'minecraft:air'), 'nothing beyond changed')
    assert(await place(ctx, admin, 'Pat', { x: x + 38, y: FLOOR, z: 2 }, 'minecraft:stone'), 'placing inside is fine')

    // Water just inside the edge spreads inside, and not across it.
    await setblock(x + 39, FLOOR, 5, 'minecraft:water')
    await ctx.eventually(async () => assert(await is(x + 38, FLOOR, 5, 'minecraft:water'), 'water spreads inside the circle'), 10 * SECOND)
    await ctx.sync(admin, [], 40)
    assert(await is(x + 40, FLOOR, 5, 'minecraft:air'), 'water doesn\'t flow beyond the edge')

    // Logging in beyond the frontier sends a player to the spawn.
    quinn = await ctx.join('Quinn')
    await ctx.waitForMessages(quinn, /^You were in the frontier, which is locked, so you're back at the spawn\.$/, 15 * SECOND)
    await ctx.eventually(async () => assert(inside(await ctx.position(admin, 'Quinn')), 'Quinn is back inside the circle'))

    // An outside box around the circle: with the circle unlocked on its own, its edge is the one
    // that holds.
    await ctx.command(admin, `/season zone add edge outside box ${x - 100} -100 ${x + 100} 100`, /^Added zone edge: everywhere beyond a box from 2900, -100 to 3100, 100 in minecraft:overworld, /)
    await ctx.command(admin, '/season unlock frontier', /^Zone frontier is unlocked: it is off now\.$/)
    await standAt(ctx, admin, 'Pat', EDGE_IN)
    await move(ctx, admin, 'Pat', middle(BEYOND), 'walk')
    await ctx.sync(admin, [pat], 20)
    assert(near(await ctx.position(admin, 'Pat'), middle(BEYOND), 1), 'Pat walks beyond the unlocked circle')
    await standAt(ctx, admin, 'Pat', { x: x + 98, y: FLOOR, z: 0 })
    const boxed = ctx.waitForMessages(pat, LOCKED('edge'), 10 * SECOND, 'the box\'s refusal')
    await move(ctx, admin, 'Pat', middle({ x: x + 103, y: FLOOR, z: 0 }), 'walk')
    await boxed
    await ctx.sync(admin, [pat], 20)
    assert((await ctx.position(admin, 'Pat')).x < x + 101, 'Pat stays inside the box')

    // Homecoming opens both.
    await ctx.command(admin, '/season unlock homecoming', /^Homecoming! Every zone is now in its after-unlock mode\.$/)
    const open = ctx.quiet(pat, LOCKED('edge'))
    await move(ctx, admin, 'Pat', middle({ x: x + 103, y: FLOOR, z: 0 }), 'walk')
    await ctx.sync(admin, [pat], 20)
    open.verify()
    assert(near(await ctx.position(admin, 'Pat'), middle({ x: x + 103, y: FLOOR, z: 0 }), 1), 'after Homecoming Pat walks beyond the box')
  }
}
