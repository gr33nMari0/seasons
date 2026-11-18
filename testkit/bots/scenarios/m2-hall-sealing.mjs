// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Sealing the Hall: no dropping or picking up items, no ender pearls or chorus fruit, an exit
// anchor takes a visitor back, and a pearl thrown from outside can't land inside.
import { SECOND, assert, sleep } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HOME, bed, consume, drop, hallToVisit, hof, inGallery, middle, move, pearl, standAt, useItem, waitInHall, waitNear } from '../lib/visits.mjs'

export default {
  name: 'm2-hall-sealing',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin)
    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await standAt(ctx, admin, 'Visitor', HOME)
    await ctx.command(admin, '/give Visitor minecraft:dirt 5', /^Gave 5/)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitInHall(ctx, admin, 'Visitor')
    const inside = { x: 25, y: FLOOR, z: 25 }
    await standAt(ctx, admin, 'Visitor', inside)

    // No dropping, no picking up.
    const noDrop = ctx.waitForMessages(visitor, /^You can't drop items in the Hall of Fame\.$/)
    await drop(ctx, admin, 'Visitor')
    await noDrop
    assert(await ctx.itemCount(admin, 'Visitor') === 5, 'nothing was dropped')
    await ctx.command(admin, `/summon minecraft:item ${inside.x + 0.5} ${FLOOR} ${inside.z + 0.5} {Item:{id:"minecraft:diamond",count:1},PickupDelay:0s,Tags:["loot"]}`, /^Summoned /)
    await sleep(2 * SECOND)
    assert(await ctx.itemCount(admin, 'Visitor') === 5, 'nothing was picked up')
    assert(await ctx.holds(admin, 'entity @e[type=item,tag=loot]'), 'the item is still on the floor')
    await ctx.command(admin, '/kill @e[type=item,tag=loot]', /^Killed /)

    // No pearls, no chorus fruit.
    await ctx.command(admin, '/item replace entity Visitor weapon.mainhand with minecraft:ender_pearl 2', /^Replaced /)
    assert(!await useItem(ctx, admin, 'Visitor'), 'an ender pearl can\'t be thrown in the Hall')
    await ctx.command(admin, '/item replace entity Visitor weapon.mainhand with minecraft:chorus_fruit 2', /^Replaced /)
    assert(!await consume(ctx, admin, 'Visitor'), 'chorus fruit can\'t be eaten in the Hall')
    await ctx.command(admin, '/item replace entity Visitor weapon.mainhand with minecraft:air', /^Replaced /)

    // An exit anchor.
    await ctx.command(admin, `/hof gallery exit season-2 30 ${FLOOR} 21`, /^Added an exit to gallery season-2 /)
    const back = ctx.waitForMessages(visitor, /^Back where you left\.$/, 15_000)
    await move(ctx, admin, 'Visitor', { x: 30.5, y: FLOOR, z: 21.5 }, 'walk')
    await back
    await waitNear(ctx, admin, 'Visitor', middle(HOME))

    // A pearl thrown from outside can't land in the Hall.
    await standAt(ctx, admin, 'Visitor', { x: 25, y: FLOOR, z: 40 })
    await pearl(ctx, admin, 'Visitor', { x: 25, y: FLOOR, z: 25 })
    await sleep(3 * SECOND)
    assert(!inGallery(await ctx.position(admin, 'Visitor')), 'the pearl didn\'t carry the visitor in')
  }
}
