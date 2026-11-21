// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A59. A creative builder and a bypass holder place and break blocks in the hub, a hopper and an
// ender chest included; what they place stays through reconciliation and does nothing for
// visitors; an owner decorates only their own cell, during the window; an op in survival without
// bypass changes nothing (questions 252).
import { assert } from '../lib/context.mjs'
import { FLOOR, breakBlock, cellOf, place, use } from '../lib/hall.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'
import { permit } from '../lib/zones.mjs'

const TABLE = { x: 10, y: FLOOR, z: 22 }
const HOPPER = { x: 11, y: FLOOR, z: 22 }
const ENDER = { x: 12, y: FLOOR, z: 22 }
const STONE = { x: 13, y: FLOOR, z: 22 }

export default {
  name: 'a59-decoration',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await ctx.join('Builder')
    await ctx.join('Bea')
    await ctx.join('Oscar')
    const cells = await hallToVisit(ctx, admin, { cells: 5, sealed: null })

    // A builder in creative mode places anything in the hub, and breaks it again.
    await standAt(ctx, admin, 'Builder', { x: 12, y: FLOOR, z: 25 })
    await ctx.command(admin, '/gamemode creative Builder', /^Set Builder's game mode/)
    assert(await place(ctx, admin, 'Builder', TABLE, 'minecraft:crafting_table'), 'a creative builder places a crafting table')
    assert(await place(ctx, admin, 'Builder', HOPPER, 'minecraft:hopper'), 'a creative builder places a hopper')
    assert(await place(ctx, admin, 'Builder', STONE, 'minecraft:stone'), 'a creative builder places stone')
    assert(await breakBlock(ctx, admin, 'Builder', STONE), 'a creative builder breaks it again')

    // So does a bypass holder in survival.
    await standAt(ctx, admin, 'Bea', { x: 12, y: FLOOR, z: 27 })
    await permit(ctx, admin, 'Bea', 'seasons.hof.bypass')
    assert(await place(ctx, admin, 'Bea', ENDER, 'minecraft:ender_chest[facing=south]'), 'a bypass holder places an ender chest')

    // An op in survival without the bypass changes nothing.
    await ctx.command(admin, '/op Oscar', /Made Oscar a server operator|already is an operator/)
    await standAt(ctx, admin, 'Oscar', { x: 14, y: FLOOR, z: 27 })
    assert(!await place(ctx, admin, 'Oscar', STONE, 'minecraft:stone'), 'an op in survival can\'t place in the hub')
    assert(!await breakBlock(ctx, admin, 'Oscar', TABLE), 'nor break the crafting table')

    // The decoration stays through reconciliation, and does nothing for a visitor.
    await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: /)
    for (const [at, block] of [[TABLE, 'crafting_table'], [HOPPER, 'hopper'], [ENDER, 'ender_chest']]) {
      assert(await ctx.holds(admin, `block ${at.x} ${at.y} ${at.z} minecraft:${block}`), `the ${block} stays`)
    }
    await standAt(ctx, admin, 'Visitor', { x: 11, y: FLOOR, z: 24 })
    await ctx.eventually(async () => assert(Number(await ctx.entityData(admin, 'Visitor', 'playerGameType')) === 2, 'the visitor is in adventure mode'))
    for (const at of [TABLE, ENDER]) {
      const refused = ctx.waitForMessages(visitor, /^You can't use that in the Hall of Fame\.$/)
      await use(ctx, admin, 'Visitor', at)
      await refused
    }
    assert(!await breakBlock(ctx, admin, 'Visitor', HOPPER), 'a visitor can\'t break the hopper')

    // During the window an owner decorates their own cell, and nowhere else.
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const mine = await cellOf(ctx, admin, 'season-2', cells, 'Visitor')
    const spot = { x: mine.x0 + 1, y: FLOOR, z: mine.z0 + 3 }
    await standAt(ctx, admin, 'Visitor', { x: mine.x0 + 2, y: FLOOR, z: mine.z0 + 2 })
    await ctx.eventually(async () => assert(Number(await ctx.entityData(admin, 'Visitor', 'playerGameType')) === 0, 'the owner decorates in survival'))
    assert(await place(ctx, admin, 'Visitor', spot, 'minecraft:oak_planks'), 'the owner decorates their own cell')
    const other = cells.find((c) => c.id !== mine.id)
    assert(!await place(ctx, admin, 'Visitor', { x: other.x0 + 1, y: FLOOR, z: other.z0 + 3 }, 'minecraft:oak_planks'), 'not someone else\'s cell')
    assert(!await place(ctx, admin, 'Visitor', { x: 15, y: FLOOR, z: 25 }, 'minecraft:oak_planks'), 'not the hub')
    assert(!await place(ctx, admin, 'Visitor', { x: mine.x0 + 2, y: FLOOR, z: mine.z0 - 1 }, 'minecraft:oak_planks'), 'not the hallway')
  }
}
