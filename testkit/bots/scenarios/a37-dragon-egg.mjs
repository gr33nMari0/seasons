// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @isolated the End's dragon fight, which the reset can't put back
// A37. After launch the dragon can be respawned, and the first kill of the season gives the egg to
// the slayer, with title, fireworks, broadcast and Discord post. Also: the final blow decides,
// even an arrow's, over more damage dealt; a dragon that dies with no player involved leaves its
// egg on the portal and is announced nowhere (spec edge case), and that egg is left alone later;
// the slayer is a season first; a second kill in the season gives no egg and no fanfare. The bots
// fight through /scenario dragon (lib/dragon.mjs).
import { SECOND, assert } from '../lib/context.mjs'
import { carried } from '../lib/vault.mjs'
import { endSeason2, waitForTitle } from '../lib/zones.mjs'
import { EGG_WAIT, discordTo, dragonInfo, fireworksOverPortal, hurt, killWithNobody, respawn, toTheEnd, waitForDragon, waitForEgg } from '../lib/dragon.mjs'

const SLAIN = /slew the ender dragon/

export default {
  name: 'a37-dragon-egg',
  async run (ctx) {
    const discord = await ctx.webhook()
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    const sam = await ctx.join('Sam')
    await discordTo(ctx, admin, discord)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    // Season 2: the world's first dragon dies with no player involved. Its egg stays on the portal.
    await toTheEnd(ctx, admin, ['Pat'])
    await waitForDragon(ctx, admin)
    const unannounced = ctx.quiet(sam, SLAIN)
    await killWithNobody(ctx, admin)
    const oldEgg = await waitForEgg(ctx, admin)
    await ctx.sync(admin, [sam], 40)
    unannounced.verify()
    assert(await carried(ctx, admin, 'Pat', 'dragon_egg') === 0, 'nobody got the Season 2 egg')

    // Season 3 launches and clears the flag, so the next dragon drops an egg again.
    await endSeason2(ctx, admin)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30 * SECOND)
    let info = await dragonInfo(ctx, admin)
    assert(!info.killed, `launch cleared the previously-killed flag: ${JSON.stringify(info)}`)

    // Respawned with four crystals. Sam deals far more, but Pat's arrow is the final blow.
    await toTheEnd(ctx, admin, ['Pat', 'Sam'])
    await respawn(ctx, admin)
    // A body hit takes a quarter and one: Sam deals 153 of the 200, Pat 2 and then the last 45.
    for (let i = 0; i < 3; i++) await hurt(ctx, admin, 'Sam', 200, 'melee')
    await hurt(ctx, admin, 'Pat', 4, 'arrow')
    const titles = [pat, sam].map((bot) => waitForTitle(bot, /The dragon is slain!/, 15 * SECOND))
    const broadcast = ctx.waitForMessages(sam, /^Pat slew the ender dragon!$/, 15 * SECOND, 'the broadcast')
    const egg = ctx.waitForMessages(pat, /^The dragon egg is yours\.$/, EGG_WAIT, 'the egg')
    await hurt(ctx, admin, 'Pat', 1000, 'arrow')
    await ctx.eventually(async () => assert(await fireworksOverPortal(ctx, admin), 'fireworks over the exit portal'), 5 * SECOND)
    await Promise.all([...titles, broadcast])
    await discord.waitFor(/^Pat slew the ender dragon in Season 3!$/, 15 * SECOND)

    // The egg is taken off the portal as it appears and handed to Pat; the Season 2 egg stays.
    await egg
    assert(await carried(ctx, admin, 'Pat', 'dragon_egg') === 1, 'Pat carries the egg')
    assert(await carried(ctx, admin, 'Sam', 'dragon_egg') === 0, 'Sam has none')
    info = await dragonInfo(ctx, admin)
    assert(info.egg === oldEgg, `only the Season 2 egg is on the portal: ${info.egg}, was ${oldEgg}`)
    // The respawn blows the tops off the pillars, which drops blocks; no egg is among them.
    assert(!await ctx.holds(admin, 'entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:dragon_egg"}}]'), 'no egg was dropped')
    await ctx.command(admin, '/season firsts', [/^Season 3 firsts:$/, /^Pat slew the ender dragon \(/])

    // A second kill in Season 3: no egg, as in vanilla, and no fanfare.
    await respawn(ctx, admin)
    const again = [pat, sam].map((bot) => ctx.quiet(bot, SLAIN))
    await hurt(ctx, admin, 'Sam', 1000, 'melee')
    // Past the death animation, when an egg would have appeared.
    await ctx.sync(admin, [pat, sam], 300)
    again.forEach((q) => q.verify())
    assert(await carried(ctx, admin, 'Sam', 'dragon_egg') === 0, 'Sam got no egg')
    assert((await dragonInfo(ctx, admin)).egg === oldEgg, 'no new egg on the portal')
    const posts = discord.posts.filter((p) => SLAIN.test(p.content ?? ''))
    assert(posts.length === 1, `one Discord post for the season: ${JSON.stringify(posts)}`)
    await ctx.command(admin, '/season firsts', [/^Season 3 firsts:$/, /^Pat slew the ender dragon \(/])
  }
}
