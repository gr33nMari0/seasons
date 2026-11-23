// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /.well-known/seasons.json tells the public site's connect page what this server is: its name,
// the versions, the API version, the features on and the login methods. Anyone may read it,
// from any site, and it sets no cookie.
import { assert } from '../lib/context.mjs'
import { WebClient, assertPublic, webConfig } from '../lib/web.mjs'

export default {
  name: 'm7-well-known',
  async run (ctx) {
    const admin = await ctx.admin()
    await webConfig(ctx, admin, 'name: TheRanch\npublic-url: https://seasons.example.com\nvisibility:\n  waypoints: hidden\n')
    const res = await new WebClient(ctx).get('/.well-known/seasons.json', { headers: { Origin: 'https://seasons.example.org' } })
    assert(res.status === 200, `served: ${res.status}`)
    assert(/^application\/json/.test(res.headers.get('content-type')), 'as JSON')
    const known = res.json
    assert(known.service === 'seasons', `a Seasons instance: ${res.text}`)
    assert(known.name === 'TheRanch', `the server's name: ${res.text}`)
    assert(/^\d+\.\d+\.\d+/.test(known.version) && /^\d+\.\d+\.\d+/.test(known.seasonsVersion), `the versions: ${res.text}`)
    assert(known.api === 'v1' && /^1\.\d+/.test(known.apiVersion), `the API version: ${res.text}`)
    assert(known.dashboard === 'https://seasons.example.com', `the dashboard: ${res.text}`)
    assert(known.public === true, 'public')
    assert(Array.isArray(known.login) && known.login.includes('device-code'), `login methods: ${res.text}`)
    for (const feature of ['season', 'online', 'event-feed', 'hall', 'leaderboards', 'links']) {
      assert(known.features.includes(feature), `${feature} is on: ${res.text}`)
    }
    assert(!known.features.includes('waypoints'), `a hidden widget isn't listed: ${res.text}`)
    assert(res.headers.get('access-control-allow-origin') === '*', 'any site may read it')
    assert(res.headers.getSetCookie().length === 0, 'no cookie')
    assertPublic(res, 'the instance metadata')

    const posted = await new WebClient(ctx).post('/.well-known/seasons.json')
    assert(posted.status === 405, `read-only: ${posted.status}`)
    const missing = await new WebClient(ctx).get('/api/v1/nothing')
    assert(missing.status === 404 && missing.json.error === 'not-found', `unknown addresses: ${missing.status} ${missing.text}`)
  }
}
