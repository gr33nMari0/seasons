// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /web sessions lists a player's logged-in devices, never their addresses; /web logout ends one or
// all of them, each audited; and a session ends by itself after sessions.lifetime (30 days).
import { DAY, MINUTE, assert } from '../lib/context.mjs'
import { FIREFOX, IPAD, WebClient, login, webConfig } from '../lib/web.mjs'

const works = async (client) => (await client.get('/api/v1/leaderboards')).status === 200

export default {
  name: 'm7-sessions',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await webConfig(ctx, admin)
    const ipad = new WebClient(ctx, { userAgent: IPAD })
    const desk = new WebClient(ctx, { userAgent: FIREFOX })
    await login(ctx, alex, ipad)
    await login(ctx, alex, desk)

    const [, tablet, pc] = await ctx.command(alex, '/web sessions', [/^Your logged-in devices \(2\):$/, /Safari on iPad/, /Firefox on Windows/])
    assert(![tablet, pc].some((l) => /\d+\.\d+\.\d+\.\d+/.test(l)), `no addresses: ${tablet} ${pc}`)
    const id = /^([a-z0-9]{6}) · /.exec(pc)?.[1]
    assert(id, `each device has an id: ${pc}`)

    await ctx.command(alex, `/web logout ${id}`, /^Logged out of Firefox on Windows\.$/)
    assert(!await works(desk), 'that device is logged out')
    assert(await works(ipad), 'the other still works')
    await ctx.command(alex, '/web logout nothing', /^You have no device nothing\. \/web sessions lists them\.$/)

    await ctx.command(alex, '/web logout all', /^Logged out of 1 device\.$/)
    assert(!await works(ipad), 'every device is logged out')
    await ctx.command(alex, '/web sessions', /^You have no logged-in devices\.$/)

    // A session lasts 30 days.
    const phone = new WebClient(ctx)
    await login(ctx, alex, phone)
    await ctx.advance(admin, 29 * DAY)
    assert(await works(phone), 'still logged in after 29 days')
    await ctx.advance(admin, DAY + MINUTE)
    assert(!await works(phone), 'logged out after 30 days')
    await ctx.command(alex, '/web sessions', /^You have no logged-in devices\.$/)

    await ctx.eventually(async () => {
      const month = new Date().toISOString().slice(0, 7)
      const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons-web/audit/${month}.jsonl`)
      assert(/"action":"web\.logout"/.test(audit), 'logging out one is audited')
      assert(/"action":"web\.logout-all"/.test(audit), 'logging out all is audited')
    })
  }
}
