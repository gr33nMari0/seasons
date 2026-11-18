// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A35. Before Homecoming, a player standing at the edge of a blocked zone can't break, place, open
// or use a bucket on anything inside it, or use its blocks, hit an armour stand or a frame's item
// there, or pick up an item lying just inside; and pistons, hoppers, liquids and
// explosions can't cross the edge. Each world rule has a control just outside the zone that does
// change, so a rule can't pass by never being tested. A holder of seasons.zone.bypass may.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR, blockItems } from '../lib/hall.mjs'
import { clickBlock, hit, openContainer } from '../lib/protection.mjs'
import { standAt } from '../lib/visits.mjs'
import { EDGE, box, breakBlock, bucket, carried, launchFrontier, permit, place } from '../lib/zones.mjs'

// The vault zone spans x 600 to 610, z 600 to 610, from the floor (y -60) up; the ground under it
// (y -61) is outside it.
export default {
  name: 'a35-edge',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await box(ctx, admin, 'vault', { x: 600, y: FLOOR, z: 600 }, { x: 610, y: FLOOR + 5, z: 610 })
    const setblock = (x, y, z, block) => ctx.command(admin, `/setblock ${x} ${y} ${z} ${block}`, /^Changed the block/)
    const is = (x, y, z, block) => ctx.holds(admin, `block ${x} ${y} ${z} ${block}`)

    await setblock(600, FLOOR, 605, 'minecraft:chest[facing=west]{Items:[{Slot:0b,id:"minecraft:diamond",count:5}]}')
    await setblock(600, FLOOR, 606, 'minecraft:stone')
    await setblock(600, FLOOR, 603, 'minecraft:lever[face=floor]')
    await standAt(ctx, admin, 'Pat', { x: 599, y: FLOOR, z: 605 })

    // From just outside the edge, nothing inside can be touched.
    const told = ctx.waitForMessages(pat, [EDGE('vault'), EDGE('vault'), EDGE('vault'), EDGE('vault'), EDGE('vault')], 10 * SECOND)
    assert(!await breakBlock(ctx, admin, 'Pat', { x: 600, y: FLOOR, z: 606 }), 'breaking inside is refused')
    assert(!await place(ctx, admin, 'Pat', { x: 600, y: FLOOR, z: 607 }, 'minecraft:stone'), 'placing inside is refused')
    assert(await openContainer(ctx, admin, 'Pat', { x: 600, y: FLOOR, z: 605 }) === 'nothing', 'the chest inside doesn\'t open')
    assert(!await bucket(ctx, admin, 'Pat', { x: 600, y: FLOOR, z: 608 }, 'water'), 'a bucket inside is refused')
    assert(!await clickBlock(ctx, admin, 'Pat', { x: 600, y: FLOOR, z: 603 }), 'the lever inside can\'t be pulled')
    await told
    assert(await is(600, FLOOR, 606, 'minecraft:stone') && await is(600, FLOOR, 607, 'minecraft:air') && await is(600, FLOOR, 608, 'minecraft:air'), 'nothing inside changed')
    assert(await is(600, FLOOR, 603, 'minecraft:lever[powered=false]'), 'the lever is still off')
    assert(await place(ctx, admin, 'Pat', { x: 598, y: FLOOR, z: 607 }, 'minecraft:stone'), 'placing outside is fine')

    // Entities inside can't be hit from the edge: the stand keeps its helmet, the frame its emerald.
    await ctx.command(admin, `/summon minecraft:armor_stand 600.5 ${FLOOR} 609.5 {equipment:{head:{id:"minecraft:iron_helmet",count:1}}}`, /^Summoned /)
    await setblock(600, FLOOR, 604, 'minecraft:stone')
    await ctx.command(admin, `/summon minecraft:item_frame 600.5 ${FLOOR + 1} 604.5 {Facing:1b,Item:{id:"minecraft:emerald",count:1}}`, /^Summoned /)
    const hits = ctx.waitForMessages(pat, [EDGE('vault'), EDGE('vault')], 10 * SECOND)
    await standAt(ctx, admin, 'Pat', { x: 599, y: FLOOR, z: 609 })
    await hit(ctx, admin, 'Pat', 'minecraft:armor_stand', { x: 600, y: FLOOR, z: 609 })
    await standAt(ctx, admin, 'Pat', { x: 599, y: FLOOR, z: 604 })
    await hit(ctx, admin, 'Pat', 'minecraft:item_frame', { x: 600, y: FLOOR + 1, z: 604 })
    await hits
    assert(await ctx.holds(admin, 'entity @e[type=minecraft:armor_stand,nbt={equipment:{head:{id:"minecraft:iron_helmet"}}}]'), 'the armour stand keeps its helmet')
    assert(await ctx.holds(admin, 'entity @e[type=minecraft:item_frame,nbt={Item:{id:"minecraft:emerald"}}]'), 'the frame keeps its emerald')

    // An item lying just inside isn't picked up from the edge; one just outside is.
    await standAt(ctx, admin, 'Pat', { x: 599, y: FLOOR, z: 607 })
    await ctx.command(admin, `/summon minecraft:item 600.25 ${FLOOR} 607.5 {Item:{id:"minecraft:gold_nugget",count:1},PickupDelay:0s,Motion:[0.0,0.0,0.0]}`, /^Summoned /)
    await ctx.command(admin, `/summon minecraft:item 598.75 ${FLOOR} 607.5 {Item:{id:"minecraft:iron_nugget",count:1},PickupDelay:0s,Motion:[0.0,0.0,0.0]}`, /^Summoned /)
    await ctx.eventually(async () => assert(await carried(ctx, admin, 'Pat', 'minecraft:iron_nugget') === 1, 'Pat picks up the item outside'), 5 * SECOND)
    await ctx.sync(admin, [], 20) // a second of pickup ticks for the item inside
    assert(await carried(ctx, admin, 'Pat', 'minecraft:gold_nugget') === 0, 'Pat doesn\'t pick up the item inside')
    assert(await ctx.holds(admin, 'entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:gold_nugget"}}]'), 'the item inside is still there')
    await standAt(ctx, admin, 'Pat', { x: 560, y: FLOOR, z: 560 })

    // A piston outside pushing a block in, and one pushing outside only.
    await setblock(599, FLOOR, 609, 'minecraft:stone')
    await setblock(598, FLOOR, 609, 'minecraft:piston[facing=east]')
    await setblock(599, FLOOR, 620, 'minecraft:stone')
    await setblock(598, FLOOR, 620, 'minecraft:piston[facing=east]')
    // A hopper outside feeding a chest inside, and one feeding a chest outside.
    await setblock(600, FLOOR, 602, 'minecraft:chest[facing=west]')
    await setblock(599, FLOOR, 602, 'minecraft:hopper[facing=east]{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:4}]}')
    await setblock(600, FLOOR, 622, 'minecraft:chest[facing=west]')
    await setblock(599, FLOOR, 622, 'minecraft:hopper[facing=east]{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:4}]}')
    // A hopper under the zone pulling from a chest inside, and one under a chest outside.
    await setblock(604, FLOOR, 604, 'minecraft:chest{Items:[{Slot:0b,id:"minecraft:gold_ingot",count:4}]}')
    await setblock(604, FLOOR - 1, 604, 'minecraft:hopper')
    await setblock(604, FLOOR, 624, 'minecraft:chest{Items:[{Slot:0b,id:"minecraft:gold_ingot",count:4}]}')
    await setblock(604, FLOOR - 1, 624, 'minecraft:hopper')
    // Water and lava just outside the edge.
    await setblock(599, FLOOR, 601, 'minecraft:water')
    await setblock(599, FLOOR, 610, 'minecraft:lava')
    // Power both pistons.
    await setblock(597, FLOOR, 609, 'minecraft:redstone_block')
    await setblock(597, FLOOR, 620, 'minecraft:redstone_block')

    await ctx.eventually(async () => assert(await is(600, FLOOR, 620, 'minecraft:stone'), 'the piston outside the zone pushes'), 10 * SECOND)
    await ctx.eventually(async () => assert(/iron_ingot/.test(await blockItems(ctx, admin, { x: 600, y: FLOOR, z: 622 })), 'the hopper outside feeds its chest'), 10 * SECOND)
    await ctx.eventually(async () => assert(/gold_ingot/.test(await blockItems(ctx, admin, { x: 604, y: FLOOR - 1, z: 624 })), 'the hopper outside pulls from its chest'), 10 * SECOND)
    await ctx.eventually(async () => assert(await is(598, FLOOR, 601, 'minecraft:water'), 'water spreads outside the zone'), 10 * SECOND)
    await ctx.eventually(async () => assert(await is(598, FLOOR, 610, 'minecraft:lava'), 'lava spreads outside the zone'), 15 * SECOND)
    // Pistons, hoppers and fluids need game ticks: two seconds of them more.
    await ctx.sync(admin, [], 40)
    assert(await is(599, FLOOR, 609, 'minecraft:stone') && await is(600, FLOOR, 609, 'minecraft:air'), 'the piston can\'t push into the zone')
    assert(!/iron_ingot/.test(await blockItems(ctx, admin, { x: 600, y: FLOOR, z: 602 })), 'no hopper feeds a chest inside the zone')
    assert(/count: ?4/.test(await blockItems(ctx, admin, { x: 604, y: FLOOR, z: 604 })), 'no hopper pulls from a chest inside the zone')
    assert(await is(600, FLOOR, 601, 'minecraft:air'), 'water doesn\'t flow into the zone')
    assert(await is(600, FLOOR, 610, 'minecraft:air'), 'lava doesn\'t flow into the zone')

    // TNT just outside the edge breaks blocks outside, and nothing inside.
    await setblock(601, FLOOR, 615, 'minecraft:stone')
    await setblock(601, FLOOR, 616, 'minecraft:stone')
    await setblock(601, FLOOR, 610, 'minecraft:stone')
    await setblock(602, FLOOR, 610, 'minecraft:stone')
    await ctx.command(admin, `/summon minecraft:tnt 601.5 ${FLOOR} 612.5 {fuse:1}`, /^Summoned /)
    await ctx.sync(admin, [], 20) // the fuse and the explosion are game ticks
    assert(!await is(601, FLOOR, 615, 'minecraft:stone') || !await is(601, FLOOR, 616, 'minecraft:stone'), 'the explosion broke blocks outside the zone')
    assert(await is(601, FLOOR, 610, 'minecraft:stone') && await is(602, FLOOR, 610, 'minecraft:stone'), 'the blocks inside the zone stand')

    // Holders of seasons.zone.bypass may.
    await standAt(ctx, admin, 'Pat', { x: 599, y: FLOOR, z: 606 })
    await permit(ctx, admin, 'Pat', 'seasons.zone.bypass')
    assert(await breakBlock(ctx, admin, 'Pat', { x: 600, y: FLOOR, z: 606 }), 'a bypass holder breaks inside')
  }
}
