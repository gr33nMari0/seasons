// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @plugins bluemap
// The Seasons marker set on a real BlueMap (docs/adr/0004). Before launch: the next season's spawn
// candidates, the Hall, the museum, and shared waypoints in sets of their own, but no zone areas,
// since no zone is in force yet. From launch: the season's spawn and no candidates, and each locked
// zone as an area on its own dimension's maps, with the Hall cut out of the one around it. At
// Homecoming the locked areas go, and a zone that becomes no-build is shown as one. After
// /bluemap reload, which drops every marker added through BlueMap's API, Seasons publishes them
// again; /season status counts them. /scenario bluemap reads the marker sets through BlueMap's API.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR, buildGallery } from '../lib/hall.mjs'
import { bluemap, describe, markersIn } from '../lib/map.mjs'
import { standAt } from '../lib/visits.mjs'
import { box, cylinder, drawBase, forceload } from '../lib/zones.mjs'

const WORLD = 'world/dimensions/minecraft/overworld/seasons'
const OVERWORLD = 'minecraft:overworld'
const NETHER = 'minecraft:the_nether'
const NORTH = { x: 4000, y: FLOOR, z: -2000 }
const EAST = { x: 6000, y: FLOOR, z: 1000 }
const TOWER = { x: 100, y: FLOOR, z: 200 }
const HUB = { x: 150, y: FLOOR, z: 220 }

