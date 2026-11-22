// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A74. A legacy gallery of barrier-sealed cells with real chests and armour stands loads without
// figure anchors, draws nothing, is never assigned or locked, and isn't a shortfall; a visitor and a
// creative builder see its chests as view-only copies and can't touch the stands; /season end and
// /season launch fill the Season 2 gallery beside it; /hof hall check lists it as legacy
// (questions 281).
import { assert } from '../lib/context.mjs'
import { FLOOR, breakBlock, buildGallery, buildSeasonOneCell, selectorData } from '../lib/hall.mjs'
import { hit, openContainer } from '../lib/protection.mjs'
import { standAt } from '../lib/visits.mjs'

const X = 61
const Z = 21

export default {
  name: 'a74-legacy-gallery',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    await ctx.join('Visitor')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })

    // Season 1's wing, by hand: a stand in its owner's gear and head, a chest of diamonds, barriers.
    const built = await buildSeasonOneCell(ctx, admin, { x: X, z: Z, head: 'Visitor', chests: 1 })
    await ctx.command(admin, `/fill ${X - 1} ${FLOOR} ${Z - 1} ${X + 11} ${FLOOR + 3} ${Z - 1} minecraft:barrier`, /^Successfully filled /)
    await ctx.command(admin, '/hof gallery create season-1 1 --legacy', /^Created gallery season-1 for Season 1, kept as built \(legacy\)\.$/)
    await ctx.command(admin, `/hof gallery bounds season-1 ${X - 1} ${FLOOR - 1} ${Z - 1} ${X + 15} ${FLOOR + 10} ${Z + 9}`, /^Gallery season-1 now spans /)
    await ctx.command(admin, '/hof cell new season-1 a', /^Created cell a /)
    await ctx.command(admin, `/hof cell bounds season-1:a ${X} ${FLOOR - 1} ${Z} ${X + 10} ${FLOOR + 6} ${Z + 8}`, /^Cell season-1:a now spans /)
    // A legacy gallery of the season being played too, which takes no part in it.
    await ctx.command(admin, '/hof gallery create old-2 2 --legacy', /^Created gallery old-2 /)
    await ctx.command(admin, `/hof gallery bounds old-2 90 ${FLOOR - 1} 20 100 ${FLOOR + 10} 30`, /^Gallery old-2 now spans /)
    await ctx.command(admin, '/hof cell new old-2 b', /^Created cell b /)
    await ctx.command(admin, `/hof cell bounds old-2:b 91 ${FLOOR - 1} 21 97 ${FLOOR + 6} 27`, /^Cell old-2:b now spans /)
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // Not a shortfall, and no figures asked for.
    await ctx.command(admin, '/season end --dry-run', /^Hall of Fame: 2 free cells in Season 2's galleries for 2 players still without one\.$/)
    const quiet = ctx.quiet(admin, /^Cell (season-1:a|old-2:b) has no figure\.$/)
    await ctx.command(admin, '/hof hall check', [/^Gallery season-1 is legacy: /, /^Gallery old-2 is legacy: /])
    await ctx.sync(admin)
    quiet.verify()

    // Its chest is view-only for a visitor and for a creative builder, and stays; so does the stand.
    const chest = built.chests[0]
    await standAt(ctx, admin, 'Visitor', { x: X + 3, y: FLOOR, z: Z + 5 })
    assert(await openContainer(ctx, admin, 'Visitor', chest) === 'copy', 'the visitor sees a copy')
    await ctx.command(admin, `/gamemode creative ${me}`, /game mode/)
    await standAt(ctx, admin, me, { x: X + 3, y: FLOOR, z: Z + 5 })
    assert(await openContainer(ctx, admin, me, chest) === 'copy', 'a builder sees a copy too')
    assert(!await breakBlock(ctx, admin, me, chest), 'a builder can\'t break a chest with items in it')
    await hit(ctx, admin, me, 'minecraft:armor_stand', built.stand)
    const stand = `@e[type=armor_stand,tag=s1x${X},limit=1]`
    assert(await selectorData(ctx, admin, stand, 'equipment.chest') !== null, 'the stand keeps its chestplate')
    await ctx.command(admin, `/gamemode survival ${me}`, /game mode/)

    // The season ends and the next one launches with the legacy galleries left as they are.
    await ctx.command(admin, '/season spawn set at 300 -60 300', /^Season 3's spawn is now /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/hof gallery info old-2', /^Cell b: free$/)
    await ctx.command(admin, '/hof gallery info season-2', [/^Cell c1: \S+$/, /^Cell c2: \S+$/])
    assert(!await ctx.holds(admin, `entity @e[type=mannequin,x=${X - 1},y=${FLOOR - 1},z=${Z - 1},dx=16,dy=12,dz=10]`), 'nothing drawn in the legacy wing')
    await ctx.command(admin, '/season launch', /Season 3 has begun/, 30_000)
    assert(await selectorData(ctx, admin, stand, 'equipment.chest') !== null, 'the stand is still as built')
    await ctx.command(admin, '/hof import season-1 --dry-run', /^Dry run of the season-1 import: 1 cell ready/)
  }
}
