// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A92. With a legacy Season 1 gallery with an entry and a season Season 2 gallery, /hof 1 lands at
// Season 1's entry and /hof 2 at Season 2's; tab completion offers both; with the legacy entry
// removed, /hof 1 says the gallery has no entry yet (an op also gets the next step) and lands
// nowhere; /hof 7 says Season 7 has no gallery; /hof tp gallery season-1 lands there; the legacy
// gallery is still never assigned or counted in the dry run (M8 plan, Visiting legacy galleries).
import { assert } from '../lib/context.mjs'
import { suggests } from '../lib/commands.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HOME, hallToVisit, hof, middle, standAt, waitNear } from '../lib/visits.mjs'

const S1 = { x1: 60, z1: 40, x2: 75, z2: 50 }
const S1_ENTRY = { x: 62, y: FLOOR, z: 42 }
const S2_ENTRY = { x: 21, y: FLOOR, z: 21 }

export default {
  name: 'a92-visit-legacy-gallery',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await ctx.command(admin, '/season config set hof.entry.requireBed false', /^Set hof\.entry\.requireBed to false in config\.yml/)
    await ctx.command(admin, `/forceload add ${S1.x1} ${S1.z1} ${S1.x2} ${S1.z2}`, /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/hof gallery create season-1 1 --legacy', /^Created gallery season-1 for Season 1, kept as built \(legacy\)\.$/)
    await ctx.command(admin, `/hof gallery bounds season-1 ${S1.x1} ${FLOOR - 1} ${S1.z1} ${S1.x2} ${FLOOR + 10} ${S1.z2}`, /^Gallery season-1 now spans /)
    await ctx.command(admin, '/hof cell new season-1 a', /^Created cell a /)
    await ctx.command(admin, `/hof cell bounds season-1:a 64 ${FLOOR - 1} 44 70 ${FLOOR + 6} 49`, /^Cell season-1:a now spans /)
    await ctx.command(admin, `/hof gallery entry season-1 ${S1_ENTRY.x} ${S1_ENTRY.y} ${S1_ENTRY.z}`, /^Gallery season-1's entry is now at /)

    // /hof 1 lands at the legacy gallery's entry, /hof 2 at Season 2's.
    await standAt(ctx, admin, 'Visitor', HOME)
    await hof(ctx, visitor, '/hof 1', /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(S1_ENTRY))
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(HOME))
    await hof(ctx, visitor, '/hof 2', /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/)
    await waitNear(ctx, admin, 'Visitor', middle(S2_ENTRY))
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)

    // Tab completion offers both seasons.
    await suggests(visitor, '/hof ', ['1', '2'])

    // With the legacy entry gone, /hof 1 says so; the op also gets the next step.
    await ctx.command(admin, '/hof gallery entry season-1 none', /^Gallery season-1 has no entry now\.$/)
    await hof(ctx, visitor, '/hof 1', /^Season 1's gallery has no entry yet\.$/)
    assert(!(await ctx.position(admin, 'Visitor')).x.toString().startsWith('6'), 'the visitor stays at home')
    await waitNear(ctx, admin, 'Visitor', middle(HOME))
    await standAt(ctx, admin, ctx.adminName, HOME)
    await hof(ctx, admin, '/hof 1', [/^Season 1's gallery has no entry yet\.$/, /^Next: \/hof gallery entry season-1/])

    // A season with no gallery at all.
    await hof(ctx, visitor, '/hof 7', /^Season 7 has no gallery to visit\.$/)

    // An admin still goes there.
    await ctx.command(admin, `/hof gallery entry season-1 ${S1_ENTRY.x} ${S1_ENTRY.y} ${S1_ENTRY.z}`, /^Gallery season-1's entry is now at /)
    await ctx.command(admin, '/hof tp gallery season-1', /^You're there\.$/)
    await waitNear(ctx, admin, ctx.adminName, middle(S1_ENTRY))

    // The legacy gallery stays out of the draw and the dry run.
    await ctx.command(admin, '/season end --dry-run', /^Hall of Fame: 2 free cells in Season 2's galleries for 2 players still without one\.$/)
  }
}
