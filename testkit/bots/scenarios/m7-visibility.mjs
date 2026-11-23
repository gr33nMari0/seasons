// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Every widget and field has a visibility in web.yml: public, players, admins or hidden. A hidden
// field is left out, a widget opened to the public needs no login, and with public: false nothing
// but the instance metadata and the login answers without one.
import { assert } from '../lib/context.mjs'
import { WebClient, deviceCode, login, webConfig } from '../lib/web.mjs'

export default {
  name: 'm7-visibility',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await webConfig(ctx, admin, 'visibility:\n  online.region: hidden\n  online.afk: players\n  leaderboards: public\n  server-health: hidden\n')
    const visitor = new WebClient(ctx)

    const online = await ctx.eventually(async () => {
      const res = await visitor.get('/api/v1/players/online')
      assert(res.json.players.some((p) => p.name === 'Alex'), `Alex is online: ${res.text}`)
      return res
    })
    const seen = online.json.players.find((p) => p.name === 'Alex')
    assert(seen.region === undefined, `a hidden field is left out: ${JSON.stringify(seen)}`)
    assert(seen.afk === undefined, `a players-only field is left out for a visitor: ${JSON.stringify(seen)}`)
    assert(typeof seen.dimension === 'string', `the rest is there: ${JSON.stringify(seen)}`)

    const boards = await visitor.get('/api/v1/leaderboards?season=2')
    assert(boards.status === 200, `leaderboards opened to the public: ${boards.status}`)

    const phone = new WebClient(ctx)
    await login(ctx, alex, phone)
    const logged = (await phone.get('/api/v1/players/online')).json.players.find((p) => p.name === 'Alex')
    assert(typeof logged.afk === 'boolean' && logged.region === undefined, `a player sees afk but not the hidden region: ${JSON.stringify(logged)}`)
    await ctx.command(admin, `/scenario permit ${admin.username} seasons.web.admin`, /^Permitted /)
    const op = new WebClient(ctx)
    await login(ctx, admin, op)
    const health = await op.get('/api/v1/admin/health')
    assert(health.status === 404, `a hidden widget isn't there even for an admin: ${health.status}`)

    // public: false
    await webConfig(ctx, admin, 'public: false\n')
    const closed = new WebClient(ctx)
    for (const path of ['/api/v1/season', '/api/v1/players/online', '/api/v1/hall/2', '/api/v1/links', '/api/v1/waypoints', '/api/v1/events']) {
      const res = await closed.get(path)
      assert(res.status === 401, `${path} needs a login: ${res.status}`)
    }
    const known = await closed.get('/.well-known/seasons.json')
    assert(known.status === 200 && known.json.public === false, `the metadata still answers: ${known.text}`)
    assert((await deviceCode(closed)).status === 200, 'and so does the login')
    assert((await phone.get('/api/v1/season')).status === 200, 'a logged-in player sees it all')
  }
}
