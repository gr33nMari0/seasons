// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A6. /season launch teleports everyone online to the new spawn in survival with 5 s of immunity,
// a title, a sound and fireworks, sets the world spawn and creates the new deaths objective. The
// dragon flag is reset too (unit test: the bots can't reach the End). An old-world zone drawn in
// the Intermission doesn't stop anyone until launch locks it. Walking is the test plugin's client
// move (docs/adr/0001).
import { SECOND, assert, sleep } from '../lib/context.mjs'
import { middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { LOCKED, cylinder } from '../lib/zones.mjs'

const OLD_WORLD = { x: 2100, y: -60, z: -1500 }
const EDGE = { x: 2125, y: -60, z: -1500 }
const IN = middle({ x: 2110, y: -60, z: -1500 })

export default {
  name: 'a06-launch',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/season launch', /^Season 2 can only be launched from the Intermission\.$/)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/season launch', /^Set Season 3's spawn first/)

    await ctx.command(admin, '/tp SeasonsAdmin 2000 -60 -1500', /^Teleported /)
    await ctx.eventually(async () => assert(Math.floor((await ctx.position(admin, 'SeasonsAdmin')).x) === 2000, 'admin moved'))
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now 2000, -60, -1500 in minecraft:overworld\.$/)
    // Season 3's old-world zone, drawn now, isn't locked yet.
    await cylinder(ctx, admin, 'old-world', OLD_WORLD, 20)
    await standAt(ctx, admin, 'Pat', EDGE)
    await move(ctx, admin, 'Pat', IN, 'walk')
    await sleep(SECOND)
    await waitNear(ctx, admin, 'Pat', IN)
    await ctx.command(admin, '/tp SeasonsAdmin 0 -60 0', /^Teleported /)
    await ctx.command(admin, '/gamemode adventure Pat', /^Set Pat's game mode/)
    await ctx.command(admin, '/season launch --dry-run', [/^Dry run of \/season launch for Season 3\./, /^2 online players would go to 2000, -60, -1500/])
    assert(await ctx.entityData(admin, 'Pat', 'playerGameType') === '2', 'the dry run changed nothing')

    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    // A firework for each arriving player; immune or not, it hurts nobody (Pat's health below).
    assert(await ctx.holds(admin, 'entity @e[type=minecraft:firework_rocket]'), 'launch fireworks are in the air')
    for (const name of ['Pat', 'SeasonsAdmin']) {
      await ctx.eventually(async () => {
        const at = await ctx.position(admin, name)
        assert(Math.abs(at.x - 2000.5) <= 1 && Math.abs(at.z + 1499.5) <= 1, `${name} is at the new spawn, not ${JSON.stringify(at)}`)
      })
      assert(await ctx.entityData(admin, name, 'playerGameType') === '0', `${name} is in survival`)
    }
    await ctx.command(admin, '/damage Pat 4', /^Applied 4|invulnerable/)
    assert(await ctx.entityData(admin, 'Pat', 'Health') === '20.0f', 'Pat takes no damage while immune')
    await sleep(6 * SECOND)
    await ctx.command(admin, '/damage Pat 4', /^Applied 4/)
    assert(await ctx.entityData(admin, 'Pat', 'Health') === '16.0f', 'Pat takes damage once immunity ends')

    assert(await ctx.holds(admin, 'loaded 2000 -60 -1500'), 'the spawn chunk is loaded')
    await ctx.command(admin, '/scoreboard objectives list', /\[Deaths, Season 3\]/)
    await ctx.command(admin, '/season info', /^Phase: Active, Frontier stage$/)
    // The world spawn: a player with no respawn point respawns there after dying elsewhere.
    await ctx.command(admin, '/tp Pat 50 -60 50', /^Teleported /)
    await ctx.command(admin, '/kill Pat', /^Killed Pat/)
    await ctx.command(admin, '/scoreboard players get Pat seasons_deaths_s3', /^Pat has 1 /)
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Pat')
      assert(Math.abs(at.x - 2000) <= 12 && Math.abs(at.z + 1500) <= 12, `Pat respawned at the world spawn, not ${JSON.stringify(at)}`)
    }, 20 * SECOND)

    // Launch locked the old world.
    await standAt(ctx, admin, 'Pat', EDGE)
    const refused = ctx.waitForMessages(pat, LOCKED('old world'), 10 * SECOND)
    await move(ctx, admin, 'Pat', IN, 'walk')
    await refused
    await waitNear(ctx, admin, 'Pat', middle(EDGE))
  }
}
