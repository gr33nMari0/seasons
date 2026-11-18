// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A44 (building). A visitor can't place or break a cushion in someone else's cell, though they can
// sit on one; the owner can place and break them while decorating, and not once the cell is
// locked. A no-build zone refusing cushions waits for zones (M3).
import { assert, sleep } from '../lib/context.mjs'
import { FLOOR, buildGallery, cellOf, gameMode, visit } from '../lib/hall.mjs'
import { entityAt, hit, placeEntity } from '../lib/protection.mjs'
import { sit } from '../lib/visits.mjs'

const CUSHION = 'minecraft:cushion'

export default {
  name: 'a44-cushion-build',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    await ctx.join('Visitor')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    await visit(ctx, admin, 'Owner', cell)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'the owner decorates in survival'))
    await visit(ctx, admin, 'Visitor', cell)

    const seat = { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 3 }
    const other = { x: cell.x0 + 3, y: FLOOR, z: cell.z0 + 3 }
    assert(!await placeEntity(ctx, admin, 'Visitor', CUSHION, seat), 'a visitor can\'t place a cushion in someone else\'s cell')
    assert(!await entityAt(ctx, admin, CUSHION, seat), 'no cushion appeared')
    assert(await placeEntity(ctx, admin, 'Owner', CUSHION, seat), 'the owner places one while decorating')
    assert(await entityAt(ctx, admin, CUSHION, seat), 'the cushion is there')
    for (let i = 0; i < 3; i++) await hit(ctx, admin, 'Visitor', CUSHION, seat)
    assert(await entityAt(ctx, admin, CUSHION, seat), 'a visitor can\'t break it')
    await ctx.eventually(async () => {
      await hit(ctx, admin, 'Owner', CUSHION, seat)
      assert(!await entityAt(ctx, admin, CUSHION, seat), 'the owner breaks their cushion')
    })
    assert(await placeEntity(ctx, admin, 'Owner', CUSHION, other), 'and places it again')

    await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 is closed\.$/)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 2, 'the locked cell is adventure for its owner'))
    assert(!await placeEntity(ctx, admin, 'Owner', CUSHION, seat), 'once locked the owner can\'t place one')
    for (let i = 0; i < 3; i++) await hit(ctx, admin, 'Owner', CUSHION, other)
    assert(await entityAt(ctx, admin, CUSHION, other), 'nor break one')
    await sleep(300)
    assert(await sit(ctx, admin, 'Visitor', CUSHION, other), 'anyone can still sit on it')
  }
}
