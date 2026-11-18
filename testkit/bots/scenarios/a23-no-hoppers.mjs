// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A23. No hopper, dropper, dispenser or crafter can be placed anywhere in the Hall, not even by an
// owner decorating their cell, nor a hopper minecart; and nothing pulls items out of a Hall
// container, neither a hopper nor a hopper minecart under it. A hopper under a chest outside the
// Hall still pulls, so the test can't pass by hoppers never working.
import { SECOND, assert, sleep } from '../lib/context.mjs'
import { FLOOR, blockItems, buildGallery, cellOf, gameMode, place, selectorData, visit } from '../lib/hall.mjs'
import { placeEntity } from '../lib/protection.mjs'

export default {
  name: 'a23-no-hoppers',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    await visit(ctx, admin, 'Owner', cell)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'the owner decorates in survival'))

    const spot = { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 3 }
    for (const block of ['minecraft:hopper', 'minecraft:dropper', 'minecraft:dispenser', 'minecraft:crafter']) {
      assert(!await place(ctx, admin, 'Owner', spot, block), `${block} can't be placed in the Hall`)
    }
    assert(!await placeEntity(ctx, admin, 'Owner', 'minecraft:hopper_minecart', spot), 'nor a hopper minecart')
    assert(await place(ctx, admin, 'Owner', spot, 'minecraft:stone'), 'while other blocks can be')

    // Chests in the Hall's floor layer, with a hopper and a hopper minecart under them, just
    // outside the Hall; and a chest with a hopper outside the Hall altogether.
    const hallChest = { x: cell.x0 + 2, y: FLOOR - 1, z: cell.z0 + 4 }
    const cartChest = { x: cell.x0 + 3, y: FLOOR - 1, z: cell.z0 + 5 }
    const outsideChest = { x: 44, y: FLOOR - 1, z: 24 }
    for (const chest of [hallChest, cartChest, outsideChest]) {
      await ctx.command(admin, `/setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`, /^Changed the block/)
      await ctx.command(admin, `/item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:cobblestone 16`, /^Replaced /)
    }
    const stocked = await blockItems(ctx, admin, hallChest)
    await ctx.command(admin, `/setblock ${hallChest.x} ${hallChest.y - 1} ${hallChest.z} minecraft:hopper`, /^Changed the block/)
    await ctx.command(admin, `/setblock ${outsideChest.x} ${outsideChest.y - 1} ${outsideChest.z} minecraft:hopper`, /^Changed the block/)
    await ctx.command(admin, `/setblock ${cartChest.x} ${cartChest.y - 1} ${cartChest.z} minecraft:air`, /^Changed the block/)
    await ctx.command(admin, `/summon minecraft:hopper_minecart ${cartChest.x + 0.5} ${cartChest.y - 1} ${cartChest.z + 0.5}`, /^Summoned /)

    await ctx.eventually(async () => assert(await blockItems(ctx, admin, { ...outsideChest, y: outsideChest.y - 1 }) !== null, 'the hopper outside the Hall pulls'), 10 * SECOND)
    await sleep(3 * SECOND)
    assert(await blockItems(ctx, admin, hallChest) === stocked, 'the hopper took nothing from the Hall chest')
    assert(await blockItems(ctx, admin, { ...hallChest, y: hallChest.y - 1 }) === null, 'the hopper under the Hall is empty')
    assert(await blockItems(ctx, admin, cartChest) === stocked, 'the hopper minecart took nothing from the Hall chest')
    const cart = await selectorData(ctx, admin, '@e[type=hopper_minecart,limit=1]', 'Items')
    assert(cart === null || cart === '[]', `the hopper minecart is empty: ${cart}`)
  }
}
