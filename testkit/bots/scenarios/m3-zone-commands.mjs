// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The /season zone commands: add (a cylinder where the admin stands, with --max-y, and a box from
// two corners), list, mode, after, remove, and /season unlock and lock for one zone; each refusal;
// the season file they write, which a hand edit changes and /season reload picks up; a broken
// zone, and one in a dimension the server doesn't have, that reload refuses; and the audit log,
// which has every change with who made it.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { BASE, LOCKED, OUTSIDE, drawBase, forceload, launchFrontier, zoneList } from '../lib/zones.mjs'

const SEASON_FILE = 'world/dimensions/minecraft/overworld/seasons/seasons/3.yml'

export default {
  name: 'm3-zone-commands',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await launchFrontier(ctx, admin)
    await drawBase(ctx, admin)
    await forceload(ctx, admin, { x: 200, z: 200 }, 20)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: 200, y: FLOOR, z: 200 })
    await ctx.command(admin, '/season zone add tower cylinder 12 --max-y 80', /^Added zone tower: a cylinder of radius 12 around 200, 200 up to y 80 in minecraft:overworld, block until Homecoming and off after\.$/)
    await ctx.command(admin, '/season zone add mine box 900 -64 900 920 0 920', /^Added zone mine: a box from 900, -64, 900 to 920, 0, 920 in minecraft:overworld, block until Homecoming and off after\.$/)

    // Refusals.
    await ctx.command(admin, '/season zone add base cylinder 5', /^There's already a zone called base\.$/)
    await ctx.command(admin, '/season zone add Bad_Id cylinder 5', /^Zone ids are /)
    await ctx.command(admin, '/season zone add homecoming cylinder 5', /^Zone ids are /)
    await ctx.command(admin, '/season zone add mine2 box', /^Give both corners: /)
    await ctx.command(admin, '/season zone add ring outside 5', /^Zones are /)
    await ctx.command(admin, '/season zone add flat cylinder 0', /^Zones are /)
    await ctx.command(admin, '/season zone mode nowhere no-build', /^There's no zone called nowhere\.$/)
    await ctx.command(admin, '/season zone after base block', /^A zone becomes off or no-build once it unlocks\.$/)

    // Mode, after, remove, list.
    await ctx.command(admin, '/season zone mode mine announce', /^Zone mine is now announce while it is locked\.$/)
    await ctx.command(admin, '/season zone after mine no-build', /^Zone mine becomes no-build once it unlocks\.$/)
    await ctx.command(admin, '/season zone remove tower', /^Removed zone tower\.$/)
    const zones = await zoneList(ctx, admin, ['base', 'mine'])
    assert(/announce until Homecoming, then no-build; now announce\.$/.test(zones.mine), `mine is listed: ${zones.mine}`)
    assert(/block until Homecoming, then off; now block\.$/.test(zones.base), `base is listed: ${zones.base}`)
    const file = ctx.readFile(SEASON_FILE)
    assert(/base:/.test(file) && /mine:/.test(file) && !/tower:/.test(file), `the season file holds the zones: ${file}`)

    // One zone unlocked and locked again.
    await standAt(ctx, admin, 'Pat', OUTSIDE)
    await ctx.command(admin, '/season unlock base', /^Zone base is unlocked: it is off now\.$/)
    await move(ctx, admin, 'Pat', middle({ x: BASE.x + 10, y: FLOOR, z: BASE.z }), 'walk')
    await ctx.sync(admin, [pat], 20)
    await waitNear(ctx, admin, 'Pat', middle({ x: BASE.x + 10, y: FLOOR, z: BASE.z }))
    await ctx.command(admin, '/season unlock base', /^Zone base is already unlocked\.$/)
    const sent = ctx.waitForMessages(pat, /^You were in the base, which is locked, so you're back at the spawn\.$/, 10 * SECOND)
    await ctx.command(admin, '/season lock base', /^Zone base is locked again: it is block now\.$/)
    await sent
    await ctx.eventually(async () => assert(Math.abs((await ctx.position(admin, 'Pat')).x - 3000.5) < 2, 'Pat is at the spawn'))

    // A hand edit, reloaded.
    ctx.writeFile(SEASON_FILE, ctx.readFile(SEASON_FILE).replace(/mode: announce/, 'mode: off'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    assert(/now off\.$/.test((await zoneList(ctx, admin, ['mine'])).mine), 'the hand edit took effect')
    // A broken zone is refused, and the previous zones stay.
    const good = ctx.readFile(SEASON_FILE)
    ctx.writeFile(SEASON_FILE, [
      'schema-version: 1',
      'season: 3',
      'spawn: { dimension: minecraft:overworld, pos: [3000, -60, 0], yaw: 0 }',
      'zones:',
      '  broken: { dimension: minecraft:overworld, shape: { cylinder: { center: [0, 0], radius: -3 } } }',
      ''
    ].join('\n'))
    await ctx.command(admin, '/season reload', [/^Reload failed/, /seasons\/3\.yml/])
    assert(/now block\.$/.test((await zoneList(ctx, admin, ['base'])).base), 'the zones stay as they were')
    // So is a zone in a dimension the server doesn't have, which would lock nothing.
    const typo = 'typo: { dimension: minecraft:the_neter, shape: { cylinder: { center: [0, 0], radius: 100 } } }'
    ctx.writeFile(SEASON_FILE, good.replace(/zones:\n( +)/, (line, indent) => `zones:\n${indent}${typo}\n${indent}`))
    await ctx.command(admin, '/season reload', [/^Reload failed/, /seasons\/3\.yml.*zones\.typo\.dimension.*minecraft:the_neter/])
    ctx.writeFile(SEASON_FILE, good)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await forceload(ctx, admin, BASE, 10)
    await standAt(ctx, admin, 'Pat', OUTSIDE)
    const told = ctx.waitForMessages(pat, LOCKED('base'), 10 * SECOND)
    await move(ctx, admin, 'Pat', middle(BASE), 'walk')
    await told

    // Every change is in the audit log, with who made it.
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${new Date().toISOString().slice(0, 7)}.jsonl`)
    for (const entry of [
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml zones\.tower","result":"ok","details":"none -> a cylinder /,
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml zones\.mine\.mode","result":"ok","details":"block -> announce"/,
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml zones\.mine\.afterUnlock","result":"ok","details":"off -> no-build"/,
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml zones\.tower","result":"ok","details":"a cylinder [^"]* -> none"/,
      /"actor":"SeasonsAdmin","action":"zone-unlock","target":"base"/,
      /"actor":"SeasonsAdmin","action":"zone-lock","target":"base"/,
      /"actor":"Pat","action":"zone-blocked","target":"base","result":"denied","details":"walk in at /
    ]) {
      assert(entry.test(audit), `the audit log has ${entry}: ${audit}`)
    }
  }
}
