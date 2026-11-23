// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Each endpoint at each access level: a visitor, a logged-in player, and a player with
// seasons.web.admin, which nobody has by default.
import { assert } from '../lib/context.mjs'
import { WebClient, login, uuid, webConfig } from '../lib/web.mjs'

export default {
  name: 'm7-access-levels',
  async run (ctx) {
    const admin = await ctx.admin()
    const alex = await ctx.join('Alex')
    await webConfig(ctx, admin)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/season marker add hubs Big Tree --at 50 -60 50', /^Added waypoint Big Tree to hubs /)

    const visitor = new WebClient(ctx)
    const player = new WebClient(ctx)
    const op = new WebClient(ctx)
    await login(ctx, alex, player)
    // Being an op isn't enough: seasons.web.admin is never granted by default.
    const opOnly = new WebClient(ctx)
    await login(ctx, admin, opOnly)
    const refused = await opOnly.get('/api/v1/admin/health')
    assert(refused.status === 403, `an op without seasons.web.admin sees no server health: ${refused.status}`)
    await ctx.command(admin, `/scenario permit ${admin.username} seasons.web.admin`, /^Permitted /)
    await login(ctx, admin, op)

    const expect = async (path, statuses) => {
      const [anon, logged, adminned] = await Promise.all([visitor, player, op].map((c) => c.get(path)))
      const got = [anon.status, logged.status, adminned.status]
      assert(JSON.stringify(got) === JSON.stringify(statuses), `${path}: visitor, player, admin ${JSON.stringify(got)}, wanted ${JSON.stringify(statuses)}`)
      return { anon, logged, adminned }
    }
    await expect('/api/v1/season', [200, 200, 200])
    await expect('/api/v1/players/online', [200, 200, 200])
    await expect('/api/v1/waypoints', [200, 200, 200])
    await expect('/api/v1/links', [200, 200, 200])
    await expect('/api/v1/leaderboards?season=2', [401, 200, 200])
    const health = await expect('/api/v1/admin/health', [401, 403, 200])
    const h = health.adminned.json
    assert(Array.isArray(h.tps) && h.tps.length === 3 && typeof h.tickMs === 'number' && h.memory.max > 0, `server health: ${health.adminned.text}`)

    const way = (await visitor.get('/api/v1/waypoints')).json.waypoints
    assert(way.some((w) => w.set === 'hubs' && w.name === 'Big Tree' && w.x === 50 && w.z === 50), `shared waypoints are published places: ${JSON.stringify(way)}`)

    // A profile: the public part for a visitor; every statistic for a player, their own or not.
    const alexId = uuid('Alex')
    const [anonProfile, ownProfile, otherProfile] = await Promise.all([visitor, player, op].map((c) => c.get(`/api/v1/players/${alexId}`)))
    assert(anonProfile.status === 200 && anonProfile.json.seasons.every((s) => s.statistics === undefined), `a visitor sees the summary: ${anonProfile.text}`)
    assert(ownProfile.json.seasons.some((s) => s.statistics), `Alex sees their own statistics: ${ownProfile.text}`)
    assert(ownProfile.json.leaderboardHidden === false, `Alex sees their own privacy setting: ${ownProfile.text}`)
    assert(otherProfile.json.seasons.some((s) => s.statistics), `another player sees Alex's statistics: ${otherProfile.text}`)
    assert(otherProfile.json.leaderboardHidden === undefined, `but not Alex's privacy setting: ${otherProfile.text}`)
    const nobody = await player.get('/api/v1/players/00000000-0000-0000-0000-000000000000')
    assert(nobody.status === 404, `an unknown player: ${nobody.status}`)
    const garbage = await player.get('/api/v1/players/not-a-uuid')
    assert(garbage.status === 404, `not a uuid: ${garbage.status}`)
    const noHall = await visitor.get('/api/v1/hall/9')
    assert(noHall.status === 404, `no gallery for Season 9: ${noHall.status}`)
  }
}
