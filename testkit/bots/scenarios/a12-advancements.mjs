// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A12. A player's advancements at season end are in their archive, the plaque in their cell shows
// "completed / visible total", and their advancement screen is empty afterwards.
import { assert } from '../lib/context.mjs'
import { at, buildGallery, cellOf, selectorData } from '../lib/hall.mjs'

export default {
  name: 'a12-advancements',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Achiever')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/advancement grant Achiever from minecraft:story/root', /^Granted \d+ advancements to Achiever$/)
    await ctx.command(admin, '/advancement grant Achiever only minecraft:adventure/root', /^Granted the advancement/)
    const cells = await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: /)
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Achiever')
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    const archive = await ctx.archive(admin, 'Achiever', 2)
    assert(archive.completed >= 17 && archive.visible > archive.completed, `the archive counts the advancements: ${JSON.stringify(archive)}`)
    const text = await ctx.eventually(async () => {
      const found = await selectorData(ctx, admin, at('minecraft:text_display', cell.plaque), 'text')
      assert(found, 'the plaque stands in the cell')
      return found
    })
    assert(text.includes('Achiever') && text.includes('Season 2'), `the plaque names the player and season: ${text}`)
    assert(text.includes(`${archive.completed} / ${archive.visible}`), `the plaque shows ${archive.completed} / ${archive.visible}: ${text}`)
    for (const id of ['story/root', 'story/enter_the_end', 'adventure/root']) {
      assert(!await ctx.holds(admin, `entity @a[name=Achiever,advancements={minecraft:${id}=true}]`), `${id} is revoked`)
    }
  }
}
