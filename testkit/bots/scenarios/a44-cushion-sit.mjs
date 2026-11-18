// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A44 (sitting). A visitor can sit on a cushion in someone else's cell, and on nothing else in
// the Hall. Placing and breaking cushions comes with the Hall's deny-by-default rules (M2 part 2),
// and no-build zones with M3.
import { assert } from '../lib/context.mjs'
import { FLOOR, cellOf } from '../lib/hall.mjs'
import { HOME, bed, hallToVisit, hof, sit, standAt, waitInHall } from '../lib/visits.mjs'

export default {
  name: 'a44-cushion-sit',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await ctx.join('Owner')
    const cells = await hallToVisit(ctx, admin)
    await ctx.command(admin, '/hof cell assign season-2:c2 Owner', /^Assigned /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    const seat = { x: cell.x0 + 1, y: FLOOR, z: cell.z0 + 3 }
    const pig = { x: cell.x0 + 3, y: FLOOR, z: cell.z0 + 3 }
    await ctx.command(admin, `/summon minecraft:cushion ${seat.x + 0.5} ${seat.y} ${seat.z + 0.5}`, /^Summoned /)
    await ctx.command(admin, `/summon minecraft:pig ${pig.x + 0.5} ${pig.y} ${pig.z + 0.5} {NoAI:1b,Tags:["a44"]}`, /^Summoned /)

    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await standAt(ctx, admin, 'Visitor', HOME)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitInHall(ctx, admin, 'Visitor')
    await standAt(ctx, admin, 'Visitor', { x: cell.x0 + 2, y: FLOOR, z: cell.z0 + 3 })

    const refused = ctx.waitForMessages(visitor, /^You can't ride in the Hall of Fame\.$/)
    assert(!await sit(ctx, admin, 'Visitor', 'minecraft:pig', pig), 'no riding a pig in the Hall')
    await refused
    assert(await sit(ctx, admin, 'Visitor', 'minecraft:cushion', seat), 'sitting on a cushion in someone else\'s cell')
    const seated = await ctx.command(admin, '/execute as Visitor on vehicle if entity @s[type=minecraft:cushion]', /^Test (passed|failed)/)
    assert(seated.startsWith('Test passed'), 'the visitor is seated on the cushion')
  }
}
