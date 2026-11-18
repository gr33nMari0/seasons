// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A20. Opening the decorating window gives everyone who played the season a random free cell and
// tells them which; a player who first plays later gets one at next login. The window opens by
// itself decorating.opensBefore (7 days) before the planned end.
import { DAY, assert, isoIn } from '../lib/context.mjs'
import { buildGallery, owners } from '../lib/hall.mjs'

const TOLD = /^Your Hall of Fame cell for Season 2 is (c\d) in season-2\.$/

export default {
  name: 'a20-assignment',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const sam = await ctx.join('Sam')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 4 })
    assert(Object.values(await owners(ctx, admin, 'season-2', cells)).every((o) => o === null), 'every cell starts free')

    // A planned end 6 days away is inside the 7-day window, so the window opens at once.
    const alexTold = ctx.waitForMessages(alex, TOLD, 15_000)
    const samTold = ctx.waitForMessages(sam, TOLD, 15_000)
    await ctx.command(admin, `/season schedule end ${isoIn(6 * DAY)}`, /^Season 2 now ends at /)
    const [alexLine, samLine] = await Promise.all([alexTold, samTold])
    const alexCell = TOLD.exec(alexLine)[1]
    const samCell = TOLD.exec(samLine)[1]
    assert(alexCell !== samCell, `Alex and Sam have different cells: ${alexCell}, ${samCell}`)

    const assigned = await owners(ctx, admin, 'season-2', cells)
    assert(assigned[alexCell] === 'Alex' && assigned[samCell] === 'Sam', `the gallery records the owners: ${JSON.stringify(assigned)}`)
    assert(Object.values(assigned).filter(Boolean).length === 3, `Alex, Sam and the admin have cells: ${JSON.stringify(assigned)}`)

    // A player who first plays now gets the last free cell at login.
    const free = cells.find((c) => assigned[c.id] === null).id
    const late = ctx.join('Late')
    const lateBot = await late
    const lateLine = await ctx.waitForMessages(lateBot, TOLD, 15_000)
    assert(TOLD.exec(lateLine)[1] === free, `Late gets the free cell ${free}: ${lateLine}`)
    assert((await owners(ctx, admin, 'season-2', cells))[free] === 'Late', 'Late owns it')
  }
}
