// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Discord: the season's end and the launch are posted to the public channel, once each and in
// order, even when Discord first answers 500 and then 429 (retried after the wait it asks for).
// Nothing for the public channel goes to the admin webhook, and admin alerts don't go public: a
// tripwire hit goes only to the admin webhook. No post can ping anyone.
import { SECOND, assert } from '../lib/context.mjs'

const TAGGED = 'minecraft:stone[minecraft:custom_data={PublicBukkitValues:{"seasons:archived":1b}}]'

export default {
  name: 'm4-discord',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Pat')
    const discord = await ctx.webhook({ fail: [500, 429] })
    const alerts = await ctx.webhook({ path: 'admin' })
    ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\nadminWebhook: "${alerts.url}"\n`)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/season reload', /^Reloaded /)

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await discord.waitFor(/^Season 2 has ended\. Welcome to the Intermission!$/, 20_000)
    assert(discord.attempts.length === 3, `two refusals, then the post: ${discord.attempts.length} attempts`)

    await ctx.command(admin, '/tp SeasonsAdmin 2000 -60 -1500', /^Teleported /)
    await ctx.eventually(async () => assert(Math.floor((await ctx.position(admin, 'SeasonsAdmin')).x) === 2000, 'admin moved'))
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now /)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await discord.waitFor(/^Season 3 has begun!$/)
    const contents = discord.posts.map((p) => p.content)
    assert(contents.filter((c) => /^Season 2 has ended/.test(c)).length === 1, `the end is posted once: ${contents}`)
    assert(contents.indexOf('Season 3 has begun!') > contents.findIndex((c) => /^Season 2 has ended/.test(c)), 'in order')
    assert(!contents.some((c) => /\d+, -?\d+, -?\d+/.test(c)), 'the launch post gives no coordinates')

    // A tripwire hit is an admin alert.
    await ctx.command(admin, `/give Pat ${TAGGED} 3`, /^Gave 3/)
    await ctx.advance(admin, 30 * SECOND)
    await alerts.waitFor(/^Tripwire: Deleted 3 × minecraft:stone in an inventory \(Pat\)$/, 35 * SECOND)
    assert(!discord.posts.some((p) => /Tripwire/.test(p.content)), 'not on the public channel')
    assert(!alerts.posts.some((p) => /has ended|has begun/.test(p.content)), 'season posts are not admin alerts')
    assert([...discord.posts, ...alerts.posts].every((p) => p.allowed_mentions?.parse?.length === 0), 'no post can ping anyone')
  }
}
