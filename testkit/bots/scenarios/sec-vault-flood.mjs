// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Security review M2. A burst of clicks on a vault item with a full inventory is not a burst of
// transactions: a full inventory is refused before anything is journalled or written, and at most
// one withdrawal runs, with one more waiting, per player.
import { assert } from '../lib/context.mjs'
import { openVault, raw, slotOf } from '../lib/vault.mjs'

const CLICKS = 200

async function journalEntries (ctx, admin) {
  const line = await ctx.command(admin, '/season status', /^Journal: /)
  return Number(/^Journal: (\d+) entries/.exec(line)[1])
}

export default {
  name: 'sec-vault-flood',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/scenario vault-return Pat minecraft:diamond 3', /^Returned 3 × minecraft:diamond to Pat's vault$/)
    await ctx.command(admin, '/give Pat minecraft:stone 2304', /^Gave 2304 /)
    const before = await journalEntries(ctx, admin)

    const window = await openVault(ctx, pat, /^Click an item to take it\.$/)
    const slot = slotOf(window, 'diamond')
    assert(slot >= 0, 'the Returned page shows the diamonds')
    await raw(pat, window, Array.from({ length: CLICKS }, () => [slot, 0, 0]))
    await ctx.sync(admin, [pat], 20)

    const after = await journalEntries(ctx, admin)
    assert(after === before, `${CLICKS} clicks with a full inventory journalled ${after - before} entries`)
    assert((await ctx.archive(admin, 'Pat', 2)).returned === 3, 'the diamonds are still in the vault')
    assert(await ctx.itemCount(admin, 'Pat') === 2304, 'Pat got nothing')
    await ctx.command(admin, '/season status', /^Journal: .*, 0 waiting for recovery$/)
  }
}
