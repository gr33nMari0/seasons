// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A36. The ender vault is read-only before Homecoming and withdraw-only for everything after it,
// collectibles included. Also: the Returned page can be withdrawn from at once, a full inventory
// leaves the item in the vault, nothing can be put in, withdrawals survive a restart, and between
// seasons the vault is read-only again.
import { assert } from '../lib/context.mjs'
import { ENDER_COUNT, HOTBAR_START, NEXT, carried, expectReadOnly, fillEnderChest, openVault, slotOf, snapshot, take, turnTo } from '../lib/vault.mjs'
import { endSeason2 } from '../lib/zones.mjs'

const OWN_DIRT = HOTBAR_START + 2

export default {
  name: 'a36-vault',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const pat = await ctx.join('Pat')
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await fillEnderChest(ctx, admin, 'Pat')
      await endSeason2(ctx, admin)
      assert((await ctx.archive(admin, 'Pat', 2)).vault === ENDER_COUNT, 'the ender chest is sealed in the vault')
      await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
      await ctx.command(admin, '/item replace entity Pat hotbar.2 with minecraft:dirt 10', /^Replaced \d+ slot/)

      // Frontier: read-only, whatever the click.
      let window = await openVault(ctx, pat, /^Your ender vault is sealed until Homecoming: you can look, but not take\.$/)
      const emerald = slotOf(window, 'emerald')
      assert(emerald >= 0 && slotOf(window, 'dragon_egg') >= 0 && slotOf(window, 'music_disc_cat') >= 0, `the Season 2 page shows the ender chest: ${JSON.stringify(snapshot(window).view)}`)
      await expectReadOnly(ctx, admin, pat, emerald, OWN_DIRT)
      pat.closeWindow(pat.currentWindow)

      // The Returned page can be taken from at once.
      await ctx.command(admin, '/scenario vault-return Pat minecraft:diamond 3', /^Returned 3 × minecraft:diamond to Pat's vault$/)
      window = await openVault(ctx, pat, /^Your ender vault is sealed until Homecoming/)
      await turnTo(ctx, pat, NEXT, 'diamond')
      const took = ctx.waitForMessages(pat, /^Took 3 × minecraft:diamond from your vault\.$/)
      await take(ctx, admin, pat, 'diamond', 3)
      await took
      await ctx.eventually(async () => assert(slotOf(pat.currentWindow, 'diamond') < 0, 'the diamonds are gone from the vault'))
      pat.closeWindow(pat.currentWindow)
      assert((await ctx.archive(admin, 'Pat', 2)).vault === ENDER_COUNT, 'the Season 2 page is untouched')

      await ctx.command(admin, '/season unlock homecoming', /^Homecoming! /)

      // A full inventory: the emeralds stay in the vault.
      await ctx.command(admin, '/clear Pat', /^Removed /)
      await ctx.command(admin, '/give Pat minecraft:stone 2304', /^Gave 2304 /)
      window = await openVault(ctx, pat, /^Click an item to take it\.$/)
      const full = ctx.waitForMessages(pat, /^Your inventory is full, so it stays in your vault\.$/)
      await pat.clickWindow(slotOf(window, 'emerald'), 0, 0)
      await full
      await ctx.sync(admin, [pat], 4)
      assert(await carried(ctx, admin, 'Pat', 'emerald') === 0, 'Pat got no emeralds')
      assert(await ctx.itemCount(admin, 'Pat') === 2304, 'Pat carries only the stone')
      assert(!await ctx.holds(admin, 'entity @e[type=item]'), 'nothing was dropped')
      assert((await ctx.archive(admin, 'Pat', 2)).vault === ENDER_COUNT, 'the emeralds are still in the vault')
      assert(slotOf(pat.currentWindow, 'emerald') >= 0, 'the page still shows the emeralds')

      // Nothing goes in: a shift-click from Pat's own inventory moves nothing.
      const before = snapshot(pat.currentWindow)
      await pat.clickWindow(HOTBAR_START, 0, 1)
      await pat._syncWindow(pat.currentWindow)
      await ctx.sync(admin, [pat], 2)
      assert(JSON.stringify(snapshot(pat.currentWindow).view) === JSON.stringify(before.view), 'the page is unchanged')
      assert(await ctx.itemCount(admin, 'Pat') === 2304, 'Pat still carries all the stone')
      pat.closeWindow(pat.currentWindow)

      // With room, everything comes out, collectibles included.
      await ctx.command(admin, '/clear Pat', /^Removed /)
      await openVault(ctx, pat, /^Click an item to take it\.$/)
      await take(ctx, admin, pat, 'emerald', 5)
      await take(ctx, admin, pat, 'music_disc_cat', 1)
      await take(ctx, admin, pat, 'dragon_egg', 1)
      await ctx.eventually(async () => assert(snapshot(pat.currentWindow).view.every((item) => item == null), `the page is empty: ${JSON.stringify(snapshot(pat.currentWindow).view)}`))
      pat.closeWindow(pat.currentWindow)
      assert((await ctx.archive(admin, 'Pat', 2)).vault === 0, 'the vault page is empty')
      await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const pat = await ctx.join('Pat')
      // After a restart, what was taken stays taken.
      assert((await ctx.archive(admin, 'Pat', 2)).vault === 0, 'the vault page is still empty')
      assert(await carried(ctx, admin, 'Pat', 'emerald') === 5 && await carried(ctx, admin, 'Pat', 'dragon_egg') === 1, 'Pat still has the emeralds and the egg')
      await ctx.command(pat, '/vault', /^Your ender vault is empty\.$/)

      // Between seasons the vault is read-only again, the new page included.
      await ctx.command(admin, '/item replace entity Pat enderchest.0 with minecraft:emerald 2', /^Replaced \d+ slot/)
      await ctx.command(admin, '/season end --now', /^Ending Season 3 now/)
      await ctx.waitForMessages(admin, /^Season 3 has ended\./, 20_000)
      assert((await ctx.archive(admin, 'Pat', 3)).vault === 2, 'the Season 3 page is sealed')
      await ctx.command(admin, '/item replace entity Pat hotbar.2 with minecraft:dirt 10', /^Replaced \d+ slot/)
      const window = await openVault(ctx, pat, /^Your ender vault is read-only until the next season starts\.$/)
      const emerald = slotOf(window, 'emerald')
      assert(emerald >= 0, `the Season 3 page comes first: ${JSON.stringify(snapshot(window).view)}`)
      await expectReadOnly(ctx, admin, pat, emerald, OWN_DIRT)
      pat.closeWindow(pat.currentWindow)
    }
  ]
}
