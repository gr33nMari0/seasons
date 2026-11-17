// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A12 (M1 part 2's half; the plaque showing the count comes with the Hall in part 3). A player's
// advancements at season end are in their archive, with the completed / visible count, and their
// advancement screen is empty afterwards.
import { assert } from '../lib/context.mjs'

export default {
  name: 'a12-advancements',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Achiever')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/advancement grant Achiever from minecraft:story/root', /^Granted \d+ advancements to Achiever$/)
    await ctx.command(admin, '/advancement grant Achiever only minecraft:adventure/root', /^Granted the advancement/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    const archive = await ctx.archive(admin, 'Achiever', 2)
    assert(archive.completed >= 17 && archive.visible > archive.completed, `the archive counts the advancements: ${JSON.stringify(archive)}`)
    for (const id of ['story/root', 'story/enter_the_end', 'adventure/root']) {
      assert(!await ctx.holds(admin, `entity @a[name=Achiever,advancements={minecraft:${id}=true}]`), `${id} is revoked`)
    }
  }
}
