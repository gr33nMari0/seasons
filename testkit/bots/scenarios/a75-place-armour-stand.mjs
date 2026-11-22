// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A75. An owner decorating their cell, and a builder, place an armour stand there; a visitor can't,
// and a dispenser can't place one in the Hall (questions 282). The test plugin's place-entity spawns
// a living entity with the reason the game's own placing gives it (DEFAULT), as an armour stand
// item does, so the Hall's spawn guard sees what it would.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { entityAt, placeEntity } from '../lib/protection.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'

export default {
  name: 'a75-place-armour-stand',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    await ctx.join('Owner')
    await ctx.join('Visitor')
    const [c1, c2] = await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/hof cell assign season-2:c1 Owner', /^Assigned /)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)

    // The owner, decorating their own cell.
    const own = { x: c1.x0 + 1, y: FLOOR, z: c1.z0 + 2 }
    await standAt(ctx, admin, 'Owner', own)
    assert(await placeEntity(ctx, admin, 'Owner', 'minecraft:armor_stand', own), 'the owner places a stand')
    await ctx.eventually(async () => assert(await entityAt(ctx, admin, 'armor_stand', own), 'it stands there'))

    // A visitor in someone else's cell can't.
    const theirs = { x: c2.x0 + 1, y: FLOOR, z: c2.z0 + 2 }
    await standAt(ctx, admin, 'Visitor', theirs)
    assert(!await placeEntity(ctx, admin, 'Visitor', 'minecraft:armor_stand', theirs), 'a visitor can\'t')
    assert(!await entityAt(ctx, admin, 'armor_stand', theirs), 'nothing there')

    // A builder in creative mode, in the hub.
    const hub = { x: 12, y: FLOOR, z: 25 }
    await ctx.command(admin, `/gamemode creative ${me}`, /game mode/)
    await standAt(ctx, admin, me, hub)
    assert(await placeEntity(ctx, admin, me, 'minecraft:armor_stand', hub), 'a builder places a stand')
    await ctx.eventually(async () => assert(await entityAt(ctx, admin, 'armor_stand', hub), 'it stands in the hub'))

    // A dispenser in the hub places none.
    const dispenser = { x: 14, y: FLOOR, z: 25 }
    await ctx.command(admin, `/setblock ${dispenser.x} ${dispenser.y} ${dispenser.z} minecraft:dispenser[facing=east]`, /^Changed the block/)
    await ctx.command(admin, `/item replace block ${dispenser.x} ${dispenser.y} ${dispenser.z} container.0 with minecraft:armor_stand`, /^Replaced /)
    await ctx.command(admin, `/setblock ${dispenser.x} ${dispenser.y + 1} ${dispenser.z} minecraft:redstone_block`, /^Changed the block/)
    await ctx.sync(admin, [], 10)
    assert(!await entityAt(ctx, admin, 'armor_stand', { x: dispenser.x + 1, y: FLOOR, z: dispenser.z }), 'no stand came out of the dispenser')
  }
}
