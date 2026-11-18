// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The Hall's deny-by-default rules from the world's side. Explosions, from outside the Hall or
// inside it, break no Hall block, item frame or armour stand and hurt nobody in the Hall; fire,
// pistons, flowing water and dispensers can't cross its edge. Each rule has a control just outside
// the Hall that does change, so a rule can't pass by never being tested.
import { SECOND, assert, sleep } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { entityAt } from '../lib/protection.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'

// hallToVisit's gallery spans x 20 to 38, y -61 to -50 and z 20 to 30; x 32 to 38 is Hall but no
// cell.
export default {
  name: 'm2-hall-world-rules',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Visitor')
    await hallToVisit(ctx, admin)
    await ctx.command(admin, '/forceload add 20 20 60 50', /^(Marked|No chunks were marked)/)
    const setblock = (x, y, z, block) => ctx.command(admin, `/setblock ${x} ${y} ${z} ${block}`, /^Changed the block/)
    const is = (x, y, z, block) => ctx.holds(admin, `block ${x} ${y} ${z} ${block}`)
    const health = async () => Number.parseFloat(await ctx.entityData(admin, 'Visitor', 'Health'))

    await standAt(ctx, admin, 'Visitor', { x: 36, y: FLOOR, z: 22 })

    // TNT outside the Hall's x edge.
    await setblock(38, FLOOR, 25, 'minecraft:stone')
    await setblock(38, FLOOR, 26, 'minecraft:stone')
    await setblock(37, FLOOR, 26, 'minecraft:stone')
    await ctx.command(admin, `/summon minecraft:item_frame 37 ${FLOOR} 25 {Facing:4b,Item:{id:"minecraft:emerald",count:1}}`, /^Summoned /)
    await ctx.command(admin, `/summon minecraft:armor_stand 36.5 ${FLOOR} 24.5`, /^Summoned /)
    await setblock(43, FLOOR, 25, 'minecraft:stone')
    await setblock(42, FLOOR, 24, 'minecraft:stone')
    await ctx.command(admin, `/summon minecraft:tnt 41.5 ${FLOOR} 25.5 {fuse:1}`, /^Summoned /)
    await sleep(2 * SECOND)
    assert(!await is(43, FLOOR, 25, 'minecraft:stone') || !await is(42, FLOOR, 24, 'minecraft:stone'), 'the explosion broke blocks outside the Hall')
    for (const [x, z] of [[38, 25], [38, 26], [37, 26]]) {
      assert(await is(x, FLOOR, z, 'minecraft:stone'), `the Hall block at ${x}, ${z} stands`)
    }
    assert(await is(38, FLOOR - 1, 24, 'minecraft:grass_block'), 'the Hall floor at its edge stands')
    assert(await entityAt(ctx, admin, 'minecraft:item_frame', { x: 37, y: FLOOR, z: 25 }), 'the item frame hangs on')
    assert(await entityAt(ctx, admin, 'minecraft:armor_stand', { x: 36, y: FLOOR, z: 24 }), 'the armour stand stands')

    // TNT inside the Hall, next to the visitor.
    await setblock(33, FLOOR, 22, 'minecraft:stone')
    await setblock(35, FLOOR, 22, 'minecraft:stone')
    await ctx.command(admin, `/summon minecraft:tnt 34.5 ${FLOOR} 22.5 {fuse:1}`, /^Summoned /)
    await sleep(2 * SECOND)
    assert(await is(33, FLOOR, 22, 'minecraft:stone') && await is(35, FLOOR, 22, 'minecraft:stone'), 'TNT inside the Hall breaks nothing')
    assert(await is(34, FLOOR - 1, 22, 'minecraft:grass_block'), 'not even the floor under it')
    assert(await health() === 20, 'nobody in the Hall is hurt')

    // Fire just outside the Hall's z edge, with wool on both sides.
    await setblock(34, FLOOR - 1, 31, 'minecraft:netherrack')
    await setblock(34, FLOOR, 31, 'minecraft:fire')
    await setblock(34, FLOOR, 30, 'minecraft:white_wool')
    await setblock(33, FLOOR, 31, 'minecraft:white_wool')
    await setblock(35, FLOOR, 31, 'minecraft:white_wool')
    await setblock(34, FLOOR, 32, 'minecraft:white_wool')

    // A piston outside the edge pushing a block into the Hall, and one pushing outside only.
    await setblock(36, FLOOR, 32, 'minecraft:piston[facing=north]')
    await setblock(36, FLOOR, 31, 'minecraft:stone')
    await setblock(44, FLOOR, 34, 'minecraft:piston[facing=north]')
    await setblock(44, FLOOR, 33, 'minecraft:stone')
    await setblock(37, FLOOR, 32, 'minecraft:redstone_block')
    await setblock(45, FLOOR, 34, 'minecraft:redstone_block')

    // Water outside the edge, which may spread outside but not in.
    await setblock(32, FLOOR, 32, 'minecraft:water')

    // A dispenser outside the edge facing into a cell, and one facing outside only.
    await setblock(30, FLOOR, 31, 'minecraft:dispenser[facing=north]{Items:[{Slot:0b,id:"minecraft:water_bucket",count:1}]}')
    await setblock(48, FLOOR, 34, 'minecraft:dispenser[facing=north]{Items:[{Slot:0b,id:"minecraft:water_bucket",count:1}]}')
    await setblock(30, FLOOR, 32, 'minecraft:redstone_block')
    await setblock(48, FLOOR, 35, 'minecraft:redstone_block')

    await ctx.eventually(async () => assert(await is(44, FLOOR, 32, 'minecraft:stone'), 'the piston outside the Hall pushes'), 10 * SECOND)
    assert(await is(36, FLOOR, 31, 'minecraft:stone') && await is(36, FLOOR, 30, 'minecraft:air'), 'the piston can\'t push into the Hall')
    await ctx.eventually(async () => assert(await is(32, FLOOR, 31, 'minecraft:water'), 'water spreads outside the Hall'), 10 * SECOND)
    await ctx.eventually(async () => assert(await is(48, FLOOR, 33, 'minecraft:water'), 'the dispenser outside the Hall pours'), 10 * SECOND)
    await sleep(3 * SECOND)
    assert(await is(32, FLOOR, 30, 'minecraft:air'), 'water doesn\'t flow into the Hall')
    assert(await is(30, FLOOR, 30, 'minecraft:air'), 'the dispenser can\'t pour into the Hall')
    await ctx.eventually(async () => {
      const burnt = !await is(33, FLOOR, 31, 'minecraft:white_wool') || !await is(35, FLOOR, 31, 'minecraft:white_wool') || !await is(34, FLOOR, 32, 'minecraft:white_wool')
      assert(burnt, 'the fire burns wool outside the Hall')
    }, 90 * SECOND)
    assert(await is(34, FLOOR, 30, 'minecraft:white_wool'), 'the wool in the Hall doesn\'t burn')
    assert(!await ctx.holds(admin, 'block 34 -60 30 minecraft:fire'), 'nor catch fire')
  }
}
