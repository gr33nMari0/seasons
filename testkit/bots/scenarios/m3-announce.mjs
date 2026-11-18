// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Announce mode: walking into the zone is broadcast once per player per zone per day, and posted to
// Discord when zones.announceToDiscord is on; walking out and in again says nothing more.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { box, launchFrontier } from '../lib/zones.mjs'

const ENTERED = /^Pat entered the market \(opens at Homecoming\)$/

export default {
  name: 'm3-announce',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Pat')
    const discord = await ctx.webhook()
    ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
    ctx.writeFile('plugins/Seasons/config.yml', ctx.readFile('plugins/Seasons/config.yml').replace(/announceToDiscord: false/, 'announceToDiscord: true'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await launchFrontier(ctx, admin)
    await box(ctx, admin, 'market', { x: 300, y: FLOOR - 1, z: 300 }, { x: 320, y: FLOOR + 10, z: 320 })
    await ctx.command(admin, '/season zone mode market announce', /^Zone market is now announce while it is locked\.$/)

    await standAt(ctx, admin, 'Pat', { x: 297, y: FLOOR, z: 310 })
    const heard = ctx.waitForMessages(admin, ENTERED, 10 * SECOND)
    await move(ctx, admin, 'Pat', middle({ x: 305, y: FLOOR, z: 310 }), 'walk')
    await heard
    await discord.waitFor(/^Pat entered the market \(opens at Homecoming\)$/)
    await waitNear(ctx, admin, 'Pat', middle({ x: 305, y: FLOOR, z: 310 }))

    const once = ctx.expectNoMessage(admin, ENTERED, 5 * SECOND)
    await move(ctx, admin, 'Pat', middle({ x: 297, y: FLOOR, z: 310 }), 'walk')
    await move(ctx, admin, 'Pat', middle({ x: 306, y: FLOOR, z: 311 }), 'walk')
    await once
    assert(discord.posts.filter((p) => /entered the market/.test(p.content ?? '')).length === 1, 'one Discord post')
  }
}
