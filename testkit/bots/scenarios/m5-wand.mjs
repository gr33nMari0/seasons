// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The wand (M5, questions 226 to 229 and 236): /hof wand for Hall admins only. Sneaking, a left
// click and a right click select two corners; the first selection inside the gallery of the cell
// just made by /hof cell new becomes its bounds, once, with the same refusals as /hof cell bounds
// (bounds never leave an anchor outside), and
// /hof cell bounds or /hof gallery bounds with no corners take it. A plain right click opens the
// anchor menu, and each choice writes what /hof cell anchor writes. A wand click never breaks or
// opens anything; a plain blaze rod, or a wand held by someone who isn't a Hall admin, is just an
// item. A museum's anchor menu offers an egg plinth for a season without one. The selection is
// forgotten at logout.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { writeMuseum } from '../lib/museum.mjs'
import { standAt } from '../lib/visits.mjs'
import { anchorMenu, blockIs, cellInFile, choices, click, giveWand, pickAnchor, select } from '../lib/wand.mjs'
import { forceload } from '../lib/zones.mjs'

const GROUND = FLOOR - 1
const TAKEN = /\(the click was taken over\)$/
const SEASONS_LINES = /^(Selected |Corner \d|That block isn't in any cell|Gave you the Seasons wand)/

export default {
  name: 'm5-wand',
  async run (ctx) {
    let admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await forceload(ctx, admin, { x: 40, z: 30 }, 30)
    await ctx.command(admin, '/hof gallery create season-2 2', /^Created gallery season-2 for Season 2\.$/)
    await ctx.command(admin, `/hof gallery bounds season-2 20 ${GROUND} 20 60 -50 40`, /^Gallery season-2 now spans /)
    await ctx.command(admin, `/hof gallery entry season-2 21 ${FLOOR} 21`, /^Gallery season-2's entry is now at /)
    // The admin faces south (yaw 0), so anchors picked with the wand face back at them: yaw 180.
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 40, y: FLOOR, z: 38 })
    await ctx.command(admin, `/tp SeasonsAdmin 40.5 ${FLOOR} 38.5 0 0`, /^Teleported /)

    // Only Hall admins get a wand.
    await ctx.command(pat, '/hof wand', /./)
    assert(await ctx.itemCount(admin, 'Pat') === 0, 'Pat got no wand')
    await giveWand(ctx, admin)

    // Two corners make a selection; with no cell named yet it is only a selection.
    const firstCorner = ctx.waitForMessages(admin, /^Corner 1: 50, -61, 22$/)
    await click(ctx, admin, 'SeasonsAdmin', { x: 50, y: GROUND, z: 22 }, { left: true, sneak: true })
    await firstCorner
    await select(ctx, admin, admin, { x: 50, y: GROUND, z: 22 }, { x: 54, y: -55, z: 26 },
      /^Selected 50, -61, 22 to 54, -55, 26 in minecraft:overworld: 5 × 7 × 5 blocks\.$/)
    // The clicks were Seasons': nothing broken.
    assert(TAKEN.test(await click(ctx, admin, 'SeasonsAdmin', { x: 50, y: GROUND, z: 22 }, { left: true, sneak: true })), 'a wand click is taken over')
    assert(await blockIs(ctx, admin, { x: 50, y: GROUND, z: 22 }, 'minecraft:grass_block'), 'the corner block is still there')

    // After /hof cell new, a selection inside the gallery becomes that cell's bounds.
    await ctx.command(admin, '/hof cell new season-2 c1', /^Created cell c1 in gallery season-2\.$/)
    await Promise.all([
      ctx.waitForMessages(admin, /^Cell season-2:c1 now spans 22, -61, 22 to 26, -55, 30\.$/),
      select(ctx, admin, admin, { x: 22, y: GROUND, z: 22 }, { x: 26, y: -55, z: 30 })
    ])
    assert(JSON.stringify(cellInFile(ctx, 'c1').bounds) === JSON.stringify({ min: [22, -61, 22], max: [26, -55, 30] }), 'c1\'s bounds are in the file')
    // Once only: a second selection in the gallery is just a selection, and c1 keeps its bounds.
    const once = ctx.quiet(admin, /^Cell season-2:c1 /)
    await select(ctx, admin, admin, { x: 44, y: GROUND, z: 22 }, { x: 48, y: -55, z: 30 })
    await ctx.sync(admin, [admin])
    once.verify()
    assert(JSON.stringify(cellInFile(ctx, 'c1').bounds) === JSON.stringify({ min: [22, -61, 22], max: [26, -55, 30] }), 'a second selection left c1 where it was')

    // The same refusals as the command: an overlap, then a selection reaching outside the gallery,
    // which stays only a selection until the command is asked to use it.
    await ctx.command(admin, '/hof cell new season-2 c2', /^Created cell c2 in gallery season-2\.$/)
    await Promise.all([
      ctx.waitForMessages(admin, /^Cell season-2:c2 would overlap season-2:c1\.$/),
      select(ctx, admin, admin, { x: 24, y: GROUND, z: 24 }, { x: 28, y: -55, z: 30 })
    ])
    assert(cellInFile(ctx, 'c2').bounds === null, 'the overlap left c2 without bounds')
    const quiet = ctx.quiet(admin, /^Cell season-2:c2 /)
    await select(ctx, admin, admin, { x: 58, y: GROUND, z: 22 }, { x: 64, y: -55, z: 30 })
    await ctx.sync(admin, [admin])
    quiet.verify()
    await ctx.command(admin, '/hof cell bounds season-2:c2', /^Cell season-2:c2 must sit inside gallery season-2\.$/)
    await Promise.all([
      ctx.waitForMessages(admin, /^Cell season-2:c2 now spans 28, -61, 22 to 32, -55, 30\.$/),
      select(ctx, admin, admin, { x: 28, y: GROUND, z: 22 }, { x: 32, y: -55, z: 30 })
    ])

    // /hof gallery bounds with no corners takes the selection.
    await select(ctx, admin, admin, { x: 19, y: -62, z: 19 }, { x: 61, y: -49, z: 41 })
    await ctx.command(admin, '/hof gallery bounds season-2', /^Gallery season-2 now spans 19, -62, 19 to 61, -49, 41\.$/)

    // Anchors from the menu. Archive and plaque go on the block clicked; the rest in front of the
    // face clicked.
    await ctx.command(admin, `/setblock 23 ${FLOOR} 29 minecraft:barrel`, /^Changed the block/)
    await ctx.command(admin, `/setblock 25 ${FLOOR} 28 minecraft:lectern`, /^Changed the block/)
    await ctx.command(admin, `/setblock 22 ${FLOOR + 1} 29 minecraft:stone`, /^Changed the block/)
    await ctx.command(admin, `/setblock 25 ${FLOOR} 22 minecraft:stone`, /^Changed the block/)
    const menu = await anchorMenu(ctx, admin, admin, { x: 23, y: FLOOR, z: 29 })
    assert(/Anchor in season-2:c1/.test(JSON.stringify(menu.title)), `the menu is titled: ${JSON.stringify(menu.title)}`)
    assert(JSON.stringify(choices(menu)) === JSON.stringify(['figure', 'archive', 'plaque', 'trophy', 'entry', 'gate']), `the menu offers every anchor type: ${choices(menu)}`)
    await pickAnchor(ctx, admin, 'archive', /^Added an archive anchor to season-2:c1 at 23, -60, 29\.$/)
    await anchorMenu(ctx, admin, admin, { x: 24, y: GROUND, z: 28 })
    await pickAnchor(ctx, admin, 'figure', /^Added a figure anchor to season-2:c1 at 24, -60, 28\.$/)
    await anchorMenu(ctx, admin, admin, { x: 25, y: FLOOR, z: 28 })
    await pickAnchor(ctx, admin, 'plaque', /^Added a plaque anchor to season-2:c1 at 25, -60, 28\.$/)
    await anchorMenu(ctx, admin, admin, { x: 22, y: FLOOR + 1, z: 29 }, 'east')
    await pickAnchor(ctx, admin, 'trophy', /^Added a trophy anchor to season-2:c1 at 23, -59, 29\.$/)
    await anchorMenu(ctx, admin, admin, { x: 24, y: GROUND, z: 24 })
    await pickAnchor(ctx, admin, 'entry', /^Added an entry anchor to season-2:c1 at 24, -60, 24\.$/)
    await anchorMenu(ctx, admin, admin, { x: 23, y: GROUND, z: 22 })
    await pickAnchor(ctx, admin, 'gate', /^Added a gate anchor to season-2:c1 at 23, -60, 22\.$/)
    await anchorMenu(ctx, admin, admin, { x: 25, y: FLOOR, z: 22 })
    await pickAnchor(ctx, admin, 'gate', /^Added a gate anchor to season-2:c1 at 25, -59, 22\.$/)
    await ctx.command(admin, '/hof cell info season-2:c1', /^Cell season-2:c1: free for Season 2; anchors: figure 1, archive 1, plaque 1, trophy 1, entry 1, gate 2$/)
    // /hof cell info doesn't hand c1 to the wand, and no bounds may leave an anchor outside.
    const untouched = ctx.quiet(admin, /^Cell season-2:c1 /)
    await select(ctx, admin, admin, { x: 44, y: GROUND, z: 22 }, { x: 48, y: -55, z: 30 })
    await ctx.sync(admin, [admin])
    untouched.verify()
    await ctx.command(admin, '/hof cell bounds season-2:c1', /^Cell season-2:c1's \w+ anchor at -?\d+, -?\d+, -?\d+ would be outside those bounds\. Move or remove it first\.$/)
    assert(JSON.stringify(cellInFile(ctx, 'c1').bounds) === JSON.stringify({ min: [22, -61, 22], max: [26, -55, 30] }), 'c1 kept the bounds around its anchors')
    // Clicking the barrel opens the anchor menu, not the barrel.
    const barrel = await anchorMenu(ctx, admin, admin, { x: 23, y: FLOOR, z: 29 })
    assert(/Anchor in season-2:c1/.test(JSON.stringify(barrel.title)), `the barrel click is the wand's: ${JSON.stringify(barrel.title)}`)
    admin.closeWindow(barrel)

    // The wand writes what the command writes: the same anchors by command on c2, 6 blocks east.
    for (const a of cellInFile(ctx, 'c1').anchors) {
      await ctx.command(admin, `/hof cell anchor season-2:c2 ${a.type} ${a.x + 6} ${a.y} ${a.z} 180`, /^Added an? \w+ anchor to season-2:c2 at /)
    }
    const shifted = cellInFile(ctx, 'c1').anchors.map((a) => ({ ...a, x: a.x + 6 }))
    assert(JSON.stringify(cellInFile(ctx, 'c2').anchors) === JSON.stringify(shifted), `the wand's anchors read as the command's: ${JSON.stringify(cellInFile(ctx, 'c1').anchors)} vs ${JSON.stringify(cellInFile(ctx, 'c2').anchors)}`)
    assert(cellInFile(ctx, 'c1').anchors.every((a) => a.yaw === 180), 'the wand\'s anchors face the admin')

    // Removing an anchor offers itself only where there is one.
    const again = await anchorMenu(ctx, admin, admin, { x: 23, y: FLOOR, z: 29 })
    assert(choices(again).includes('remove'), `remove is offered on an anchor: ${choices(again)}`)
    await pickAnchor(ctx, admin, 'remove', /^Removed the archive anchor at 23, -60, 29 from season-2:c1\.$/)
    assert(!cellInFile(ctx, 'c1').anchors.some((a) => a.type === 'archive'), 'the archive anchor is gone')

    // Outside every cell.
    await Promise.all([
      ctx.waitForMessages(admin, /^That block isn't in any cell\.$/),
      click(ctx, admin, 'SeasonsAdmin', { x: 40, y: GROUND, z: 35 })
    ])

    // A museum offers an egg plinth for a season that has none (Seasons 2 and 3 have theirs).
    await writeMuseum(ctx, admin)
    await ctx.command(admin, '/hof cell new museum extra', /^Created cell extra in gallery museum\.$/)
    await select(ctx, admin, admin, { x: 201, y: GROUND, z: 307 }, { x: 203, y: -55, z: 309 })
    await ctx.command(admin, '/hof cell bounds museum:extra', /^Cell museum:extra now spans 201, -61, 307 to 203, -55, 309\.$/)
    const museum = await anchorMenu(ctx, admin, admin, { x: 202, y: GROUND, z: 308 })
    assert(choices(museum).includes('egg'), `the museum's menu offers an egg plinth: ${choices(museum)}`)
    await pickAnchor(ctx, admin, 'egg', /^Added an egg plinth for Season 1 to museum:extra at 202, -60, 308\.$/)
    assert(/egg: 1/.test(ctx.readFile('world/dimensions/minecraft/overworld/seasons/galleries/museum.yml')), 'the egg plinth is in the museum file')
    await ctx.command(admin, '/season validate', /^All config files are valid\.$/)

    // A wand in the hands of someone who isn't a Hall admin, and a plain blaze rod, are just items.
    await forceload(ctx, admin, { x: 100, z: 100 }, 4)
    await ctx.command(admin, `/setblock 100 ${FLOOR} 100 minecraft:chest`, /^Changed the block/)
    await ctx.command(admin, '/give Pat minecraft:blaze_rod[custom_data={PublicBukkitValues:{"seasons:wand":1b}}]', /^Gave 1 /)
    const patQuiet = ctx.quiet(pat, SEASONS_LINES)
    assert(!TAKEN.test(await click(ctx, admin, 'Pat', { x: 100, y: FLOOR, z: 100 })), 'Pat\'s click opens the chest as usual')
    await click(ctx, admin, 'Pat', { x: 100, y: GROUND, z: 101 }, { left: true, sneak: true })
    await ctx.sync(admin, [pat])
    patQuiet.verify()
    await ctx.command(admin, '/item replace entity SeasonsAdmin weapon.mainhand with minecraft:blaze_rod', /^Replaced /)
    const adminQuiet = ctx.quiet(admin, SEASONS_LINES)
    assert(!TAKEN.test(await click(ctx, admin, 'SeasonsAdmin', { x: 100, y: FLOOR, z: 100 })), 'a plain blaze rod is no wand')
    await ctx.sync(admin, [admin])
    adminQuiet.verify()

    // The selection is forgotten at logout.
    await ctx.command(admin, '/clear SeasonsAdmin', /^Removed /)
    await giveWand(ctx, admin)
    await select(ctx, admin, admin, { x: 34, y: GROUND, z: 22 }, { x: 38, y: -55, z: 30 })
    await ctx.leave(admin)
    admin = await ctx.admin()
    await ctx.command(admin, '/hof cell new season-2 c3', /^Created cell c3 /)
    await ctx.command(admin, '/hof cell bounds season-2:c3', /^Give both corners, or select them with \/hof wand first\.$/)
  }
}
