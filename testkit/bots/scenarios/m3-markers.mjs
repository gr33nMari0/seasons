// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /season marker add and remove: shared waypoints at the admin's position, kept in markers.yml in
// the world folder and removed by name ignoring case; each refusal; a name is plain text, never
// formatting; a hand edit is picked up by reload and a broken one refused; every edit is in the
// audit log with who made it. No scenario server has BlueMap, so this is also Seasons without it:
// /season status says so, and the commands work all the same.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { forceload } from '../lib/zones.mjs'

const MARKERS = 'world/dimensions/minecraft/overworld/seasons/markers.yml'
const TOWER = { x: 100, y: FLOOR, z: 200 }

export default {
  name: 'm3-markers',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season status', /^BlueMap isn't installed, so no map markers are published\.$/)

    // Waypoints need no season.
    await forceload(ctx, admin, TOWER, 8)
    await standAt(ctx, admin, 'SeasonsAdmin', TOWER)
    await ctx.command(admin, "/season marker add megaprojects Peter's Tower", [
      /^Added waypoint Peter's Tower to megaprojects at 100, -60, 200 in minecraft:overworld\.$/,
      /^BlueMap isn't installed, so it shows once BlueMap is\.$/
    ])
    await ctx.command(admin, '/season marker add hubs <red>Spawn hub</red>', /^Added waypoint <red>Spawn hub<\/red> to hubs at 100, -60, 200 /)

    // Refusals.
    await ctx.command(admin, "/season marker add megaprojects PETER'S TOWER", /^megaprojects already has a waypoint called Peter's Tower\.$/)
    await ctx.command(admin, '/season marker add Mega_Projects Hub', /^Waypoint sets are /)
    await ctx.command(admin, `/season marker add hubs ${'x'.repeat(65)}`, /^Waypoint names are 1 to 64 characters\.$/)
    await ctx.command(admin, '/season marker remove megaprojects Nothing', /^megaprojects has no waypoint called Nothing\.$/)
    await ctx.command(admin, '/season marker remove nowhere Hub', /^nowhere has no waypoint called Hub\.$/)

    // The file holds them, and a hand edit is picked up by reload.
    const file = ctx.readFile(MARKERS)
    assert(/schema-version: 1/.test(file) && /megaprojects:/.test(file) && /Peter's Tower/.test(file) && /hubs:/.test(file), `markers.yml holds the waypoints: ${file}`)
    ctx.writeFile(MARKERS, file.replace(/sets:\n( +)/, (line, indent) => `sets:\n${indent}farms: { Iron Farm: { dimension: minecraft:the_nether, pos: [10, 70, -20] } }\n${indent}`))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season marker remove farms iron farm', /^Removed waypoint Iron Farm from farms\.$/)
    await ctx.command(admin, "/season marker remove megaprojects peter's tower", /^Removed waypoint Peter's Tower from megaprojects\.$/)
    assert(!/Peter's Tower/.test(ctx.readFile(MARKERS)), 'the waypoint is gone from markers.yml')

    // A broken edit is refused, and the waypoints stay as they were.
    const good = ctx.readFile(MARKERS)
    ctx.writeFile(MARKERS, good.replace(/sets:\n( +)/, (line, indent) => `sets:\n${indent}broken: { Half: { pos: [1, 2] } }\n${indent}`))
    await ctx.command(admin, '/season reload', [/^Reload failed/, /markers\.yml.*sets\.broken\.Half/])
    await ctx.command(admin, '/season marker remove broken half', /^broken has no waypoint called half\.$/)
    ctx.writeFile(MARKERS, good)
    await ctx.command(admin, '/season reload', /^Reloaded /)

    // A command edit the load refuses is put back. A hand edit not reloaded yet holds "iron farm",
    // so adding "Iron Farm" by command makes a file with both; the file goes back to the hand edit,
    // and nothing is audited.
    const handEdited = good.replace(/sets:\n( +)/, (line, indent) => `sets:\n${indent}farms: { iron farm: { pos: [10, 64, -20] } }\n${indent}`)
    ctx.writeFile(MARKERS, handEdited)
    await ctx.command(admin, '/season marker add farms Iron Farm', /^The waypoint wasn't saved: markers\.yml, at sets\.farms\.Iron Farm: the set already has a waypoint by that name, ignoring case\. See the server log\.$/)
    assert(ctx.readFile(MARKERS) === handEdited, `markers.yml is as the hand edit left it: ${ctx.readFile(MARKERS)}`)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season marker remove farms IRON FARM', /^Removed waypoint iron farm from farms\.$/)

    // Every change is in the audit log, with who made it.
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${new Date().toISOString().slice(0, 7)}.jsonl`)
    for (const entry of [
      /"actor":"SeasonsAdmin","action":"config","target":"markers\.yml sets\.megaprojects\.Peter's Tower","result":"ok","details":"none -> 100, -60, 200 in minecraft:overworld"/,
      /"actor":"SeasonsAdmin","action":"config","target":"markers\.yml sets\.hubs\.<red>Spawn hub<\/red>","result":"ok","details":"none -> 100, -60, 200 in minecraft:overworld"/,
      /"actor":"SeasonsAdmin","action":"config","target":"markers\.yml sets\.megaprojects\.Peter's Tower","result":"ok","details":"100, -60, 200 in minecraft:overworld -> none"/
    ]) {
      assert(entry.test(audit), `the audit log has ${entry}: ${audit}`)
    }
    assert(!/"target":"markers\.yml sets\.farms\.Iron Farm","result":"ok","details":"none -> /.test(audit), `the refused add isn't audited: ${audit}`)
    await ctx.command(admin, '/season status', /^BlueMap isn't installed, so no map markers are published\.$/)
  }
}
