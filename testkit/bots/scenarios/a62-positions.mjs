// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A62. Positions can be typed absolute, relative or as `here`, and boxes as two positions or the
// selection: each lands where typed; a typed spawn inside a zone or the Hall is still refused; the
// console's relative position is refused and its absolute one works (questions 264 and 265).
import { FLOOR } from '../lib/hall.mjs'
import { HOME, hallToVisit, standAt } from '../lib/visits.mjs'

const IN_HUB = { x: 10, y: FLOOR, z: 25 }
const FREE = { x: 32, y: FLOOR, z: 22 }

export default {
  name: 'a62-positions',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    await hallToVisit(ctx, admin, { sealed: null })
    await standAt(ctx, admin, me, HOME)

    // The next season's spawn and candidates, typed and relative.
    await ctx.command(admin, '/season spawn set at 193 -60 110', /^Season 3's spawn is now 193, -60, 110 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season spawn candidate add west ~-20 ~ ~', /^Added spawn candidate west for Season 3 at 80, -60, 100 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season spawn candidate move west ~ ~ ~5', /^Moved spawn candidate west of Season 3 to 100, -60, 105 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season spawn candidate add 1abc', /^Candidate names start with a letter/)
    await ctx.command(admin, '/season spawn candidate add mid here', /^Added spawn candidate mid for Season 3 at 100, -60, 100 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season spawn candidate remove mid', /^Removed spawn candidate mid from Season 3\.$/)

    // ^ is along the sender's facing: yaw -90 faces +x.
    await ctx.command(admin, `/tp ${me} ${HOME.x + 0.5} ${HOME.y} ${HOME.z + 0.5} -90 0`, /^Teleported /)
    await ctx.command(admin, '/season spawn candidate add east ^ ^ ^10', /^Added spawn candidate east for Season 3 at 110, -60, 100 in minecraft:overworld\.$/)

    // A zone box from relative corners, and a typed spawn inside it refused.
    await ctx.command(admin, '/season zone add base box ~-3 ~-3 ~-3 ~3 ~3 ~3', /^Added zone base: /)
    await ctx.command(admin, '/season spawn set at ~1 ~ ~1', /^Season 3's spawn can't be inside zone base; /)

    // The hub and the Hall spawn, relative to an admin standing in the hub.
    await standAt(ctx, admin, me, IN_HUB)
    await ctx.command(admin, '/hof hall spawn ~1 ~ ~ 90', /^The Hall spawn is now at 11, -60, 25\.$/)
    await ctx.command(admin, '/hof hall hub ~-2 ~-1 ~-5 ~9 ~10 ~5', /^The Hall's hub now spans 8, -61, 20 to 19, -50, 30\.$/)
    await ctx.command(admin, '/season spawn set at here', /^Season 3's spawn can't be inside the Hall of Fame\.$/)
    await ctx.command(admin, '/hof hall spawn 18 -60 21 0', /^The Hall spawn is now at 18, -60, 21\.$/)

    // A cell's bounds, the gallery's entry and an anchor, relative to where the admin stands.
    await standAt(ctx, admin, me, FREE)
    await ctx.command(admin, '/hof cell new season-2 c3', /^Created cell c3 in gallery season-2\.$/)
    await ctx.command(admin, '/hof cell bounds season-2:c3 ~ ~-1 ~ ~4 ~6 ~8', /^Cell season-2:c3 now spans 32, -61, 22 to 36, -54, 30\.$/)
    await ctx.command(admin, '/hof gallery entry season-2 here', /^Gallery season-2's entry is now at 32, -60, 22\.$/)
    await ctx.command(admin, '/hof cell anchor season-2:c3 figure ~2 ~ ~6', /^Added a figure anchor to season-2:c3 at 34, -60, 28/)
    await ctx.command(admin, '/hof cell bounds season-2:c3 ~ ~ ~', /^Give a box as two positions/)

    // The console has no position of its own.
    await ctx.command(admin, '/scenario console season spawn set at ~ ~ ~', /^Console: The console has no position: give x y z as numbers\.$/)
    await ctx.command(admin, '/scenario console season spawn set at 300 -60 300', /^Console: Season 3's spawn is now 300, -60, 300 in minecraft:overworld\.$/)
  }
}
