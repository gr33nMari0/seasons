// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A21. Two players running /hof swap at each other within 60 s swap cells; one alone changes
// nothing.
import { assert } from '../lib/context.mjs'
import { buildGallery, owners } from '../lib/hall.mjs'

export default {
  name: 'a21-swap',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const sam = await ctx.join('Sam')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 3 })

    await ctx.command(alex, '/hof swap Sam', /^Cells can only be swapped while the decorating window is open\.$/)
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 3 players have cells\.$/)
    const before = await owners(ctx, admin, 'season-2', cells)
    const alexCell = Object.keys(before).find((c) => before[c] === 'Alex')
    const samCell = Object.keys(before).find((c) => before[c] === 'Sam')

    // One alone changes nothing.
    const asked = ctx.waitForMessages(sam, /^Alex wants to swap Hall of Fame cells with you\. Run \/hof swap Alex within 60 seconds to agree\.$/)
    await ctx.command(alex, '/hof swap Sam', /^Asked Sam to swap cells\. They have 60 seconds to run \/hof swap Alex\.$/)
    await asked
    assert(JSON.stringify(await owners(ctx, admin, 'season-2', cells)) === JSON.stringify(before), 'one request alone changes nothing')
    await ctx.command(alex, '/hof swap Alex', /^You can't swap with yourself\.$/)

    // Both within 60 s: swapped.
    const alexDone = ctx.waitForMessages(alex, new RegExp(`^You swapped cells with Sam\\. Your cell is now ${samCell}\\.$`))
    await ctx.command(sam, '/hof swap Alex', new RegExp(`^You swapped cells with Alex\\. Your cell is now ${alexCell}\\.$`))
    await alexDone
    const after = await owners(ctx, admin, 'season-2', cells)
    assert(after[alexCell] === 'Sam' && after[samCell] === 'Alex', `swapped: ${JSON.stringify(after)}`)
  }
}
