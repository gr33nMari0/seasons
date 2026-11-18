// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A13. The first player to complete an advancement in a season is broadcast as a season first; the
// second is not. Reaching the Nether and carrying a rare item are firsts too, each posted to
// Discord and listed by /season firsts.
import { SECOND, assert } from '../lib/context.mjs'

const STONE_AGE = /^Season first! Alex completed \[(.+)\]\.$/

export default {
  name: 'a13-firsts',
  async run (ctx) {
    const discord = await ctx.webhook()
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    const sam = await ctx.join('Sam')
    ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    // The first to an advancement: everyone hears it, and Discord too.
    const heard = ctx.waitForMessages(sam, STONE_AGE, 15 * SECOND, 'the first broadcast')
    await ctx.command(admin, '/advancement grant Alex only minecraft:story/mine_stone', /^Granted the advancement/)
    const line = await heard
    ctx.log(`announced: ${line}`)
    assert(STONE_AGE.exec(line)[1] === 'Stone Age', `the advancement is named by its title: ${line}`)
    await discord.waitFor(/^Season first! Alex completed \[Stone Age\]\.$/, 15 * SECOND)

    // The second is not.
    const quiet = ctx.expectNoMessage(alex, /^Season first! Sam completed/, 5 * SECOND)
    await ctx.command(admin, '/advancement grant Sam only minecraft:story/mine_stone', /^Granted the advancement/)
    await quiet

    // The first to the Nether.
    const nether = ctx.waitForMessages(alex, /^Season first! Sam reached the Nether\.$/, 15 * SECOND, 'the Nether first')
    await ctx.command(admin, '/execute in minecraft:the_nether run tp Sam 0 120 0', /^Teleported /)
    await nether

    // A rare item, noticed by the inventory sweep.
    const trident = ctx.waitForMessages(sam, /^Season first! Alex found Trident\.$/, 30 * SECOND, 'the trident first')
    await ctx.command(admin, '/give Alex minecraft:trident', /^Gave 1 /)
    await trident
    await discord.waitFor(/^Season first! Alex found Trident\.$/, 15 * SECOND)

    const listed = await ctx.command(admin, '/season firsts', [
      /^Season 2 firsts:$/,
      /^Alex completed \[Stone Age\] \(/,
      /^Sam reached the Nether \(/,
      /^Alex found Trident \(/
    ])
    ctx.log(listed.join('\n'))
    // Teleporting into the Nether also completes its advancements, each a first of its own.
    const posted = discord.posts.map((p) => p.content).filter((c) => c.startsWith('Season first!'))
    for (const first of ['Alex completed [Stone Age]', 'Sam reached the Nether', 'Alex found Trident']) {
      assert(posted.filter((c) => c === `Season first! ${first}.`).length === 1, `posted once: ${first}`)
    }
    assert(!posted.some((c) => c.startsWith('Season first! Sam completed [Stone Age]')), `the second to Stone Age is not a first: ${JSON.stringify(posted)}`)
  }
}
