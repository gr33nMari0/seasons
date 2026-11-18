// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Unlock conditions: a zone locked until a date opens by itself when the date comes, with a
// broadcast; and Homecoming set to come with the dragon's death happens when the dragon dies.
// Combinations and the other kinds are unit tests (UnlockConditionsTest).
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { LOCKED, box, launchFrontier, waitForTitle, zoneList } from '../lib/zones.mjs'

const SEASON_FILE = 'world/dimensions/minecraft/overworld/seasons/seasons/3.yml'

export default {
  name: 'm3-unlock-conditions',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await box(ctx, admin, 'tower', { x: 300, y: FLOOR - 1, z: -300 }, { x: 310, y: FLOOR + 10, z: -290 })
    await box(ctx, admin, 'keep', { x: 340, y: FLOOR - 1, z: -300 }, { x: 350, y: FLOOR + 10, z: -290 })

    // The tower opens 15 seconds from now; Homecoming waits for the dragon.
    const at = new Date(Date.now() + 15 * SECOND).toISOString().replace(/\.\d+Z$/, 'Z')
    let file = ctx.readFile(SEASON_FILE)
    const tower = file.indexOf('tower:')
    file = file.slice(0, tower) + file.slice(tower).replace(/lockedUntil: homecoming/, `lockedUntil: { date: "${at}" }`)
    file = file.replace(/homecoming:[^\n]*\n( +[^\n]*\n)*/, '') + 'homecoming:\n  unlock: { dragonKilled: true }\n'
    ctx.writeFile(SEASON_FILE, file)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    const listed = (await zoneList(ctx, admin, ['tower'])).tower
    assert(/block until /.test(listed) && /now block\.$/.test(listed), `the tower is locked until its date: ${listed}`)

    await standAt(ctx, admin, 'Pat', { x: 297, y: FLOOR, z: -295 })
    const refused = ctx.waitForMessages(pat, LOCKED('tower'), 10 * SECOND)
    await move(ctx, admin, 'Pat', middle({ x: 303, y: FLOOR, z: -295 }), 'walk')
    await refused

    await ctx.waitForMessages(pat, /^The tower is open!$/, 30 * SECOND)
    await move(ctx, admin, 'Pat', middle({ x: 303, y: FLOOR, z: -295 }), 'walk')
    await waitNear(ctx, admin, 'Pat', middle({ x: 303, y: FLOOR, z: -295 }))
    assert(/now block\.$/.test((await zoneList(ctx, admin, ['keep'])).keep), 'the keep waits for Homecoming')

    // The dragon dies: Homecoming.
    const title = waitForTitle(pat, /Homecoming/, 20 * SECOND)
    await ctx.command(admin, '/execute in minecraft:the_end run forceload add -16 -16 16 16', /^(Marked|No chunks were marked)/)
    await ctx.command(admin, '/execute in minecraft:the_end run summon minecraft:ender_dragon 0 100 0 {Tags:["seasons_test"]}', /^Summoned /)
    await ctx.command(admin, '/kill @e[type=minecraft:ender_dragon,tag=seasons_test]', /^Killed /)
    await title
    await ctx.eventually(async () => assert(/now off\.$/.test((await zoneList(ctx, admin, ['keep'])).keep), 'the keep opened at Homecoming'), 10 * SECOND)
  }
}
