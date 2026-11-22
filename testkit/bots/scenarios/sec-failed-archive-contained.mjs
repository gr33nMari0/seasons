// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Security review H2 and H3. An archive that fails after its vault page is sealed, because what the
// player carries changed between the copy and the clear, is put back at once: the page is removed
// and the attempt closed, so the ender chest never exists twice. The player isn't let go with
// everything they carry: they are held, their commands wait, and Seasons archives them again.
import { assert } from '../lib/context.mjs'
import { ENDER_ITEMS, GEAR_ITEMS, expectEmpty, giveGear } from '../lib/gear.mjs'

export default {
  name: 'sec-failed-archive-contained',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await giveGear(ctx, admin, 'Alex')
    await ctx.command(admin, '/scenario act-at VAULT_SEALED Alex give', /^Armed give at VAULT_SEALED for Alex/)

    const failed = ctx.waitForMessages(admin, /^Archiving Alex into Season 2 stopped: /, 20_000)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await failed
    await ctx.waitForMessages(admin, /^Season 2 has ended\. Welcome to the Intermission\.$/, 20_000)

    // Put back: no open attempt, no sealed page, everything still on Alex.
    const after = await ctx.archive(admin, 'Alex', 2)
    assert(after.archivedThrough < 2, `Alex isn't archived yet: ${JSON.stringify(after)}`)
    assert(after.pending === 0, `the failed attempt is closed: ${JSON.stringify(after)}`)
    assert(after.vault === 0, `no vault page holds a copy of the ender chest: ${JSON.stringify(after)}`)
    assert(await ctx.itemCount(admin, 'Alex') === GEAR_ITEMS + 1, 'Alex still carries the gear and the stone')
    // Held meanwhile: no commands, so nothing they carry can move.
    await ctx.command(alex, '/season info', /^Seasons is moving your items right now, so commands wait a moment\.$/)

    // Seasons tries again.
    await ctx.command(admin, '/scenario clock +15s', /./)
    await ctx.eventually(async () => {
      const archive = await ctx.archive(admin, 'Alex', 2)
      assert(archive.archivedThrough === 2, `Alex is archived on the retry: ${JSON.stringify(archive)}`)
    }, 20_000)
    const archive = await ctx.archive(admin, 'Alex', 2)
    assert(archive.items === GEAR_ITEMS + 1, `the archive holds the gear and the stone: ${JSON.stringify(archive)}`)
    assert(archive.vault === ENDER_ITEMS, `the ender chest is in the vault once: ${JSON.stringify(archive)}`)
    assert(archive.pending === 0, 'nothing waits for recovery')
    await expectEmpty(ctx, admin, 'Alex', assert)
  }
}
