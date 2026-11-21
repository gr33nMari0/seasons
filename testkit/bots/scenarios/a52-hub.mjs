// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A52. Two wand corners mark the hub; the Hall spawn is refused outside it; plain /hof always
// lands at the Hall spawn and is refused with none set; walking from the hub down a wing and back
// changes nothing; a visitor can't change anything in the hub (questions 243 and 244). The hub is
// beside the gallery's west side, so the two touch.
import { assert } from '../lib/context.mjs'
import { FLOOR, breakBlock, buildGallery, gameMode, place, use } from '../lib/hall.mjs'
import { HUB, hallFile, inBox } from '../lib/hub.mjs'
import { HOME, bed, hof, middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { giveWand, select } from '../lib/wand.mjs'

const SPAWN = { x: 12, y: FLOOR, z: 25 }
const TABLE = { x: 10, y: FLOOR, z: 22 }

export default {
  name: 'a52-hub',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    const opal = await ctx.join('Opal')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, `/forceload add ${HUB.x1} ${HUB.z1} ${HUB.x2} ${HUB.z2}`, /^(Marked|No chunks were marked)/)
    await ctx.command(admin, `/forceload add ${HOME.x - 8} ${HOME.z - 8} ${HOME.x + 8} ${HOME.z + 8}`, /^(Marked|No chunks were marked)/)
    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await standAt(ctx, admin, 'Visitor', HOME)

    // No Hall spawn: plain /hof is refused, and doesn't fall back to the gallery's entry.
    await hof(ctx, visitor, '/hof', /^The Hall of Fame has no spawn yet\.$/)
    // The spawn needs a hub first.
    await ctx.command(admin, `/hof hall spawn ${SPAWN.x} ${SPAWN.y} ${SPAWN.z}`, /^Mark the Hall's hub first, with \/hof hall hub\.$/)

    // Two wand corners mark the hub; it can't overlap the gallery.
    await giveWand(ctx, admin)
    await select(ctx, admin, admin, { x: HUB.x1, y: HUB.y1, z: HUB.z1 }, { x: HUB.x2, y: HUB.y2, z: HUB.z2 })
    await ctx.command(admin, '/hof hall hub', new RegExp(`^The Hall's hub now spans ${HUB.x1}, ${HUB.y1}, ${HUB.z1} to ${HUB.x2}, ${HUB.y2}, ${HUB.z2}\\.$`))
    await ctx.command(admin, `/hof hall hub ${HUB.x1} ${HUB.y1} ${HUB.z1} 25 ${HUB.y2} ${HUB.z2}`, /^The hub can't overlap gallery season-2\.$/)

    // The Hall spawn goes inside the hub only.
    await ctx.command(admin, `/hof hall spawn 30 ${FLOOR} 25`, /^The Hall spawn must be inside the hub\.$/)
    await ctx.command(admin, `/hof hall spawn ${SPAWN.x} ${SPAWN.y} ${SPAWN.z} 90`, /^The Hall spawn is now at 12, -60, 25\.$/)
    const file = hallFile(ctx)
    assert(JSON.stringify(file.hub) === JSON.stringify({ min: [HUB.x1, HUB.y1, HUB.z1], max: [HUB.x2, HUB.y2, HUB.z2] }), `hall.yml has the hub: ${JSON.stringify(file)}`)
    assert(JSON.stringify(file.spawn.pos) === JSON.stringify([SPAWN.x, SPAWN.y, SPAWN.z]) && file.spawn.yaw === 90, `hall.yml has the Hall spawn: ${JSON.stringify(file)}`)
    // A hub that would leave the spawn outside it is refused.
    await ctx.command(admin, `/hof hall hub ${HUB.x1} ${HUB.y1} ${HUB.z1} ${HUB.x2} ${HUB.y2} 23`, /^The hub must hold the Hall spawn at 12, -60, 25; move the spawn first\.$/)

    // Plain /hof lands at the Hall spawn, in adventure mode.
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(SPAWN))
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Visitor') === 2, 'the visitor is in adventure mode in the hub'))

    // Walking from the hub down the wing and back is staying in the Hall.
    const quiet = ctx.quiet(visitor, /^You left the Hall/)
    await move(ctx, admin, 'Visitor', { x: 25.5, y: FLOOR, z: 21.5 }, 'walk')
    await ctx.sync(admin, [visitor], 20)
    await move(ctx, admin, 'Visitor', middle(SPAWN), 'walk')
    await ctx.sync(admin, [visitor], 20)
    quiet.verify()
    assert(inBox(HUB, await ctx.position(admin, 'Visitor')), 'the visitor is back in the hub')
    assert(await gameMode(ctx, admin, 'Visitor') === 2, 'still in adventure mode')

    // Nothing in the hub changes for a visitor.
    await ctx.command(admin, `/setblock ${TABLE.x} ${TABLE.y} ${TABLE.z} minecraft:crafting_table`, /^Changed the block/)
    assert(!await place(ctx, admin, 'Visitor', { x: 13, y: FLOOR, z: 25 }), 'a visitor can\'t place in the hub')
    assert(!await breakBlock(ctx, admin, 'Visitor', { x: 13, y: FLOOR - 1, z: 25 }), 'a visitor can\'t break the hub\'s floor')
    const refused = ctx.waitForMessages(visitor, /^You can't use that in the Hall of Fame\.$/)
    await use(ctx, admin, 'Visitor', TABLE)
    await refused
    assert(await ctx.holds(admin, `block ${TABLE.x} ${TABLE.y} ${TABLE.z} minecraft:crafting_table`), 'the crafting table stays')

    // Nor for an op in survival without the bypass.
    await ctx.command(admin, '/op Opal', /^Made Opal a server operator$/)
    await standAt(ctx, admin, 'Opal', { x: 14, y: FLOOR, z: 27 })
    assert(!await place(ctx, admin, 'Opal', { x: 15, y: FLOOR, z: 27 }), 'an op in survival can\'t place in the hub')
    assert(opal)

    // /hof leave takes the visitor home.
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(HOME))
    assert(await gameMode(ctx, admin, 'Visitor') === 0, 'back in survival')
  }
}
