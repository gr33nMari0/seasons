// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A55. A gate is open by default; an admin shuts one cell's gate, then a whole gallery's; a gate
// waits until nobody is in its cell; an owner shuts their own gate and an admin opens it; in bounds
// mode nobody inside a cell is moved out when it closes (questions 250 and 251). c1's gate stands
// in its doorway: the plane x = 25, beside c1's east side (c1 spans x 20 to 24).
import { assert } from '../lib/context.mjs'
import { FLOOR, buildGallery, inside } from '../lib/hall.mjs'
import { waitBlocks } from '../lib/hub.mjs'
import { middle, move, standAt } from '../lib/visits.mjs'
import { GALLERY_FILE } from '../lib/wand.mjs'

const GATE = { x1: 25, y1: FLOOR, z1: 23, x2: 25, y2: FLOOR + 1, z2: 25 }
const BARS = 'minecraft:iron_bars'
const AIR = 'minecraft:air'

export default {
  name: 'a55-gates-no-moves',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    await ctx.join('Visitor')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const [c1, c2] = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, `/hof cell anchor season-2:c1 gate ${GATE.x1} ${GATE.y1} ${GATE.z1}`, /^Added a gate anchor to season-2:c1 at 25, -60, 23\.$/)
    await ctx.command(admin, `/hof cell anchor season-2:c1 gate ${GATE.x2} ${GATE.y2} ${GATE.z2}`, /^Added a gate anchor to season-2:c1 at 25, -59, 25\.$/)
    ctx.writeFile(GALLERY_FILE(), ctx.readFile(GALLERY_FILE()).replace(/^cells:/m, 'access: gate\ncells:'))
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // Open by default, even with nobody owning the cell and the window shut.
    await ctx.sync(admin, [], 40)
    await waitBlocks(ctx, admin, GATE, AIR)

    // An admin shuts c1's gate; with a visitor in the cell it waits for them to leave.
    await standAt(ctx, admin, 'Visitor', { x: 22, y: FLOOR, z: 24 })
    const waiting = ctx.waitForMessages(admin, /^The gate of season-2:c1 shuts once nobody is in the cell\.$/)
    await ctx.command(admin, '/hof cell gate season-2:c1 shut', /^The gate of season-2:c1 is shut\.$/)
    await waiting
    await ctx.sync(admin, [], 60)
    await waitBlocks(ctx, admin, GATE, AIR)
    assert(inside(c1, await ctx.position(admin, 'Visitor')), 'the visitor wasn\'t moved out')
    await move(ctx, admin, 'Visitor', middle({ x: 27, y: FLOOR, z: 21 }), 'walk')
    await waitBlocks(ctx, admin, GATE, BARS)

    // A whole gallery's gates open and shut.
    await ctx.command(admin, '/hof gallery gate season-2 open', /^Every gate in season-2 is open\.$/)
    await waitBlocks(ctx, admin, GATE, AIR)
    await ctx.command(admin, '/hof gallery gate season-2 shut', /^Every gate in season-2 is shut\.$/)
    await waitBlocks(ctx, admin, GATE, BARS)

    // An owner opens and shuts their own gate; an admin opens it.
    await ctx.command(admin, '/hof cell assign season-2:c1 Owner', /^Assigned season-2:c1 to Owner for Season 2\.$/)
    await ctx.command(owner, '/hof gate open', /^Your cell's gate is open\.$/)
    await waitBlocks(ctx, admin, GATE, AIR)
    await ctx.command(owner, '/hof gate shut', /^Your cell's gate is shut\.$/)
    await waitBlocks(ctx, admin, GATE, BARS)
    await ctx.command(admin, '/hof cell gate season-2:c1 open', /^The gate of season-2:c1 is open\.$/)
    await waitBlocks(ctx, admin, GATE, AIR)
    const visitorBot = ctx.bots.find((b) => b.username === 'Visitor')
    await ctx.command(visitorBot, '/hof gate shut', /^You have no cell with a gate\.$/)

    // Bounds mode: an owner decorating inside their cell isn't moved out when the window closes.
    ctx.writeFile(GALLERY_FILE(), ctx.readFile(GALLERY_FILE()).replace(/^access: gate$/m, 'access: bounds'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/hof cell unassign season-2:c1', /^season-2:c1 has no owner for Season 2 now\.$/)
    await ctx.command(admin, '/hof cell assign season-2:c2 Owner', /^Assigned season-2:c2 to Owner for Season 2\.$/)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    await standAt(ctx, admin, 'Owner', { x: c2.x0 + 2, y: FLOOR, z: c2.z0 - 1 })
    await move(ctx, admin, 'Owner', middle({ x: c2.x0 + 2, y: FLOOR, z: c2.z0 + 2 }), 'walk')
    await ctx.eventually(async () => assert(inside(c2, await ctx.position(admin, 'Owner')), 'the owner walked into their cell'))
    const moved = ctx.quiet(owner, /gallery entry/)
    await ctx.command(admin, '/season decorate close', /^The decorating window for Season 2 is closed\.$/)
    await ctx.sync(admin, [owner], 40)
    moved.verify()
    assert(inside(c2, await ctx.position(admin, 'Owner')), 'the owner is still in their cell')
  }
}
