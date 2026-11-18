// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A22. During decorating, an owner can place and open a chest in their cell; another player sees a
// view-only copy and can take nothing. The owner can break their chest only once it is empty.
import { assert } from '../lib/context.mjs'
import { FLOOR, blockItems, breakBlock, buildGallery, cellOf, gameMode, place, visit } from '../lib/hall.mjs'
import { expectViewOnly, openContainer, openWindow } from '../lib/protection.mjs'

export default {
  name: 'a22-cell-container',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    const visitor = await ctx.join('Visitor')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    await visit(ctx, admin, 'Owner', cell)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'the owner decorates in survival'))
    await visit(ctx, admin, 'Visitor', cell)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 2, 'the visitor is in adventure'))

    const chest = { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 2 }
    assert(await place(ctx, admin, 'Owner', chest, 'minecraft:chest'), 'the owner places a chest in their cell')
    await ctx.command(admin, '/give Owner minecraft:diamond 3', /^Gave 3 /)
    assert(await openContainer(ctx, admin, 'Owner', chest) === 'real', 'the owner opens the chest itself')
    let window = await openWindow(owner)
    const own = window.slots.findIndex((item, i) => i >= window.inventoryStart && item?.name === 'diamond')
    await owner.clickWindow(own, 0, 1)
    await ctx.eventually(async () => assert(/diamond/.test(await blockItems(ctx, admin, chest) ?? ''), 'the owner put their diamonds in'))
    owner.closeWindow(window)
    const stocked = await blockItems(ctx, admin, chest)

    assert(await openContainer(ctx, admin, 'Visitor', chest) === 'copy', 'another player sees a copy')
    await expectViewOnly(ctx, admin, visitor, chest)
    assert(await blockItems(ctx, admin, chest) === stocked, 'the chest still holds the diamonds')
    assert(await ctx.itemCount(admin, 'Visitor') === 0, 'the visitor took nothing')
    assert(!await breakBlock(ctx, admin, 'Visitor', chest), 'the visitor can\'t break it')

    const refused = ctx.waitForMessages(owner, /^Take everything out of it first\.$/)
    assert(!await breakBlock(ctx, admin, 'Owner', chest), 'a chest with something in it doesn\'t break')
    await refused
    assert(await openContainer(ctx, admin, 'Owner', chest) === 'real', 'the owner opens it again')
    window = await openWindow(owner)
    const inside = window.slots.findIndex((item, i) => i < window.inventoryStart && item?.name === 'diamond')
    await owner.clickWindow(inside, 0, 1)
    await ctx.eventually(async () => assert(await blockItems(ctx, admin, chest) === null, 'the owner took their diamonds back'))
    owner.closeWindow(window)
    assert(await breakBlock(ctx, admin, 'Owner', chest), 'the empty chest breaks')
    assert(await ctx.itemCount(admin, 'Owner') === 3, 'the owner has their diamonds')
  }
}
