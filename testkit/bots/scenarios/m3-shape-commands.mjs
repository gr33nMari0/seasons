// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The `outside`, `y-above` and `y-below` shapes by command and by hand, in the Intermission, when
// zone commands edit the next season's file. /season zone add draws each, lists it and writes it to
// the season file; it refuses a malformed shape, and a zone of any new shape that would hold the
// spawn or a candidate: for an outside zone, that is a spawn beyond its circle or box (A41). The
// spawn and candidates can't go beyond a frontier either. A hand edit with each shape is picked up
// by reload, a malformed one is refused with its key path, and one that leaves the spawn beyond a
// frontier loads with a warning, and launch is refused. Every edit is audited.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { SPAWN, endSeason2, forceload, zoneList } from '../lib/zones.mjs'

const SEASON_FILE = 'world/dimensions/minecraft/overworld/seasons/seasons/3.yml'
const FAR = { x: SPAWN.x + 100, y: FLOOR, z: SPAWN.z }
const NEAR = { x: SPAWN.x + 10, y: FLOOR, z: SPAWN.z }

export default {
  name: 'm3-shape-commands',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await forceload(ctx, admin, SPAWN, 110)
    await endSeason2(ctx, admin)

    // Each shape, drawn.
    await standAt(ctx, admin, 'SeasonsAdmin', SPAWN)
    await ctx.command(admin, '/season zone add frontier outside circle 50', /^Added zone frontier: everywhere beyond a circle of radius 50 around 3000, 0 in minecraft:overworld, block until Homecoming and off after\.$/)
    await ctx.command(admin, '/season zone add edge outside box 2900 -100 3100 100', /^Added zone edge: everywhere beyond a box from 2900, -100 to 3100, 100 in minecraft:overworld, block until Homecoming and off after\.$/)
    await ctx.command(admin, '/season zone add sky y-above 200', /^Added zone sky: everything from y 200 up in minecraft:overworld, block until Homecoming and off after\.$/)
    await ctx.command(admin, '/season zone add deep y-below -63', /^Added zone deep: everything up to y -63 in minecraft:overworld, block until Homecoming and off after\.$/)

    // Malformed shapes.
    for (const shape of ['outside 5', 'outside circle 0', 'outside circle', 'outside box 1 2 3', 'y-above high', 'y-below', 'outside square 5']) {
      await ctx.command(admin, `/season zone add bad ${shape}`, /^Zones are /)
    }
    await ctx.command(admin, '/season zone add bad outside box', /^Give both corners: \/season zone add bad outside box <x1> <z1> <x2> <z2>\./)

    // A zone of a new shape that would hold the spawn.
    const aroundSpawn = /^That zone would hold Season 3's spawn at 3000, -60, 0; the spawn must stay outside every zone\.$/
    await ctx.command(admin, '/season zone add pen outside box 3010 -10 3030 10', aroundSpawn)
    await ctx.command(admin, '/season zone add pen y-above -60', aroundSpawn)
    await ctx.command(admin, '/season zone add pen y-below -60', aroundSpawn)
    await standAt(ctx, admin, 'SeasonsAdmin', FAR)
    await ctx.command(admin, '/season zone add pen outside circle 20', aroundSpawn)

    // Neither the spawn nor a candidate goes beyond a frontier.
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn can't be inside zone (edge|frontier); the spawn must stay outside every zone\.$/)
    await ctx.command(admin, '/season spawn candidate add stray', /^Season 3's spawn candidates can't be inside zone (edge|frontier); every candidate must stay outside every zone\.$/)
    await standAt(ctx, admin, 'SeasonsAdmin', NEAR)
    await ctx.command(admin, '/season spawn candidate add mesa', /^Added spawn candidate mesa for Season 3 at 3010, -60, 0 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season zone add pen outside box 2990 -10 3005 10', /^That zone would hold Season 3's spawn candidate mesa at 3010, -60, 0; every candidate must stay outside every zone\.$/)

    // Listed, and written to the season file.
    const zones = await zoneList(ctx, admin, ['deep', 'edge', 'frontier', 'sky'])
    assert(/^frontier: everywhere beyond a circle of radius 50 around 3000, 0 in minecraft:overworld; block until Homecoming, then off; now off\.$/.test(zones.frontier), `the frontier is listed: ${zones.frontier}`)
    assert(/^sky: everything from y 200 up in /.test(zones.sky), `the sky is listed: ${zones.sky}`)
    const file = ctx.readFile(SEASON_FILE)
    // Command-edited files are written in block style, lists one item a line.
    for (const written of [/outside:\s+circle:\s+center:\s+- 3000\s+- 0\s+radius: 50\n/, /y-above: 200\n/, /y-below: -63\n/, /outside:\s+box:\s+min:\s+- 2900\s+- -100\s+max:\s+- 3100\s+- 100\n/]) {
      assert(written.test(file), `the season file has ${written}: ${file}`)
    }

    // Hand edits: one with each shape is picked up; a malformed one is refused with where it is.
    const good = ctx.readFile(SEASON_FILE)
    ctx.writeFile(SEASON_FILE, good.replace('y-above: 200', 'y-above: 250'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    assert(/^sky: everything from y 250 up/.test((await zoneList(ctx, admin, ['sky'])).sky), 'the hand edit took effect')
    for (const [broken, path] of [
      ['y-above: 250', 'zones\\.sky\\.shape\\.y-above', 'y-above: high'],
      ['radius: 50', 'zones\\.frontier\\.shape\\.outside\\.circle\\.radius', 'radius: 0']
    ].map(([from, at, to]) => [ctx.readFile(SEASON_FILE).replace(from, to), at])) {
      ctx.writeFile(SEASON_FILE, broken)
      await ctx.command(admin, '/season reload', [/^Reload failed/, new RegExp(`seasons/3\\.yml.*${path}`)])
    }
    ctx.writeFile(SEASON_FILE, good)
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // A hand edit that leaves the spawn beyond the frontier loads with a warning, which the dry run
    // repeats.
    ctx.writeFile(SEASON_FILE, good.replace(/(center:\s+- )3000/, '$13300'))
    await ctx.command(admin, '/season reload', [/^Reloaded /, /Season 3's spawn at 3000, -60, 0 is inside zone frontier/])
    await ctx.command(admin, '/season launch --dry-run', /^\/season launch would be refused: Season 3's spawn is inside a zone\./)
    ctx.writeFile(SEASON_FILE, good)
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // Every edit is audited.
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${new Date().toISOString().slice(0, 7)}.jsonl`)
    for (const entry of [
      /"action":"config","target":"seasons\/3\.yml zones\.frontier","result":"ok","details":"none -> everywhere beyond a circle of radius 50 /,
      /"action":"config","target":"seasons\/3\.yml zones\.edge","result":"ok","details":"none -> everywhere beyond a box /,
      /"action":"config","target":"seasons\/3\.yml zones\.sky","result":"ok","details":"none -> everything from y 200 up /,
      /"action":"config","target":"seasons\/3\.yml zones\.deep","result":"ok","details":"none -> everything up to y -63 /
    ]) {
      assert(entry.test(audit), `the audit log has ${entry}: ${audit}`)
    }
  }
}
