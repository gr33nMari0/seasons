// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// After their archive, with decorating.afterArchive at its default 15 minutes, an owner still
// arranges their cell but takes nothing out of it into the next season (questions 87): the chest
// they filled before the end opens as a view-only copy, a block they break drops nothing, and they
// still carry nothing.
import { assert } from '../lib/context.mjs'
import { FLOOR, blockItems, breakBlock, buildGallery, cellOf, gameMode, inside, place, visit } from '../lib/hall.mjs'
import { expectViewOnly, openContainer, openWindow } from '../lib/protection.mjs'

export default {
  name: 'm2-after-archive-keeps',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    await visit(ctx, admin, 'Owner', cell)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'the owner decorates in survival'))

    // Before the end: a stash in a chest, and a block to break afterwards.
    const chest = { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 2 }
    const stone = { x: cell.x0 + 3, y: FLOOR, z: cell.z0 + 2 }
    assert(await place(ctx, admin, 'Owner', chest, 'minecraft:chest'), 'the owner places a chest')
    await ctx.command(admin, `/item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:diamond 5`, /^Replaced /)
    assert(await openContainer(ctx, admin, 'Owner', chest) === 'real', 'before the end it is the real chest')
    owner.closeWindow(await openWindow(owner))
    assert(await place(ctx, admin, 'Owner', stone, 'minecraft:diamond_block'), 'and a diamond block')
    const stocked = await blockItems(ctx, admin, chest)

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Owner')), 'the owner is in their cell'))
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'after their archive they still arrange it, in survival'))
    assert(await ctx.itemCount(admin, 'Owner') === 0, 'archived: they carry nothing')

    assert(await openContainer(ctx, admin, 'Owner', chest) === 'copy', 'their stash opens as a copy')
    await expectViewOnly(ctx, admin, owner, chest)
    assert(await breakBlock(ctx, admin, 'Owner', stone, { drops: true }), 'they can still break a block')
    await ctx.sync(admin, [], 20)
    assert(!await ctx.holds(admin, 'entity @e[type=item]'), 'it dropped nothing')
    assert(await ctx.itemCount(admin, 'Owner') === 0, 'they still carry nothing')
    assert(await blockItems(ctx, admin, chest) === stocked, 'the stash stays in the cell')
  }
}
