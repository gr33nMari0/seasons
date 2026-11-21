// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A42 with the owner offline: a curator's return waits on the owner's Returned page, the owner is
// told with the reason at their next login, and takes it from /vault at once, long before
// Homecoming. A destroy while they are away is told the same way, and an undo too.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, archiveWithGear, curate } from '../lib/curate.mjs'
import { NEXT, carried, openVault, take, turnTo } from '../lib/vault.mjs'

export default {
  name: 'a42-curate-offline',
  async run (ctx) {
    const admin = await ctx.admin()
    let away = await ctx.join('Away')
    await archiveWithGear(ctx, admin, ['Away'])
    await ctx.leave(away)

    await curate(ctx, admin, 'Away', 'diamond_sword', 'return', 'Belongs to you', /^Returned 1 × minecraft:diamond_sword to Away's vault\.$/)
    const destroyed = await curate(ctx, admin, 'Away', 'torch', 'destroy', 'Too many torches', /^Destroyed 32 × minecraft:torch\. \/hof curate undo (\S+) brings it back/)
    const id = /undo (\S+) brings/.exec(destroyed)[1]
    await ctx.command(admin, `/hof curate undo ${id}`, /^Put 32 × minecraft:torch back in Away's Season 2 archive\.$/)
    const record = await ctx.archive(admin, 'Away', 2)
    assert(record.items === GEAR_ITEMS - 1 && record.returned === 1, `the sword waits on the Returned page: ${JSON.stringify(record)}`)

    // Told at the next login, in order, with the reasons.
    const login = ctx.join('Away')
    away = await login
    await ctx.waitForMessages(away, [
      /^A curator returned 1 × minecraft:diamond_sword from your Season 2 archive to your vault's Returned page: Belongs to you$/,
      /^A curator removed 32 × minecraft:torch from your Season 2 archive: Too many torches$/,
      /^A curator put 32 × minecraft:torch back in your Season 2 archive\.$/
    ], 20_000)

    // The Returned page is withdrawable at once, before Homecoming.
    await ctx.command(admin, '/clear Away', /./)
    await openVault(ctx, away, /^Your ender vault is sealed until Homecoming/)
    await turnTo(ctx, away, NEXT, 'diamond_sword')
    await take(ctx, admin, away, 'diamond_sword', 1)
    assert(await carried(ctx, admin, 'Away', 'diamond_sword') === 1, 'Away has the sword back')
    assert((await ctx.archive(admin, 'Away', 2)).returned === 0, 'the Returned page is empty')
  }
}
