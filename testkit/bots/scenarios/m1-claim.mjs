// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// claim assignment (questions 167): when the window opens players are told to claim a cell rather
// than given one; /hof claim <cell> takes a free one, /hof claim alone the one they stand in; a
// taken cell, a second claim and a claim outside the window are refused; whoever never claims gets
// a free cell when they are archived; claims are audited.
import { assert } from '../lib/context.mjs'
import { buildGallery, owners } from '../lib/hall.mjs'
import { audit } from '../lib/protection.mjs'
import { standAt } from '../lib/visits.mjs'

const FILE = 'world/dimensions/minecraft/overworld/seasons/galleries/season-2.yml'
const TOLD = /^Claim your Hall of Fame cell for Season 2 with \/hof claim \[cell\]\.$/

export default {
  name: 'm1-claim',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const sam = await ctx.join('Sam')
    const kim = await ctx.join('Kim')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 4 })
    ctx.writeFile(FILE, ctx.readFile(FILE).replace(/^cells:/m, 'assignment: claim\ncells:'))
    await ctx.command(admin, '/season reload', /^Reloaded /)

    await ctx.command(alex, '/hof claim c1', /^Cells can only be claimed while the decorating window is open\.$/)

    const told = [alex, sam, kim].map((bot) => ctx.waitForMessages(bot, TOLD))
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 0 players have cells\.$/)
    await Promise.all(told)

    await ctx.command(alex, '/hof claim c1', /^Your Hall of Fame cell for Season 2 is c1 in season-2\.$/)
    await ctx.command(sam, '/hof claim c1', /^c1 is taken\.$/)
    await ctx.command(alex, '/hof claim c3', /^You already have a cell, c1\. Swap it with \/hof swap instead\.$/)
    await ctx.command(sam, '/hof claim', /^Free cells: c2, c3, c4\. Claim one with \/hof claim \[cell\]\.$/)
    await standAt(ctx, admin, 'Sam', cells[1].entry)
    await ctx.command(sam, '/hof claim', /^Your Hall of Fame cell for Season 2 is c2 in season-2\.$/)

    let held = await owners(ctx, admin, 'season-2', cells)
    assert(held.c1 === 'Alex' && held.c2 === 'Sam' && held.c3 === null && held.c4 === null, `the claims are recorded: ${JSON.stringify(held)}`)

    // Kim and the admin never claim: they get the free cells when they are archived.
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    held = await owners(ctx, admin, 'season-2', cells)
    assert(Object.values(held).includes('Kim'), `Kim has a cell after archiving: ${JSON.stringify(held)}`)

    const claims = audit(ctx).filter((e) => e.action === 'claim')
    assert(claims.some((e) => e.actor === 'Alex' && e.target === 'season-2:c1'), `Alex's claim is audited: ${JSON.stringify(claims)}`)
    assert(claims.some((e) => e.actor === 'Sam' && e.target === 'season-2:c2'), `Sam's claim is audited: ${JSON.stringify(claims)}`)
  }
}
