// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A89. An admin left-clicks a gallery's east side with the wand, which takes the gallery's box
// to edit with that face picked; sneak-scrolling up three times moves it out 3, a step of 10 moves
// it out 10 and back 10; /hof sel apply writes it once, and /hof undo puts it back; growing it
// into the hub is refused whole; expand, contract and shift by command; the same for a link's area
// and a point's box (M8 plan, Nudging boxes).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { link, linkInFile } from '../lib/links.mjs'
import { hallToVisit, standAt } from '../lib/visits.mjs'
import { GALLERY_FILE, click, giveWand, readYaml } from '../lib/wand.mjs'

const gallery = (ctx) => readYaml(ctx.readFile(GALLERY_FILE())).bounds

/** Scrolls the hotbar up (a step back) or down, sneaking, as the wheel would. */
async function scroll (ctx, admin, bot, up) {
  const to = (bot.quickBarSlot + (up ? 8 : 1)) % 9
  await ctx.command(admin, `/scenario scroll ${bot.username} ${to} sneak`, /^Scrolled /)
}

export default {
  name: 'a89-nudge-faces',
  async run (ctx) {
    const admin = await ctx.admin()
    await hallToVisit(ctx, admin, { sealed: null })
    await giveWand(ctx, admin)
    await standAt(ctx, admin, ctx.adminName, { x: 34, y: FLOOR, z: 21 })
    const before = gallery(ctx)
    assert(before.max[0] === 38, `the gallery ends at x 38: ${JSON.stringify(before)}`)

    // Left-click its east side: the gallery's box, east face.
    await click(ctx, admin, ctx.adminName, { x: 38, y: FLOOR, z: 21 }, { left: true, face: 'east' })
    await ctx.command(admin, '/hof sel info', /^Editing gallery season-2: 20, -61, 20 to 38, -50, 30, 19 × 12 × 11, face \+x, step 1\.$/)
    for (let i = 0; i < 3; i++) await scroll(ctx, admin, admin, true)
    await ctx.command(admin, '/hof sel step 10', /^The wand's step is 10\.$/)
    await scroll(ctx, admin, admin, true)
    await scroll(ctx, admin, admin, false)
    await ctx.command(admin, '/hof sel info', /^Editing gallery season-2: 20, -61, 20 to 41, -50, 30, 22 × 12 × 11, face \+x, step 10\.$/)
    assert(gallery(ctx).max[0] === 38, 'nothing is written before apply')
    await ctx.command(admin, '/hof sel apply', /^Gallery season-2 now spans 20, -61, 20 to 41, -50, 30\.$/)
    assert(gallery(ctx).max[0] === 41, 'written once applied')
    await ctx.command(admin, '/hof undo', /^Put galleries\/season-2\.yml back /)
    assert(gallery(ctx).max[0] === 38, 'and undone')

    // Into the hub: refused whole.
    await ctx.command(admin, '/hof sel edit gallery season-2', /^Editing gallery season-2: /)
    await ctx.command(admin, '/hof sel expand 2 -x', /^Editing gallery season-2: 18, -61, 20 to 38, -50, 30, /)
    await ctx.command(admin, '/hof sel apply', /overlap/)
    assert(gallery(ctx).min[0] === 20, 'nothing changed')
    await ctx.command(admin, '/hof sel cancel', /^Edit cancelled; gallery season-2 is as it was\.$/)

    // By command: expand, contract and shift the selection.
    await ctx.command(admin, '/hof sel pos1 100 -60 100', /^Corner 1: /)
    await ctx.command(admin, '/hof sel pos2 102 -60 102', /^Selected /)
    await ctx.command(admin, '/hof sel expand 2 up', /^Selection: 100, -60, 100 to 102, -58, 102, 3 × 3 × 3/)
    await ctx.command(admin, '/hof sel contract 1 -x', /^Selection: 101, -60, 100 to 102, -58, 102, 2 × 3 × 3/)
    await ctx.command(admin, '/hof sel shift 4 north', /^Selection: 101, -60, 96 to 102, -58, 98, 2 × 3 × 3/)
    await ctx.command(admin, '/hof sel contract 5 +x', /^A face can't pass the opposite one\.$/)

    // A link's area and a point's box.
    await link(ctx, admin, 'add door from 9 -60 27 9 -58 29 to hall', /^Link door leads to /)
    await ctx.command(admin, '/hof sel edit link door', /^Editing link door: 9, -60, 27 to 9, -58, 29, /)
    await ctx.command(admin, '/hof sel expand 1 up', /^Editing link door: 9, -60, 27 to 9, -57, 29, /)
    await ctx.command(admin, '/hof sel apply', /^Link door's area is now 9, -60, 27 to 9, -57, 29\.$/)
    await ctx.eventually(async () => assert(linkInFile(ctx, 'door').triggers[0].area.max[1] === -57, 'the area grew'))
    await ctx.command(admin, '/hof point add yard 140 -60 140 142 -60 142', /^Point yard spans /)
    await ctx.command(admin, '/hof sel edit point yard', /^Editing point yard: /)
    await ctx.command(admin, '/hof sel expand 3 +z', /^Editing point yard: 140, -60, 140 to 142, -60, 145, /)
    await ctx.command(admin, '/hof sel apply', /^Point yard spans 140, -60, 140 to 142, -60, 145\.$/)
  }
}
