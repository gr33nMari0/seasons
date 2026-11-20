// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @isolated the End's dragon fight, which the reset can't put back
// A38. With a full inventory, the slayer's egg goes to the Returned page of their vault, which they
// can take from at once, before Homecoming. Also: a slayer who leaves before the egg appears finds
// it on their Returned page when they come back, and a crystal a player sets off is their final
// blow, over more damage dealt by someone else.
import { SECOND, assert } from '../lib/context.mjs'
import { NEXT, carried, openVault, slotOf, take, turnTo } from '../lib/vault.mjs'
import { launchFrontier } from '../lib/zones.mjs'
import { EGG_WAIT, crystal, dragonInfo, hurt, nextSeason, respawn, toTheEnd, waitForDragon } from '../lib/dragon.mjs'

const SEALED = /^Your ender vault is sealed until Homecoming/

/** Opens the vault and turns to the page that shows the egg. */
async function eggPage (ctx, bot) {
  const window = await openVault(ctx, bot, SEALED)
  if (slotOf(window, 'dragon_egg') < 0) await turnTo(ctx, bot, NEXT, 'dragon_egg')
}

export default {
  name: 'a38-egg-returned',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    let sam = await ctx.join('Sam')
    await launchFrontier(ctx, admin)

    // Season 3: Pat's inventory is full when the egg appears, so it waits on the Returned page.
    await toTheEnd(ctx, admin, ['Pat', 'Sam'])
    await waitForDragon(ctx, admin)
    await ctx.command(admin, '/give Pat minecraft:stone 2304', /^Gave 2304 /)
    const returned = ctx.waitForMessages(pat, /^Your inventory is full, so the dragon egg waits on your vault's Returned page: \/vault$/, EGG_WAIT, 'the egg returned')
    await hurt(ctx, admin, 'Pat', 1000, 'melee')
    await returned
    await ctx.sync(admin, [pat], 4)
    assert(await carried(ctx, admin, 'Pat', 'dragon_egg') === 0, 'Pat carries no egg')
    assert(await ctx.itemCount(admin, 'Pat') === 2304, 'Pat carries only the stone')
    assert(!await ctx.holds(admin, 'entity @e[type=minecraft:item]'), 'nothing was dropped')
    assert((await dragonInfo(ctx, admin)).egg === 'none', 'the egg is off the portal')

    // Before Homecoming, the Returned page gives it up once there is room.
    await ctx.command(admin, '/clear Pat minecraft:stone 64', /^Removed 64 /)
    await eggPage(ctx, pat)
    const took = ctx.waitForMessages(pat, /^Took 1 × minecraft:dragon_egg from your vault\.$/)
    await take(ctx, admin, pat, 'dragon_egg', 1)
    await took
    pat.closeWindow(pat.currentWindow)

    // Season 4: Pat deals most of the damage, but Sam's crystal is the final blow, and Sam has left
    // by the time the egg appears.
    await nextSeason(ctx, admin, 3)
    await toTheEnd(ctx, admin, ['Pat', 'Sam'])
    await respawn(ctx, admin)
    for (let i = 0; i < 3; i++) await hurt(ctx, admin, 'Pat', 200, 'melee')
    const slain = ctx.waitForMessages(pat, /^Sam slew the ender dragon!$/, 15 * SECOND, 'the broadcast')
    await ctx.eventually(async () => {
      if ((await dragonInfo(ctx, admin)).alive) await crystal(ctx, admin, 'Sam')
      assert(!(await dragonInfo(ctx, admin)).alive, 'the dragon is dead')
    }, 20 * SECOND)
    await slain
    await ctx.leave(sam)
    await ctx.eventually(async () => assert((await dragonInfo(ctx, admin)).killed, 'the fight is over'), EGG_WAIT)
    await ctx.eventually(async () => assert((await dragonInfo(ctx, admin)).egg === 'none', 'the egg is off the portal'), EGG_WAIT)
    await ctx.sync(admin, [], 40)
    // Pat's Season 3 egg went into Pat's archive at the end of Season 3.
    assert(await carried(ctx, admin, 'Pat', 'dragon_egg') === 0, 'Pat got no egg')
    assert((await ctx.archive(admin, 'Pat', 3)).present, "Pat's Season 3 archive holds the egg Pat carried")

    // Sam comes back to it.
    sam = await ctx.join('Sam')
    await eggPage(ctx, sam)
    await take(ctx, admin, sam, 'dragon_egg', 1)
    sam.closeWindow(sam.currentWindow)
  }
}
