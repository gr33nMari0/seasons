// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A14. The season card has the highlights, every General-tab statistic as a season difference, the
// player's firsts and their furthest block. It opens as a book from the plaque anchor in their cell.
import { SECOND, assert } from '../lib/context.mjs'
import { buildGallery, cellOf, place, use } from '../lib/hall.mjs'

export default {
  name: 'a14-season-card',
  async run (ctx) {
    const admin = await ctx.admin()
    const card = await ctx.join('Carda')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Carda')

    const first = ctx.waitForMessages(card, /^Season first! Carda completed /, 15 * SECOND)
    await ctx.command(admin, '/advancement grant Carda only minecraft:story/mine_stone', /^Granted the advancement/)
    await first
    await ctx.command(admin, '/forceload add 496 -4 504 4', /^(Marked|No chunks)/)
    assert(await place(ctx, admin, 'Carda', { x: 500, y: -60, z: 0 }), 'Carda places a block far out')

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    const line = await ctx.command(admin, '/scenario card Carda 2', /^Card of Carda for Season 2: /)
    const lines = line.replace(/^Card of Carda for Season 2: /, '').split(' | ')
    ctx.log(lines.join('\n'))
    for (const expected of [/^Carda$/, /^Season 2$/, /^Played \d{4}-\d\d-\d\d to \d{4}-\d\d-\d\d$/,
      /^Play time: \d/, /^Deaths: \d+$/, /^Mobs killed: \d+$/, /^Players killed: \d+$/, /^Travelled: [\d,]+ blocks$/,
      /^Diamonds mined: \d+$/, /^Ancient debris mined: \d+$/, /^Blocks placed: [\d,]+$/, /^Blocks mined: [\d,]+$/,
      /^Advancements: \d+ \/ \d+$/, /^Furthest block: 500 blocks, at 500, 0$/, /^Ended at -?\d+, -?\d+, -?\d+ in minecraft:overworld$/,
      /^Season firsts$/, /^completed \[Stone Age\]$/, /^Statistics$/, /^minecraft:leave_game: \d+$/, /^minecraft:play_time: /, /^minecraft:open_chest: \d+$/]) {
      assert(lines.some((l) => expected.test(l)), `the card has a line matching ${expected}`)
    }
    const general = lines.filter((l) => /^minecraft:[a-z_]+: /.test(l))
    assert(general.length >= 40, `every General-tab statistic is on the card: ${general.length}`)

    // The plaque anchor opens it as a book, with no item involved.
    await ctx.eventually(async () => assert(await ctx.itemCount(admin, 'Carda') === 0, 'archived'))
    const opened = new Promise((resolve) => card._client.once('open_book', resolve))
    await use(ctx, admin, 'Carda', cell.plaque)
    await Promise.race([opened, new Promise((_resolve, reject) => setTimeout(() => reject(new Error('no book opened')), 10 * SECOND))])
    assert(await ctx.itemCount(admin, 'Carda') === 0, 'reading the card leaves nothing behind')
  }
}
