// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A24. After the lock, the owner's chest opens as a view-only copy for everyone, the owner
// included, and still does after a restart. (decorating.afterArchive is 0 here, so the cell locks
// at the end and stays locked.)
import { assert } from '../lib/context.mjs'
import { FLOOR, blockItems, breakBlock, buildGallery, cellOf, gameMode, inside, layout, place, visit } from '../lib/hall.mjs'
import { expectViewOnly, openContainer, openWindow } from '../lib/protection.mjs'

const chestOf = (cell) => ({ x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 2 })

export default {
  name: 'a24-lock-view-only',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const owner = await ctx.join('Owner')
      const visitor = await ctx.join('Visitor')
      ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\ndecorating:\n  afterArchive: 0s\n')
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await ctx.command(admin, '/season reload', /^Reloaded /)
      const cells = await buildGallery(ctx, admin, { cells: 2 })
      await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
      const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
      await visit(ctx, admin, 'Owner', cell)
      await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'the owner decorates in survival'))
      const chest = chestOf(cell)
      assert(await place(ctx, admin, 'Owner', chest, 'minecraft:chest'), 'the owner places a chest')
      await ctx.command(admin, `/item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:diamond 5`, /^Replaced /)
      assert(await openContainer(ctx, admin, 'Owner', chest) === 'real', 'before the lock it is the real chest')
      owner.closeWindow(await openWindow(owner))
      const stocked = await blockItems(ctx, admin, chest)

      await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
      await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
      await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Owner')), 'the owner is in their cell'))
      await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 2, 'the locked cell is adventure for its owner'))

      assert(await openContainer(ctx, admin, 'Owner', chest) === 'copy', 'after the lock the owner sees a copy')
      await expectViewOnly(ctx, admin, owner, chest)
      await visit(ctx, admin, 'Visitor', cell)
      assert(await openContainer(ctx, admin, 'Visitor', chest) === 'copy', 'so does anyone else')
      await expectViewOnly(ctx, admin, visitor, chest)
      assert(!await breakBlock(ctx, admin, 'Owner', chest), 'the owner can\'t break it')
      assert(await blockItems(ctx, admin, chest) === stocked, 'the chest still holds what was put in')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const owner = await ctx.join('Owner')
      const cell = await cellOf(ctx, admin, 'season-2', layout({ cells: 2 }), 'Owner')
      const chest = chestOf(cell)
      const stocked = await blockItems(ctx, admin, chest)
      assert(/diamond/.test(stocked ?? ''), 'the chest kept its diamonds through the restart')
      await ctx.eventually(async () => assert(inside(cell, await ctx.position(admin, 'Owner')), 'the owner is in their cell'))
      assert(await openContainer(ctx, admin, 'Owner', chest) === 'copy', 'after a restart it is still a copy')
      await expectViewOnly(ctx, admin, owner, chest)
      assert(await blockItems(ctx, admin, chest) === stocked, 'and nothing left it')
    }
  ]
}