export default {
  name: 'm3-bluemap',
  async run (ctx) {
    const admin = await ctx.admin()
    // BlueMap starts after the server has, and loads the game's resources first.
    await ctx.eventually(() => bluemap(ctx, admin), 180 * SECOND)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

    // The Hall (a gallery with one cell, its entry at 21, -60, 21), a museum written by hand, two
    // waypoints and two spawn candidates.
    await buildGallery(ctx, admin, { cells: 1 })
    ctx.writeFile(`${WORLD}/galleries/museum.yml`, [
      'schema-version: 1',
      'id: museum',
      'kind: museum',
      'dimension: minecraft:overworld',
      'bounds: { min: [200, -61, 300], max: [210, -50, 310] }',
      'entry: { pos: [201, -60, 301], yaw: 0 }',
      ''
    ].join('\n'))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await forceload(ctx, admin, TOWER, 60)
    await standAt(ctx, admin, 'SeasonsAdmin', TOWER)
    await ctx.command(admin, '/season marker add megaprojects Tower', /^Added waypoint Tower to megaprojects /)
    await standAt(ctx, admin, 'SeasonsAdmin', HUB)
    await ctx.command(admin, '/season marker add hubs Spawn hub', /^Added waypoint Spawn hub to hubs /)
    await forceload(ctx, admin, NORTH, 8)
    await standAt(ctx, admin, 'SeasonsAdmin', NORTH)
    await ctx.command(admin, '/season spawn candidate add north-mesa', /^Added spawn candidate north-mesa /)
    await forceload(ctx, admin, EAST, 8)
    await standAt(ctx, admin, 'SeasonsAdmin', EAST)
    await ctx.command(admin, '/season spawn candidate add east-jungle', /^Added spawn candidate east-jungle /)

    // Season 3's zones, drawn before it launches: the base, the old world around the Hall, a farm
    // that becomes no-build at Homecoming, and a box below the nether roof.
    await drawBase(ctx, admin)
    await cylinder(ctx, admin, 'old-world', { x: 60, y: FLOOR, z: 25 }, 60)
    await box(ctx, admin, 'farm', { x: 700, y: -64, z: 700 }, { x: 720, y: 0, z: 720 })
    await ctx.command(admin, '/season zone after farm no-build', /^Zone farm becomes no-build once it unlocks\.$/)
    await ctx.command(admin, '/execute in minecraft:the_nether run tp SeasonsAdmin 0.5 128 0.5', /^Teleported /)
    await ctx.command(admin, '/season zone add old-nether box -40 0 -40 40 127 40', /^Added zone old-nether: .* in minecraft:the_nether, /)
    await ctx.command(admin, `/execute in minecraft:overworld run tp SeasonsAdmin ${EAST.x + 0.5} ${EAST.y} ${EAST.z + 0.5}`, /^Teleported /)

    const before = await ctx.eventually(async () => {
      const found = await bluemap(ctx, admin)
      const over = markersIn(found, OVERWORLD)
      for (const [key, label] of [
        ['seasons/candidate-north-mesa', 'Spawn candidate: north mesa'],
        ['seasons/candidate-east-jungle', 'Spawn candidate: east jungle'],
        ['seasons/hall', 'Hall of Fame'],
        ['seasons/museum-museum', 'Museum'],
        ['seasons-megaprojects/tower', 'Tower'],
        ['seasons-hubs/spawn hub', 'Spawn hub']
      ]) {
        assert(over.get(key)?.label === label, `the overworld maps show ${key} "${label}": ${describe(over)}`)
        assert(over.get(key).type === 'poi', `${key} is a point`)
      }
      assert(![...over.keys()].some((k) => /^seasons\/(zone|spawn)-/.test(k)), `no zone or spawn is shown before launch: ${describe(over)}`)
      assert(markersIn(found, NETHER).size === 0, 'nothing is shown in the Nether before launch')
      assert(found.sets.some((s) => s.id === 'seasons' && s.label === 'Seasons'), 'the marker set is called Seasons')
      assert(found.sets.some((s) => s.id === 'seasons-megaprojects' && s.label === 'megaprojects'), 'each waypoint set is a marker set of its own')
      return found
    }, 15 * SECOND)
    const hall = markersIn(before, OVERWORLD).get('seasons/hall')
    assert(hall.x === 21.5 && hall.y === -60 && hall.z === 21.5, `the Hall is shown at its entry: ${JSON.stringify(hall)}`)

    // Launch: the spawn appears, the candidates go, and the zones lock.
    await ctx.command(admin, '/season spawn set east-jungle', /^Season 3's spawn is now candidate east-jungle: /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    const launched = await ctx.eventually(async () => {
      const found = await bluemap(ctx, admin)
      const over = markersIn(found, OVERWORLD)
      const nether = markersIn(found, NETHER)
      assert(over.get('seasons/spawn-3')?.label === 'Season 3 spawn', `the spawn is shown: ${describe(over)}`)
      assert(![...over.keys()].some((k) => k.startsWith('seasons/candidate-')), `the candidates are gone: ${describe(over)}`)
      for (const [key, label] of [
        ['seasons/zone-base', 'base: locked until Homecoming'],
        ['seasons/zone-old-world', 'old world: locked until Homecoming'],
        ['seasons/zone-farm', 'farm: locked until Homecoming']
      ]) {
        assert(over.get(key)?.label === label, `the overworld maps show ${key} "${label}": ${describe(over)}`)
        assert(over.get(key).type === 'extrude', `${key} is an area`)
      }
      assert(nether.get('seasons/zone-old-nether')?.label === 'old nether: locked until Homecoming', `the Nether maps show old-nether: ${describe(nether)}`)
      assert(!over.has('seasons/zone-old-nether') && !nether.has('seasons/zone-base'), 'each zone is shown on its own dimension\'s maps only')
      return found
    }, 15 * SECOND)
    const over = markersIn(launched, OVERWORLD)
    const spawn = over.get('seasons/spawn-3')
    assert(spawn.x === 6000.5 && spawn.y === -60 && spawn.z === 1000.5, `the spawn is where it was set: ${JSON.stringify(spawn)}`)
    const oldWorld = over.get('seasons/zone-old-world')
    assert(oldWorld.holes === 1, `the Hall is cut out of the old world: ${JSON.stringify(oldWorld)}`)
    assert(oldWorld.minY === -64 && oldWorld.maxY === 320 && oldWorld.points >= 32, `a cylinder with no y limits spans the world's height: ${JSON.stringify(oldWorld)}`)
    const farm = over.get('seasons/zone-farm')
    assert(farm.minY === -64 && farm.maxY === 1 && farm.points === 4 && farm.holes === 0, `a box keeps its corners: ${JSON.stringify(farm)}`)
    const oldNether = markersIn(launched, NETHER).get('seasons/zone-old-nether')
    assert(oldNether.minY === 0 && oldNether.maxY === 128, `old-nether stops at the roof: ${JSON.stringify(oldNether)}`)

    // A BlueMap reload drops every marker added through its API; Seasons publishes them again.
    const keys = (found) => found.markers.map((m) => `${m.dimension} ${m.map} ${m.set}/${m.id} ${m.label}`).sort().join('\n')
    ctx.log('[SeasonsAdmin] -> /bluemap reload')
    admin.chat('/bluemap reload')
    await ctx.eventually(async () => {
      const found = await bluemap(ctx, admin)
      assert(found.api !== launched.api, 'BlueMap has reloaded')
      assert(keys(found) === keys(launched), `the same markers are back after the reload:\n${keys(found)}\nwas\n${keys(launched)}`)
    }, 120 * SECOND)

    // Homecoming: the locked areas go, and the farm is shown as no-build.
    await ctx.command(admin, '/season unlock homecoming', /^Homecoming! Every zone is now in its after-unlock mode\.$/)
    await ctx.eventually(async () => {
      const found = await bluemap(ctx, admin)
      const over = markersIn(found, OVERWORLD)
      assert(over.get('seasons/zone-farm')?.label === 'farm: no building', `the farm is shown as no-build: ${describe(over)}`)
      for (const key of ['seasons/zone-base', 'seasons/zone-old-world']) {
        assert(!over.has(key), `${key} is gone after Homecoming: ${describe(over)}`)
      }
      assert(markersIn(found, NETHER).size === 0, 'old-nether is gone after Homecoming')
    }, 15 * SECOND)

    // A waypoint set with nothing left in it goes from the map.
    await ctx.command(admin, '/season marker remove hubs spawn hub', /^Removed waypoint Spawn hub from hubs\.$/)
    await ctx.eventually(async () => {
      const found = await bluemap(ctx, admin)
      assert(!found.sets.some((s) => s.id === 'seasons-hubs'), 'the hubs set is gone')
      assert(markersIn(found, OVERWORLD).has('seasons-megaprojects/tower'), 'the other waypoints stay')
    }, 15 * SECOND)
    await ctx.command(admin, '/season status', /^BlueMap: \d+ markers on \d+ maps\.$/)
  }
}
