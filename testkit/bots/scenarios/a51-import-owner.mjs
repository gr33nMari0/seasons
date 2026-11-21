// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A51. A cell whose stand has no head, or a head of someone else, is flagged by the dry run and
// skipped by the import until an admin assigns its owner. The cells are tagged as the spec's import
// steps say, with /hof cell new and the wand's two corners for each cell's bounds (M5; A50 keeps
// tagging by command).
import { assert } from '../lib/context.mjs'
import { blockItems, buildSeasonOneCell } from '../lib/hall.mjs'
import { giveWand, select } from '../lib/wand.mjs'

const REPORT = 'world/dimensions/minecraft/overworld/seasons/import-season-1.txt'

export default {
  name: 'a51-import-owner',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Owner')
    await ctx.join('Kim')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    const good = await buildSeasonOneCell(ctx, admin, { x: 60, z: 40, head: 'Owner', chests: 1 })
    const headless = await buildSeasonOneCell(ctx, admin, { x: 80, z: 40, head: null, chests: 1 })
    // Stranger has never played here.
    const stranger = await buildSeasonOneCell(ctx, admin, { x: 100, z: 40, head: 'Stranger', chests: 1 })

    await ctx.command(admin, '/hof gallery create season-1 1', /^Created gallery season-1 for Season 1\.$/)
    await ctx.command(admin, '/hof gallery bounds season-1 58 -61 38 116 -50 50', /^Gallery season-1 now spans /)
    await giveWand(ctx, admin)
    for (const [id, x] of [['good', 59], ['headless', 79], ['stranger', 99]]) {
      await ctx.command(admin, `/hof cell new season-1 ${id}`, new RegExp(`^Created cell ${id} in gallery season-1\\.$`))
      await Promise.all([
        ctx.waitForMessages(admin, new RegExp(`^Cell season-1:${id} now spans ${x}, -61, 39 to ${x + 16}, -52, 49\\.$`)),
        select(ctx, admin, admin, { x, y: -61, z: 39 }, { x: x + 16, y: -52, z: 49 })
      ])
    }

    await ctx.command(admin, '/hof import season-1 --dry-run', /^Dry run of the season-1 import: 1 cell ready, 2 flagged\. See import-season-1\.txt\.$/, 20_000)
    const report = ctx.readFile(REPORT)
    ctx.log(report)
    assert(/Cell good: ready/.test(report), 'the good cell is ready')
    assert(/Cell headless: flagged: the stand has no player head\b/.test(report), 'the headless cell is flagged')
    assert(/Cell stranger: flagged: the player head is Stranger's \(.*\), who has never played here\b/.test(report), 'the stranger\'s head is flagged')

    const stockedHeadless = await blockItems(ctx, admin, headless.chests[0])
    const stockedStranger = await blockItems(ctx, admin, stranger.chests[0])
    await ctx.command(admin, '/hof import season-1', /^Imported 1 cell from season-1; 2 skipped\.$/, 20_000)
    assert(await blockItems(ctx, admin, good.chests[0]) === null, 'the good cell is imported')
    assert(await blockItems(ctx, admin, headless.chests[0]) === stockedHeadless, 'the headless cell is skipped')
    assert(await blockItems(ctx, admin, stranger.chests[0]) === stockedStranger, 'the stranger cell is skipped')
    assert(!(await ctx.archive(admin, 'Stranger', 1)).present, 'nothing is archived for Stranger')

    // Once an admin names the owner, the cell imports.
    await ctx.command(admin, '/hof cell assign season-1:headless Kim', /^Assigned season-1:headless to Kim for Season 1\.$/)
    await ctx.command(admin, '/hof import season-1 headless --dry-run', /^Dry run of the season-1 import: 1 cell ready, 0 flagged\. /, 20_000)
    assert(/Cell headless: ready[\s\S]*owner: Kim \(.*\), set by an admin/.test(ctx.readFile(REPORT)), 'the admin\'s owner is used')
    await ctx.command(admin, '/hof import season-1 headless', /^Imported 1 cell from season-1; 0 skipped\.$/, 20_000)
    assert(await blockItems(ctx, admin, headless.chests[0]) === null, 'the headless cell is imported for Kim')
    assert((await ctx.archive(admin, 'Kim', 1)).present, 'Kim has a Season 1 record')
    assert(await blockItems(ctx, admin, stranger.chests[0]) === stockedStranger, 'the stranger cell still waits')
  }
}
