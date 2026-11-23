// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// /web privacy hides a player from leaderboards, for everyone, and shows them again; the choice is
// kept in their player record and survives a restart, as do dashboard sessions.
import { assert } from '../lib/context.mjs'
import { SESSION_COOKIE, WebClient, login, uuid, webConfig } from '../lib/web.mjs'

const SAVED = 'plugins/SeasonsTestkit/m7-privacy-session.txt'

async function names (client) {
  const res = await client.get('/api/v1/leaderboards?season=2')
  assert(res.status === 200, `leaderboards: ${res.status} ${res.text}`)
  return { res, names: res.json.boards.deaths.map((e) => e.name) }
}

export default {
  name: 'm7-privacy',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const alex = await ctx.join('Alex')
      const sam = await ctx.join('Sam')
      await webConfig(ctx, admin)
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await ctx.command(admin, '/kill Sam', /Killed Sam/)
      const alexPhone = new WebClient(ctx)
      const samPhone = new WebClient(ctx)
      await login(ctx, alex, alexPhone)
      await login(ctx, sam, samPhone)

      const before = await names(samPhone)
      assert(before.names.includes('Alex') && before.names.includes('Sam'), `both on the leaderboard: ${before.res.text}`)
      const samDeaths = before.res.json.boards.deaths.find((e) => e.name === 'Sam')
      assert(samDeaths.value >= 1 && samDeaths.rank === 1, `Sam's death counts: ${JSON.stringify(samDeaths)}`)

      await ctx.command(alex, '/web privacy', /^You're now hidden from the dashboard's leaderboards\. \/web privacy shows you again\.$/)
      const hidden = await names(samPhone)
      assert(!hidden.names.includes('Alex'), `Alex is hidden from Sam: ${hidden.res.text}`)
      const own = await names(alexPhone)
      assert(!own.names.includes('Alex') && own.res.json.you.hidden === true, `and from Alex, who is told: ${own.res.text}`)
      const profile = await alexPhone.get(`/api/v1/players/${uuid('Alex')}`)
      assert(profile.json.leaderboardHidden === true, `Alex's own profile says so: ${profile.text}`)
      ctx.writeFile(SAVED, samPhone.cookies.get(SESSION_COOKIE))
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const alex = await ctx.join('Alex')
      await ctx.join('Sam')
      // Sam's dashboard is still logged in after the restart.
      const samPhone = new WebClient(ctx)
      samPhone.cookies.set(SESSION_COOKIE, ctx.readFile(SAVED).trim())
      const still = await names(samPhone)
      assert(!still.names.includes('Alex'), `Alex is still hidden after a restart: ${still.res.text}`)
      await ctx.command(alex, '/web privacy', /^You're now shown on the dashboard's leaderboards\.$/)
      const shown = await names(samPhone)
      assert(shown.names.includes('Alex'), `Alex is shown again: ${shown.res.text}`)
      void admin
    }
  ]
}
