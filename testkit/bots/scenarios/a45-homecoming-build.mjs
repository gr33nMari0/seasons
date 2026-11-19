// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A45. Before Homecoming nobody can enter the old-world circle by any route; after it, a player can
// take a stack of Season 1 blocks from an old chest and build with it, and break and rebuild part
// of a Season 1 base. Routes in and building are the test plugin's stand-ins where bots can't act
// through the protocol translation (docs/adr/0001, ADR 0003).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { openContainer } from '../lib/protection.mjs'
import { middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { BASE, OUTSIDE, breakBlock, carried, cylinder, expectEveryRouteRefused, inBase, launchFrontier, place, placeCarried } from '../lib/zones.mjs'

const CHEST = { x: BASE.x, y: FLOOR, z: BASE.z + 2 }
const WALL = { x: BASE.x + 3, y: FLOOR, z: BASE.z }
const BUILD = { x: BASE.x + 4, y: FLOOR, z: BASE.z + 3 }

export default {
  name: 'a45-homecoming-build',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await cylinder(ctx, admin, 'old-world', BASE, 30)
    // A Season 1 base: a chest of stone bricks and a wall.
    await ctx.command(admin, `/setblock ${CHEST.x} ${CHEST.y} ${CHEST.z} minecraft:chest[facing=east]{Items:[{Slot:0b,id:"minecraft:stone_bricks",count:64}]}`, /^Changed the block/)
    await ctx.command(admin, `/fill ${WALL.x} ${FLOOR} ${WALL.z - 2} ${WALL.x} ${FLOOR + 2} ${WALL.z + 2} minecraft:mossy_cobblestone`, /^Successfully filled /)

    await expectEveryRouteRefused(ctx, admin, pat, OUTSIDE, { x: BASE.x + 10, y: FLOOR, z: BASE.z }, 'old world', (at) => !inBase(at))

    await ctx.command(admin, '/season unlock homecoming', /^Homecoming! /)
    await standAt(ctx, admin, 'Pat', OUTSIDE)
    const inside = middle({ x: CHEST.x + 1, y: FLOOR, z: CHEST.z })
    await move(ctx, admin, 'Pat', inside, 'walk')
    // A full round of the zone sweep, which would send Pat back if the base were still locked.
    await ctx.sync(admin, [pat], 20)
    await waitNear(ctx, admin, 'Pat', inside)

    // A stack from the old chest, built with.
    assert(await openContainer(ctx, admin, 'Pat', CHEST) === 'real', 'the old chest opens for real')
    await ctx.eventually(async () => assert(pat.currentWindow, 'the chest window is open'))
    await pat.clickWindow(0, 0, 1)
    await ctx.eventually(async () => assert(await carried(ctx, admin, 'Pat', 'minecraft:stone_bricks') === 64, 'Pat took the stack'))
    pat.closeWindow(pat.currentWindow)
    assert(await placeCarried(ctx, admin, 'Pat', BUILD, 'minecraft:stone_bricks'), 'Pat builds with a Season 1 block')
    assert(await ctx.holds(admin, `block ${BUILD.x} ${BUILD.y} ${BUILD.z} minecraft:stone_bricks`), 'the block is there')
    assert(await carried(ctx, admin, 'Pat', 'minecraft:stone_bricks') === 63, 'one brick came out of the stack')

    // Part of the old base, broken and built again.
    assert(await breakBlock(ctx, admin, 'Pat', { x: WALL.x, y: FLOOR + 1, z: WALL.z }), 'Pat breaks part of the old wall')
    assert(await ctx.holds(admin, `block ${WALL.x} ${FLOOR + 1} ${WALL.z} minecraft:air`), 'the wall has a gap')
    assert(await place(ctx, admin, 'Pat', { x: WALL.x, y: FLOOR + 1, z: WALL.z }, 'minecraft:stone_bricks'), 'Pat rebuilds it')
    assert(await ctx.holds(admin, `block ${WALL.x} ${FLOOR + 1} ${WALL.z} minecraft:stone_bricks`), 'the wall is whole again')
  }
}
