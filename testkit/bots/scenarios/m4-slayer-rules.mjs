// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @isolated the End's dragon fight, which the reset can't put back
// The slayer when no player dealt the final blow is whoever dealt the most damage, and with
// dragon.eggTo: portal the egg stays on the portal while the slayer still gets the fanfare. With
// dragon.newEggEachSeason: false, launch leaves the previously-killed flag set, so the next
// season's first kill is announced but drops no egg.
import { SECOND, assert } from '../lib/context.mjs'
import { carried } from '../lib/vault.mjs'
import { launchFrontier } from '../lib/zones.mjs'
import { dragonInfo, hurt, killWithNobody, nextSeason, respawn, toTheEnd, waitForDragon, waitForEgg } from '../lib/dragon.mjs'

export default {
  name: 'm4-slayer-rules',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    const sam = await ctx.join('Sam')
    await launchFrontier(ctx, admin)
    await ctx.command(admin, '/season config set dragon.eggTo portal', /^Set dragon\.eggTo to portal in config\.yml \(it was slayer\)\.$/)

    // Pat deals 51, Sam 102, then the dragon dies with no player's hand in it: Sam is the slayer.
    await toTheEnd(ctx, admin, ['Pat', 'Sam'])
    await waitForDragon(ctx, admin)
    await hurt(ctx, admin, 'Pat', 200, 'melee')
    await hurt(ctx, admin, 'Sam', 200, 'melee')
    await hurt(ctx, admin, 'Sam', 200, 'melee')
    const slain = ctx.waitForMessages(pat, /^Sam slew the ender dragon!$/, 15 * SECOND, 'the broadcast')
    await killWithNobody(ctx, admin)
    await slain

    // The egg stays on the portal.
    const egg = await waitForEgg(ctx, admin)
    await ctx.sync(admin, [sam], 60)
    assert((await dragonInfo(ctx, admin)).egg === egg, 'the egg is still on the portal')
    assert(await carried(ctx, admin, 'Sam', 'dragon_egg') === 0, 'Sam was given no egg')
    await ctx.command(admin, '/season firsts', [/^Season 3 firsts:$/, /^Sam slew the ender dragon \(/])

    // No new egg each season: launch leaves the flag alone.
    await ctx.command(admin, '/season config set dragon.newEggEachSeason false', /^Set dragon\.newEggEachSeason to false in config\.yml \(it was true\)\.$/)
    await nextSeason(ctx, admin, 3)
    assert((await dragonInfo(ctx, admin)).killed, 'the flag is still set after launch')
    await toTheEnd(ctx, admin, ['Pat', 'Sam'])
    await respawn(ctx, admin, 'Pat')
    const again = ctx.waitForMessages(sam, /^Pat slew the ender dragon!$/, 15 * SECOND, 'the Season 4 broadcast')
    await hurt(ctx, admin, 'Pat', 1000, 'melee')
    await again
    await ctx.sync(admin, [pat], 300)
    assert((await dragonInfo(ctx, admin)).egg === egg, 'no new egg')
    assert(await carried(ctx, admin, 'Pat', 'dragon_egg') === 0, 'Pat got no egg')
  }
}
