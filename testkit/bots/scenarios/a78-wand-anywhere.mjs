// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A78. 2,000 blocks from the Hall and in the Nether, an admin's wand click on a button opens the
// link menu, and "To the Hall spawn" makes it a working link at once; another block put aside with
// the wand is taken by /hof link add with no from; a wand click on a block that is a trigger
// already makes no second link; a holder of only seasons.admin.hof gets the same menu (M8 plan,
// In-game tools).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HALL_SPAWN } from '../lib/hub.mjs'
import { WELCOME, icons, linkInFile, pick, setBlock, wandMenu } from '../lib/links.mjs'
import { hallToVisit, middle, standAt, waitNear } from '../lib/visits.mjs'
import { click, giveWand } from '../lib/wand.mjs'
import { forceload, permit } from '../lib/zones.mjs'

const FAR = { x: 2000, y: FLOOR, z: 0 }
const ASIDE = { x: 2003, y: FLOOR, z: 0 }
const NETHER = { x: 8, y: 120, z: 8 }

export default {
  name: 'a78-wand-anywhere',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await forceload(ctx, admin, FAR, 8)
    await giveWand(ctx, admin)

    // Far from the Hall: a button becomes a link to the Hall from the menu.
    await setBlock(ctx, admin, FAR, 'minecraft:stone_button[face=floor]')
    await standAt(ctx, admin, ctx.adminName, { x: FAR.x + 1, y: FLOOR, z: FAR.z })
    const menu = await wandMenu(ctx, admin, admin, FAR)
    assert(icons(menu).includes('lodestone') && icons(menu).includes('paper'), `the link menu: ${icons(menu)}`)
    assert(!icons(menu).includes('respawn_anchor'), 'no Hall spot is offered outside the Hall')
    await pick(ctx, admin, 'lodestone', /^Link l1 leads to the Hall spawn, from the block at 2000, -60, 0, clicked\.$/)
    assert(linkInFile(ctx, 'l1').to === 'hall', 'l1 leads to the Hall')
    await standAt(ctx, admin, 'Visitor', { x: FAR.x - 1, y: FLOOR, z: FAR.z })
    const welcomed = ctx.waitForMessages(visitor, WELCOME)
    await click(ctx, admin, 'Visitor', FAR)
    await welcomed
    await waitNear(ctx, admin, 'Visitor', middle(HALL_SPAWN))

    // Clicking the trigger again with the wand makes no second link.
    const line = await click(ctx, admin, ctx.adminName, FAR)
    assert(/\(the click was taken over\)$/.test(line), `the wand's click is taken over: ${line}`)
    assert(!linkInFile(ctx, 'l2'), 'no second link')

    // Put aside, then /hof link add takes it.
    await setBlock(ctx, admin, ASIDE, 'minecraft:oak_sign')
    await wandMenu(ctx, admin, admin, ASIDE)
    await pick(ctx, admin, 'paper', /^The block at 2003, -60, 0 is put aside: /)
    await ctx.command(admin, '/hof link add s3-back to hall', /^Link s3-back leads to the Hall spawn, from the block at 2003, -60, 0, clicked\.$/)

    // In the Nether.
    await ctx.command(admin, '/execute in minecraft:the_nether run forceload add 0 0 16 16', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/execute in minecraft:the_nether run setblock ${NETHER.x} ${NETHER.y} ${NETHER.z} minecraft:stone_button[face=floor]`, /^(Changed the block|Could not set)/)
    await ctx.command(admin, `/execute in minecraft:the_nether run tp ${ctx.adminName} ${NETHER.x + 1}.5 ${NETHER.y} ${NETHER.z}.5`, /^Teleported /)
    await wandMenu(ctx, admin, admin, NETHER)
    await pick(ctx, admin, 'lodestone', /^Link l2 leads to the Hall spawn, from the block at 8, 120, 8, clicked\.$/)
    assert(/the_nether/.test(JSON.stringify(linkInFile(ctx, 'l2').triggers)), `l2 is in the Nether: ${JSON.stringify(linkInFile(ctx, 'l2'))}`)
    await standAt(ctx, admin, ctx.adminName, HALL_SPAWN)

    // seasons.admin.hof alone opens the same menu.
    await permit(ctx, admin, 'Visitor', 'seasons.admin.hof')
    await giveWand(ctx, visitor)
    await standAt(ctx, admin, 'Visitor', { x: FAR.x + 5, y: FLOOR, z: FAR.z })
    const theirs = await wandMenu(ctx, admin, visitor, { x: FAR.x + 5, y: FLOOR - 1, z: FAR.z })
    assert(icons(theirs).includes('lodestone'), `a Hall admin gets the link menu: ${icons(theirs)}`)
    visitor.closeWindow(theirs)
  }
}
