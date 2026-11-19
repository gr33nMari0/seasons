// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The optional frontier border: a per-player world border drawn around a locked `outside` circle,
// visual only. Off by default; /season config set zones.frontierBorder true turns it on, and a
// player inside the circle is sent a border, the square around the circle, while the world's own
// border never changes. A visit to the Hall at 0, 0, beyond the frontier, takes it away and
// /hof leave brings it back; so do the Nether and the way back, and dying and respawning inside
// the circle. A holder of seasons.zone.bypass never has one, and Homecoming takes it away. The
// bots read the border packets their client gets.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { bed, hallToVisit, hof, standAt } from '../lib/visits.mjs'
import { SPAWN, WORLD_BORDER, forceload, outsideCircle, permit, trackBorder } from '../lib/zones.mjs'

const RADIUS = 60

export default {
  name: 'm3-frontier-border',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    trackBorder(pat)
    await hallToVisit(ctx, admin)
    // The spawn is set before the end: with a gallery, everyone waits in their cell until launch.
    await forceload(ctx, admin, SPAWN, 8)
    await standAt(ctx, admin, 'SeasonsAdmin', SPAWN)
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now 3000, -60, 0 /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await outsideCircle(ctx, admin, 'frontier', SPAWN, RADIUS)
    await standAt(ctx, admin, 'Pat', SPAWN)

    const frontier = async (what) => ctx.eventually(async () =>
      assert(pat.border.x === SPAWN.x && pat.border.z === SPAWN.z && pat.border.size === 2 * RADIUS, `${what}: Pat sees the frontier's border, ${JSON.stringify(pat.border)}`))
    const world = async (bot, what) => ctx.eventually(async () =>
      assert(bot.border.size === WORLD_BORDER, `${what}: ${bot.username} sees the world's border, ${JSON.stringify(bot.border)}`))

    // Off by default.
    await ctx.sync(admin, [pat], 20)
    await world(pat, 'off by default')

    // Turned on: Pat inside the circle sees it; the world's own border doesn't change.
    await ctx.command(admin, '/season config set zones.frontierBorder true', /^Set zones\.frontierBorder to true in config\.yml \(it was false\)\.$/)
    await frontier('turned on')
    await ctx.command(admin, '/worldborder get', new RegExp(`${WORLD_BORDER}`))

    // The Hall lies beyond the frontier: a visit takes the border away, and leaving brings it back.
    await bed(ctx, admin, { x: SPAWN.x + 2, y: FLOOR, z: SPAWN.z })
    await hof(ctx, pat, '/hof', /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/)
    await world(pat, 'in the Hall')
    await hof(ctx, pat, '/hof leave', /^Back where you left\.$/)
    await frontier('back from the Hall')

    // Dying and respawning at the world spawn, inside the circle, brings it back too: the game
    // sends the world's border with the respawn. Pat dies away from the spawn once the arrival
    // immunity from /hof leave has run out, and before any change of dimension, after which the
    // game holds a player invulnerable until their client has caught up.
    await standAt(ctx, admin, 'Pat', { x: SPAWN.x + 20, y: FLOOR, z: SPAWN.z })
    await frontier('before dying')
    await ctx.advance(admin, 6_000)
    const died = new Promise((resolve) => pat.once('death', resolve))
    const respawned = new Promise((resolve) => pat._client.once('respawn', resolve))
    await ctx.command(admin, '/kill Pat', /^Killed Pat/)
    await died
    await respawned
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Pat')
      assert(Math.abs(at.x - SPAWN.x) <= 12 && Math.abs(at.z - SPAWN.z) <= 12, `Pat respawned at the spawn, not ${JSON.stringify(at)}`)
    }, 20_000)
    await ctx.sync(admin, [pat], 20)
    await frontier('after dying and respawning')

    // Another dimension takes it away; the frontier brings it back.
    await ctx.command(admin, '/execute in minecraft:the_nether run tp Pat 0.5 128 0.5', /^Teleported /)
    await world(pat, 'in the Nether')
    await ctx.command(admin, `/execute in minecraft:overworld run tp Pat ${SPAWN.x + 0.5} ${SPAWN.y} ${SPAWN.z + 0.5}`, /^Teleported /)
    await frontier('back in the overworld')

    // A bypass holder never has one.
    const quinn = await ctx.join('Quinn')
    trackBorder(quinn)
    await permit(ctx, admin, 'Quinn', 'seasons.zone.bypass')
    await standAt(ctx, admin, 'Quinn', { x: SPAWN.x + 5, y: FLOOR, z: SPAWN.z })
    await ctx.sync(admin, [quinn], 20)
    await world(quinn, 'with the bypass')

    // Homecoming unlocks the frontier, and the border goes.
    await ctx.command(admin, '/season unlock homecoming', /^Homecoming! Every zone is now in its after-unlock mode\.$/)
    await world(pat, 'after Homecoming')
    await ctx.command(admin, '/worldborder get', new RegExp(`${WORLD_BORDER}`))
  }
}
