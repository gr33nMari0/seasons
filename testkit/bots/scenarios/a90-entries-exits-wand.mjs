// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A90. From the wand's menu in the Hall, the Hall spawn, a gallery's entry, a Hall exit and a
// gallery exit are set on the block in front of the face clicked, and the Hall spawn, a gallery's
// entry and a Hall exit from the selection; a visitor lands at the new spawn and the new exit
// takes them back (M8 plan, In-game tools).
import { FLOOR } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { pick, wandMenu } from '../lib/links.mjs'
import { HOME, hallToVisit, hof, middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { giveWand, select } from '../lib/wand.mjs'

const GROUND = FLOOR - 1

export default {
  name: 'a90-entries-exits-wand',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/season config set hof.entry.requireBed false', /^Set hof\.entry\.requireBed to false/)
    await giveWand(ctx, admin)
    await standAt(ctx, admin, ctx.adminName, { x: 14, y: FLOOR, z: 27 })

    // The Hall spawn and a Hall exit on a block of the hub.
    await wandMenu(ctx, admin, admin, { x: 12, y: GROUND, z: 25 })
    await pick(ctx, admin, 'respawn_anchor', /^The Hall spawn is now at 12, -60, 25\.$/)
    await wandMenu(ctx, admin, admin, { x: 9, y: GROUND, z: 29 })
    await pick(ctx, admin, 'iron_door', /^Added a Hall exit at 9, -60, 29\.$/)

    // A gallery's entry and exit on a block of the gallery outside every cell.
    await wandMenu(ctx, admin, admin, { x: 37, y: GROUND, z: 21 })
    await pick(ctx, admin, 'oak_door', /^Gallery season-2's entry is now at 37, -60, 21\.$/)
    await wandMenu(ctx, admin, admin, { x: 36, y: GROUND, z: 21 })
    await pick(ctx, admin, 'birch_door', /^Added an exit to gallery season-2 at 36, -60, 21\.$/)

    // The visitor lands at the new spawn and the new exit sends them back.
    await standAt(ctx, admin, 'Visitor', HOME)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitNear(ctx, admin, 'Visitor', middle({ x: 12, y: FLOOR, z: 25 }))
    const back = ctx.waitForMessages(visitor, /^Back where you left\.$/)
    await move(ctx, admin, 'Visitor', middle({ x: 9, y: FLOOR, z: 29 }), 'walk')
    await back
    await waitNear(ctx, admin, 'Visitor', middle(HOME))

    // From the selection: the Hall spawn as a box, a gallery entry as a box, a doorway exit.
    await select(ctx, admin, admin, { x: 16, y: FLOOR, z: 21 }, { x: 18, y: FLOOR, z: 23 })
    await wandMenu(ctx, admin, admin, { x: 13, y: GROUND, z: 25 })
    await pick(ctx, admin, 'crying_obsidian', /^The Hall spawn is now 16, -60, 21 to 18, -60, 23\.$/)
    await select(ctx, admin, admin, { x: 34, y: FLOOR, z: 21 }, { x: 35, y: FLOOR, z: 21 })
    await wandMenu(ctx, admin, admin, { x: 37, y: GROUND, z: 21 })
    await pick(ctx, admin, 'spruce_door', /^Gallery season-2's entry is now 34, -60, 21 to 35, -60, 21\.$/)
    await select(ctx, admin, admin, { x: 8, y: FLOOR, z: 22 }, { x: 8, y: FLOOR + 2, z: 23 })
    await wandMenu(ctx, admin, admin, { x: 13, y: GROUND, z: 25 })
    await pick(ctx, admin, 'iron_trapdoor', /^Added a Hall exit 8, -60, 22 to 8, -58, 23\.$/)
    await standAt(ctx, admin, ctx.adminName, HALL_SPAWN)
  }
}
