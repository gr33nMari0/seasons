// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A25. With access open, a visitor walks into any cell in adventure mode and can break, place or
// take nothing: not from a chest, an item frame, an armour stand or a decorated pot. A fence gate
// opens for them, since walking in may need it; a lever, a sign and a lectern don't. In creative
// mode Seasons still refuses every change, and each refusal is audited.
import { assert } from '../lib/context.mjs'
import { FLOOR, blockItems, breakBlock, cellOf, gameMode, inside, place, selectorData } from '../lib/hall.mjs'
import { attack, audit, clickBlock, entityAt, expectViewOnly, hit, openContainer, rightClick, seen } from '../lib/protection.mjs'
import { HOME, bed, hallToVisit, hof, move, standAt, waitInHall } from '../lib/visits.mjs'

export default {
  name: 'a25-walk-in',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await ctx.join('Owner')
    const cells = await hallToVisit(ctx, admin)
    await ctx.command(admin, '/hof cell assign season-2:c2 Owner', /^Assigned /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')

    // The owner's cell, as they left it.
    const at = (dx, dy, dz) => ({ x: cell.x0 + dx, y: FLOOR + dy, z: cell.z0 + dz })
    const chest = at(1, 0, 2)
    const gate = at(4, 0, 2)
    const lever = at(1, 0, 3)
    const lectern = at(3, 0, 3)
    const sign = at(0, 0, 4)
    const pot = at(1, 0, 4)
    const stand = at(3, 0, 4)
    const wall = at(4, 1, 5)
    const frame = at(4, 1, 4)
    const setblock = (pos, block) => ctx.command(admin, `/setblock ${pos.x} ${pos.y} ${pos.z} ${block}`, /^Changed the block/)
    await setblock(chest, 'minecraft:chest')
    await ctx.command(admin, `/item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:diamond 4`, /^Replaced /)
    await setblock(gate, 'minecraft:oak_fence_gate')
    await setblock(lever, 'minecraft:lever[face=floor]')
    await setblock(lectern, 'minecraft:lectern[has_book=true]{Book:{id:"minecraft:writable_book",count:1}}')
    await setblock(sign, 'minecraft:oak_sign')
    await setblock(pot, 'minecraft:decorated_pot{item:{id:"minecraft:emerald",count:1}}')
    await setblock(wall, 'minecraft:stone')
    await ctx.command(admin, `/summon minecraft:item_frame ${frame.x} ${frame.y} ${frame.z} {Facing:2b,Item:{id:"minecraft:emerald",count:1}}`, /^Summoned /)
    await ctx.command(admin, `/summon minecraft:armor_stand ${stand.x + 0.5} ${stand.y} ${stand.z + 0.5} {Tags:["a25"],equipment:{head:{id:"minecraft:iron_helmet",count:1}}}`, /^Summoned /)
    const frameSelector = `@e[type=item_frame,x=${frame.x},y=${frame.y},z=${frame.z},dx=0,dy=0,dz=0,limit=1]`
    const potItem = async () => ctx.command(admin, `/data get block ${pot.x} ${pot.y} ${pot.z} item`, /(has the following block data: |^Found no elements matching )/)
    const stocked = await blockItems(ctx, admin, chest)
    const potBefore = await potItem()
    const rotation = await selectorData(ctx, admin, frameSelector, 'ItemRotation')
    const unchanged = async (when) => {
      assert(await blockItems(ctx, admin, chest) === stocked, `${when}: the chest is untouched`)
      assert(await potItem() === potBefore, `${when}: the pot still holds its emerald`)
      assert(/emerald/.test(await selectorData(ctx, admin, frameSelector, 'Item') ?? ''), `${when}: the frame still shows its emerald`)
      assert(await selectorData(ctx, admin, frameSelector, 'ItemRotation') === rotation, `${when}: the frame's item wasn't turned`)
      assert(/iron_helmet/.test(await selectorData(ctx, admin, '@e[type=armor_stand,tag=a25,limit=1]', 'equipment.head') ?? ''), `${when}: the stand keeps its helmet`)
      assert(await ctx.holds(admin, `block ${lever.x} ${lever.y} ${lever.z} minecraft:lever[powered=false]`), `${when}: the lever wasn't pulled`)
      assert(!await ctx.holds(admin, 'entity @e[type=item]'), `${when}: nothing was dropped`)
      assert(await ctx.itemCount(admin, 'Visitor') === 1, `${when}: the visitor holds only their stick`)
    }

    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await standAt(ctx, admin, 'Visitor', HOME)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitInHall(ctx, admin, 'Visitor')
    await ctx.command(admin, '/give Visitor minecraft:stick 1', /^Gave 1 /)
    await move(ctx, admin, 'Visitor', { x: cell.x0 + 2.5, y: FLOOR, z: cell.z0 + 3.5 })
    await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Visitor')), 'the visitor walked into the cell'))
    assert(await gameMode(ctx, admin, 'Visitor') === 2, 'in adventure mode')

    for (const mode of ['adventure', 'creative']) {
      if (mode === 'creative') {
        await ctx.command(admin, '/gamemode creative Visitor', /^Set Visitor's game mode/)
      }
      assert(!await breakBlock(ctx, admin, 'Visitor', chest), `${mode}: the chest can't be broken`)
      assert(!await breakBlock(ctx, admin, 'Visitor', at(2, -1, 6)), `${mode}: nor the floor`)
      assert(!await place(ctx, admin, 'Visitor', at(2, 0, 6), 'minecraft:stone'), `${mode}: nothing can be placed`)
      assert(await openContainer(ctx, admin, 'Visitor', chest) === 'copy', `${mode}: the chest opens as a copy`)
      await expectViewOnly(ctx, admin, visitor, chest)
      assert(await openContainer(ctx, admin, 'Visitor', pot) === 'copy', `${mode}: the pot shows a copy and takes nothing`)
      await expectViewOnly(ctx, admin, visitor, pot)
      assert(!await clickBlock(ctx, admin, 'Visitor', lever), `${mode}: the lever can't be pulled`)
      assert(!await clickBlock(ctx, admin, 'Visitor', lectern), `${mode}: the lectern doesn't open`)
      assert(!await clickBlock(ctx, admin, 'Visitor', sign), `${mode}: the sign can't be edited`)
      await rightClick(visitor, seen(visitor, 'item_frame', frame))
      await attack(visitor, seen(visitor, 'item_frame', frame))
      await rightClick(visitor, seen(visitor, 'armor_stand', stand))
      await attack(visitor, seen(visitor, 'armor_stand', stand))
      await hit(ctx, admin, 'Visitor', 'minecraft:armor_stand', stand)
      assert(await entityAt(ctx, admin, 'minecraft:armor_stand', stand), `${mode}: the armour stand stands`)
      await unchanged(mode)
    }
    // The gate is the one thing that moves: visitors may need to walk through it.
    assert(await clickBlock(ctx, admin, 'Visitor', gate), 'the fence gate opens for a visitor')

    const blocked = audit(ctx).filter((entry) => entry.action === 'hall-blocked' && entry.actor === 'Visitor')
    for (const what of ['break', 'place', 'use', 'hit']) {
      assert(blocked.some((entry) => entry.details.startsWith(what) && entry.target === cell.key), `the ${what} attempts are audited: ${JSON.stringify(blocked)}`)
    }
  }
}
