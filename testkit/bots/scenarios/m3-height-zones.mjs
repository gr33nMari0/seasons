// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// `y-above` and `y-below` keep the rules of every other shape (A30, A35, A32). In the overworld a
// sky zone from y -50 up and a floor zone from y -62 down: every route up into the sky is refused
// and the player put back; nothing in either is built on or broken from outside; water poured
// above a hole flows down into the hole as far as the floor zone and no further. In the Nether the
// roof is left open with `y-below 127` in place of a cylinder's --max-y: walking and building at
// y 128, refused below.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { middle, move, near, standAt } from '../lib/visits.mjs'
import { EDGE, LOCKED, SPAWN, breakBlock, chorus, forceload, launchFrontier, place, portal, ride } from '../lib/zones.mjs'

const SKY = -50
const DEEP = -62
const NETHER = 'minecraft:the_nether'
const ROOF = 128

export default {
  name: 'm3-height-zones',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await forceload(ctx, admin, SPAWN, 40)
    await standAt(ctx, admin, 'SeasonsAdmin', SPAWN)
    await ctx.command(admin, `/season zone add sky y-above ${SKY}`, new RegExp(`^Added zone sky: everything from y ${SKY} up in minecraft:overworld, `))
    await ctx.command(admin, `/season zone add deep y-below ${DEEP}`, new RegExp(`^Added zone deep: everything up to y ${DEEP} in minecraft:overworld, `))

    // Every route up into the sky is refused, and the player stays below it.
    const from = { x: SPAWN.x + 10, y: FLOOR, z: SPAWN.z }
    const up = { x: SPAWN.x + 14, y: SKY + 3, z: SPAWN.z }
    const routes = [
      ['walking', () => move(ctx, admin, 'Pat', middle(up), 'walk')],
      ['flying', () => move(ctx, admin, 'Pat', middle(up), 'fly')],
      ['riding', () => ride(ctx, admin, 'Pat', middle(up))],
      ['chorus fruit', () => chorus(ctx, admin, 'Pat', middle(up))],
      ['a portal', () => portal(ctx, admin, 'Pat', 'minecraft:overworld', middle(up))]
    ]
    for (const [route, go] of routes) {
      await standAt(ctx, admin, 'Pat', from)
      const told = ctx.waitForMessages(pat, LOCKED('sky'), 10 * SECOND, `the ${route} refusal`)
      await go()
      await told
      await ctx.sync(admin, [pat], 20)
      const at = await ctx.position(admin, 'Pat')
      assert(at.y < SKY && near(at, middle(from), 2), `${route}: Pat is back below the sky, at ${JSON.stringify(at)}`)
      assert(await ctx.entityData(admin, 'Pat', 'RootVehicle') === null, `${route}: not riding anything`)
    }
    // Just under it, a player is free.
    const notStopped = ctx.quiet(pat, LOCKED('sky'))
    await move(ctx, admin, 'Pat', middle({ ...from, y: SKY - 2 }), 'fly')
    await ctx.sync(admin, [pat], 20)
    notStopped.verify()
    assert(near(await ctx.position(admin, 'Pat'), middle({ ...from, y: SKY - 2 }), 1), 'Pat flies just under the sky')

    // Nothing above or below is touched from between them.
    const setblock = (x, y, z, block) => ctx.command(admin, `/setblock ${x} ${y} ${z} ${block}`, /^Changed the block/)
    const is = (x, y, z, block) => ctx.holds(admin, `block ${x} ${y} ${z} ${block}`)
    const x = SPAWN.x + 20
    await standAt(ctx, admin, 'Pat', { x, y: FLOOR, z: 0 })
    const told = ctx.waitForMessages(pat, [EDGE('sky'), EDGE('deep')], 10 * SECOND)
    assert(!await place(ctx, admin, 'Pat', { x, y: SKY, z: 2 }, 'minecraft:stone'), 'placing in the sky is refused')
    assert(!await breakBlock(ctx, admin, 'Pat', { x, y: DEEP, z: 2 }), 'breaking in the floor zone is refused')
    await told
    assert(await is(x, SKY, 2, 'minecraft:air') && !await is(x, DEEP, 2, 'minecraft:air'), 'nothing above or below changed')
    assert(await place(ctx, admin, 'Pat', { x, y: SKY - 1, z: 2 }, 'minecraft:stone'), 'placing just under the sky is fine')
    assert(await breakBlock(ctx, admin, 'Pat', { x, y: DEEP + 1, z: 2 }), 'breaking just above the floor zone is fine')

    // Water poured above a hole flows down into it, and stops at the floor zone.
    await setblock(x, FLOOR - 1, 6, 'minecraft:air')
    await setblock(x, DEEP, 6, 'minecraft:air')
    await setblock(x, FLOOR, 6, 'minecraft:water')
    await ctx.eventually(async () => assert(await is(x, FLOOR - 1, 6, 'minecraft:water'), 'water flows down into the hole'), 10 * SECOND)
    await ctx.sync(admin, [], 40)
    assert(await is(x, DEEP, 6, 'minecraft:air'), 'water doesn\'t flow down into the floor zone')

    // The nether roof, open above y-below 127.
    await ctx.command(admin, `/execute in ${NETHER} run forceload add -64 -64 64 64`, /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/execute in ${NETHER} run tp SeasonsAdmin 0.5 ${ROOF} 0.5`, /^Teleported /)
    await ctx.eventually(async () => assert((await ctx.entityData(admin, 'SeasonsAdmin', 'Dimension')) === `"${NETHER}"`, 'the admin is in the Nether'))
    await ctx.command(admin, '/season zone add old-nether y-below 127', /^Added zone old-nether: everything up to y 127 in minecraft:the_nether, /)
    await ctx.command(admin, `/execute in ${NETHER} run tp Pat 10.5 ${ROOF} 10.5`, /^Teleported /)
    await ctx.eventually(async () => assert((await ctx.entityData(admin, 'Pat', 'Dimension')) === `"${NETHER}"`, 'Pat is in the Nether'))
    const open = ctx.quiet(pat, LOCKED('old nether'))
    await move(ctx, admin, 'Pat', { x: 14.5, y: ROOF, z: 10.5 }, 'walk')
    assert(await place(ctx, admin, 'Pat', { x: 15, y: ROOF, z: 12 }, 'minecraft:cobblestone'), 'Pat builds on the roof')
    await ctx.sync(admin, [pat], 20)
    open.verify()
    assert(near(await ctx.position(admin, 'Pat'), { x: 14.5, y: ROOF, z: 10.5 }, 1), 'Pat stays on the roof')
    const refused = ctx.waitForMessages(pat, LOCKED('old nether'), 10 * SECOND, 'the refusal below the roof')
    await move(ctx, admin, 'Pat', { x: 14.5, y: 120, z: 10.5 }, 'fall')
    await refused
    await ctx.sync(admin, [pat], 20)
    assert((await ctx.position(admin, 'Pat')).y >= ROOF, 'Pat is kept on the roof')
    assert(!await place(ctx, admin, 'Pat', { x: 15, y: 127, z: 13 }, 'minecraft:cobblestone'), 'nobody builds below the roof from it')
  }
}
