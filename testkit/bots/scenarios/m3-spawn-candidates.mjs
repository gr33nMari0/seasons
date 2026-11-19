// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The /season spawn candidate commands and /season spawn set <candidate>. Candidates go into the
// next season's file and keep the rest of it; the list gives each one's distance from 0, 0 and
// says which the spawn is; each refusal; a hand edit is picked up by reload; every edit is in the
// audit log with who made it; and once Season 3 launches, the commands work on Season 4's file.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { forceload } from '../lib/zones.mjs'

const SEASONS = 'world/dimensions/minecraft/overworld/seasons/seasons'
const NORTH = { x: 4000, y: FLOOR, z: -2000 }
const EAST = { x: 6000, y: FLOOR, z: 1000 }

export default {
  name: 'm3-spawn-candidates',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season spawn candidate add north-mesa', /^No season recorded yet\. /)
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/season spawn candidate list', /^Season 3 has no spawn candidates yet\. /)

    await forceload(ctx, admin, NORTH, 8)
    await standAt(ctx, admin, 'SeasonsAdmin', NORTH)
    await ctx.command(admin, '/season spawn candidate add north-mesa', /^Added spawn candidate north-mesa for Season 3 at 4000, -60, -2000 in minecraft:overworld\.$/)
    await forceload(ctx, admin, EAST, 8)
    await standAt(ctx, admin, 'SeasonsAdmin', EAST)
    await ctx.command(admin, '/season spawn candidate add east-jungle', /^Added spawn candidate east-jungle for Season 3 at 6000, -60, 1000 in minecraft:overworld\.$/)

    // Refusals.
    await ctx.command(admin, '/season spawn candidate add north-mesa', /^Season 3 already has a spawn candidate called north-mesa\.$/)
    await ctx.command(admin, '/season spawn candidate add North_Mesa', /^Candidate names are /)
    await ctx.command(admin, '/season spawn candidate remove nowhere', /^Season 3 has no spawn candidate called nowhere\.$/)
    await ctx.command(admin, '/season spawn set nowhere', /^Season 3 has no spawn candidate called nowhere\.$/)

    await ctx.command(admin, '/season spawn candidate list', [
      /^Season 3's spawn candidates:$/,
      /^- east-jungle: 6000, -60, 1000 in minecraft:overworld, 6083 blocks from 0, 0$/,
      /^- north-mesa: 4000, -60, -2000 in minecraft:overworld, 4472 blocks from 0, 0$/
    ])
    await ctx.command(admin, '/season spawn set east-jungle', /^Season 3's spawn is now candidate east-jungle: 6000, -60, 1000 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season spawn candidate list', /^- east-jungle: 6000, -60, 1000 in minecraft:overworld, 6083 blocks from 0, 0 \(the spawn\)$/)
    await ctx.command(admin, '/season spawn candidate remove north-mesa', /^Removed spawn candidate north-mesa from Season 3\.$/)

    // The file keeps the spawn and the candidate left, and a hand edit is picked up by reload.
    const file = ctx.readFile(`${SEASONS}/3.yml`)
    assert(/spawnCandidates:/.test(file) && /east-jungle:/.test(file) && !/north-mesa/.test(file), `Season 3's file has its candidates: ${file}`)
    assert(/spawn:\n +dimension: minecraft:overworld\n +pos:\n +- 6000\n +- -60\n +- 1000/.test(file), `Season 3's file has the spawn: ${file}`)
    ctx.writeFile(`${SEASONS}/3.yml`, file.replace(/spawnCandidates:\n( +)/, (line, indent) => `spawnCandidates:\n${indent}west-tundra: { pos: [-5000, 70, 300] }\n${indent}`))
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await ctx.command(admin, '/season spawn candidate list', [
      /^- east-jungle: /,
      /^- west-tundra: -5000, 70, 300 in minecraft:overworld, 5008 blocks from 0, 0$/
    ])

    // Every edit is in the audit log, with who made it.
    const audit = ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${new Date().toISOString().slice(0, 7)}.jsonl`)
    for (const entry of [
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml spawnCandidates\.north-mesa","result":"ok","details":"none -> 4000, -60, -2000 in minecraft:overworld"/,
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml spawnCandidates\.east-jungle","result":"ok","details":"none -> 6000, -60, 1000 in minecraft:overworld"/,
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml spawn","result":"ok","details":"none -> 6000, -60, 1000 in minecraft:overworld"/,
      /"actor":"SeasonsAdmin","action":"config","target":"seasons\/3\.yml spawnCandidates\.north-mesa","result":"ok","details":"4000, -60, -2000 in minecraft:overworld -> none"/
    ]) {
      assert(entry.test(audit), `the audit log has ${entry}: ${audit}`)
    }

    // Once Season 3 has launched, the candidates are Season 4's.
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    await ctx.command(admin, '/season spawn candidate list', /^Season 4 has no spawn candidates yet\. /)
    await ctx.command(admin, '/season spawn candidate add far-east', /^Added spawn candidate far-east for Season 4 at 6000, -60, 1000 in minecraft:overworld\.$/)
    assert(/far-east:/.test(ctx.readFile(`${SEASONS}/4.yml`)), 'Season 4\'s file has the candidate')
  }
}
