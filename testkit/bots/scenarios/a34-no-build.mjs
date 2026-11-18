// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A34. In a no-build zone a player can walk in, open chests and take normal items, and use a farm's
// button, but can't place, break, use buckets or light TNT, and an explosion there breaks nothing.
// A44's other half: nobody places or breaks a cushion in a no-build zone. Placing, breaking,
// buckets and lighting are the test plugin's stand-ins (docs/adr/0001, ADR 0003).
import { SECOND, assert, sleep } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { clickBlock, entityAt, hit, openContainer, placeEntity } from '../lib/protection.mjs'
import { middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { box, breakBlock, bucket, ignite, launchFrontier, place } from '../lib/zones.mjs'

const NO_BUILD = /^You can't build in the district\.$/

export default {
  name: 'a34-no-build',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await box(ctx, admin, 'district', { x: 700, y: FLOOR - 1, z: 700 }, { x: 720, y: FLOOR + 10, z: 720 })
    await ctx.command(admin, '/season zone mode district no-build', /^Zone district is now no-build while it is locked\.$/)
    const setblock = (x, y, z, block) => ctx.command(admin, `/setblock ${x} ${y} ${z} ${block}`, /^Changed the block/)
    await setblock(705, FLOOR, 705, 'minecraft:chest[facing=west]{Items:[{Slot:0b,id:"minecraft:bread",count:3}]}')
    await setblock(706, FLOOR, 703, 'minecraft:stone_button[face=floor]')
    await setblock(710, FLOOR, 710, 'minecraft:tnt')
    await setblock(712, FLOOR, 712, 'minecraft:stone')
    await ctx.command(admin, `/summon minecraft:cushion 714.5 ${FLOOR} 704.5`, /^Summoned /)

    // Walking in is fine.
    await standAt(ctx, admin, 'Pat', { x: 697, y: FLOOR, z: 705 })
    await move(ctx, admin, 'Pat', middle({ x: 703, y: FLOOR, z: 705 }), 'walk')
    await sleep(2 * SECOND)
    await waitNear(ctx, admin, 'Pat', middle({ x: 703, y: FLOOR, z: 705 }))

    // Chests open for real, and normal items can be taken.
    assert(await openContainer(ctx, admin, 'Pat', { x: 705, y: FLOOR, z: 705 }) === 'real', 'the chest opens for real')
    await ctx.eventually(async () => assert(pat.currentWindow, 'the chest window is open'))
    await pat.clickWindow(0, 0, 1)
    await ctx.eventually(async () => assert(await ctx.itemCount(admin, 'Pat') === 3, 'Pat took the bread'))
    pat.closeWindow(pat.currentWindow)
    assert(await clickBlock(ctx, admin, 'Pat', { x: 706, y: FLOOR, z: 703 }), 'the farm button can be pressed')

    // Building, buckets and TNT are refused.
    let told = ctx.waitForMessages(pat, [NO_BUILD, NO_BUILD, NO_BUILD, NO_BUILD], 10 * SECOND)
    assert(!await place(ctx, admin, 'Pat', { x: 704, y: FLOOR, z: 707 }, 'minecraft:stone'), 'placing is refused')
    assert(!await breakBlock(ctx, admin, 'Pat', { x: 712, y: FLOOR, z: 712 }), 'breaking is refused')
    assert(!await bucket(ctx, admin, 'Pat', { x: 704, y: FLOOR, z: 708 }, 'water'), 'emptying a bucket is refused')
    assert(!await ignite(ctx, admin, 'Pat', { x: 710, y: FLOOR, z: 710 }), 'lighting TNT is refused')
    await told
    assert(await ctx.holds(admin, `block 710 ${FLOOR} 710 minecraft:tnt`), 'the TNT is still there')
    assert(await ctx.holds(admin, `block 704 ${FLOOR} 708 minecraft:air`), 'no water was poured')

    // Cushions: neither placed nor broken.
    told = ctx.waitForMessages(pat, NO_BUILD, 10 * SECOND)
    assert(!await placeEntity(ctx, admin, 'Pat', 'minecraft:cushion', { x: 703, y: FLOOR, z: 703 }), 'no cushion is placed')
    await told
    await standAt(ctx, admin, 'Pat', { x: 713, y: FLOOR, z: 704 })
    await hit(ctx, admin, 'Pat', 'minecraft:cushion', { x: 714, y: FLOOR, z: 704 })
    assert(await entityAt(ctx, admin, 'minecraft:cushion', { x: 714, y: FLOOR, z: 704 }), 'the cushion is still there')

    // An explosion breaks nothing.
    await standAt(ctx, admin, 'Pat', { x: 690, y: FLOOR, z: 690 })
    await ctx.command(admin, `/summon minecraft:tnt 713.5 ${FLOOR} 713.5 {fuse:1}`, /^Summoned /)
    await sleep(2 * SECOND)
    assert(await ctx.holds(admin, `block 712 ${FLOOR} 712 minecraft:stone`), 'the stone stands')
    assert(await ctx.holds(admin, `block 713 ${FLOOR - 1} 713 minecraft:grass_block`), 'the floor under the TNT stands')
    assert(await ctx.holds(admin, `block 710 ${FLOOR} 710 minecraft:tnt`), 'the TNT nearby wasn\'t set off')
  }
}
