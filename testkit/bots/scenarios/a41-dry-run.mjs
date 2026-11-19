// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A41. /season end --dry-run reports a cell shortfall, a spawn beyond 24,000 blocks of 0,0 and a
// spawn inside a zone. Two players have played Season 2 and its gallery has one cell. A spawn set
// 25,000 blocks out is taken with a warning, which reload and the dry run repeat. The commands
// won't put the spawn or a spawn candidate in a zone (a zone around either, either inside a zone),
// so the scenario makes that happen by hand, as an admin editing the season file might;
// /season reload takes the edit and warns. Launch is refused until the zone goes.
import { assert } from '../lib/context.mjs'
import { FLOOR, buildGallery } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { SPAWN, forceload } from '../lib/zones.mjs'

const SEASON_FILE = 'world/dimensions/minecraft/overworld/seasons/seasons/3.yml'
const FAR = { x: 25000, y: FLOOR, z: 0 }
const MESA = { x: SPAWN.x + 200, y: FLOOR, z: 0 }

export default {
  name: 'a41-dry-run',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.join('Ann')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 1 })
    await ctx.command(admin, '/season end --dry-run', /^Hall of Fame: only 1 free cells in Season 2's galleries for 2 players without one, so 1 would be archived without displays\.$/)

    // A spawn beyond the last stronghold ring is set, with a warning that reload and the dry run
    // repeat.
    await forceload(ctx, admin, FAR, 20)
    await standAt(ctx, admin, 'SeasonsAdmin', FAR)
    await ctx.command(admin, '/season spawn set', [/^Season 3's spawn is now 25000, -60, 0 in minecraft:overworld\.$/, /^That's 25000 blocks from 0, 0, beyond 24,000: strongholds may be out of reach\.$/])
    await ctx.command(admin, '/season reload', [/^Reloaded /, /Season 3's spawn at 25000, -60, 0 is 25000 blocks from 0, 0, beyond 24,000/])
    await ctx.command(admin, '/season end --dry-run', [
      /^Dry run of \/season end/,
      /^Hall of Fame: only 1 free cells /,
      /^Season 3's spawn at 25000, -60, 0 is 25000 blocks from 0, 0, beyond 24,000: strongholds may be out of reach\.$/
    ])

    // The commands won't put the spawn or a candidate in a zone.
    await forceload(ctx, admin, SPAWN, 40)
    await forceload(ctx, admin, MESA, 40)
    await standAt(ctx, admin, 'SeasonsAdmin', SPAWN)
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now 3000, -60, 0 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season zone add pen cylinder 20', /^That zone would hold Season 3's spawn at 3000, -60, 0; the spawn must stay outside every zone\.$/)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: SPAWN.x + 100, y: FLOOR, z: SPAWN.z })
    // For an outside zone, outside the zone is inside its circle: one drawn away from the spawn
    // would hold it.
    await ctx.command(admin, '/season zone add beyond outside circle 20', /^That zone would hold Season 3's spawn at 3000, -60, 0; the spawn must stay outside every zone\.$/)
    await ctx.command(admin, '/season zone add fold cylinder 20', /^Added zone fold: /)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: SPAWN.x + 105, y: FLOOR, z: SPAWN.z })
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn can't be inside zone fold; the spawn must stay outside every zone\.$/)
    await ctx.command(admin, '/season spawn candidate add stray', /^Season 3's spawn candidates can't be inside zone fold; every candidate must stay outside every zone\.$/)
    await standAt(ctx, admin, 'SeasonsAdmin', MESA)
    await ctx.command(admin, '/season spawn candidate add mesa', /^Added spawn candidate mesa for Season 3 at 3200, -60, 0 in minecraft:overworld\.$/)
    await ctx.command(admin, '/season zone add ring cylinder 20', /^That zone would hold Season 3's spawn candidate mesa at 3200, -60, 0; every candidate must stay outside every zone\.$/)

    // A hand edit can.
    const file = ctx.readFile(SEASON_FILE)
    assert(/fold/.test(file) && /mesa/.test(file), `the zone and the candidate are in Season 3's file: ${file}`)
    const pen = `pen: { dimension: minecraft:overworld, shape: { cylinder: { center: [${SPAWN.x}, ${SPAWN.z}], radius: 20 } } }`
    const stray = `stray: { pos: [${SPAWN.x + 105}, ${FLOOR}, ${SPAWN.z}] }`
    ctx.writeFile(SEASON_FILE, file
      .replace(/zones:\n( +)/, (line, indent) => `zones:\n${indent}${pen}\n${indent}`)
      .replace(/spawnCandidates:\n( +)/, (line, indent) => `spawnCandidates:\n${indent}${stray}\n${indent}`))
    await ctx.command(admin, '/season reload', [
      /^Reloaded /,
      /Season 3's spawn at 3000, -60, 0 is inside zone pen/,
      /Season 3's spawn candidate stray at 3105, -60, 0 is inside zone fold/
    ])
    await ctx.command(admin, '/season end --dry-run', [
      /^Dry run of \/season end/,
      /^Hall of Fame: only 1 free cells /,
      /^Season 3's spawn at 3000, -60, 0 is inside zone pen\. \/season launch is refused until it moves\.$/
    ])

    await endSeason2Without(ctx, admin)
    await ctx.command(admin, '/season launch --dry-run', /^\/season launch would be refused: Season 3's spawn is inside a zone\./)
    await ctx.command(admin, '/season launch', /^Season 3's spawn is inside a zone\./)
    await ctx.command(admin, '/season zone remove pen', /^Removed zone pen\.$/)
    await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
  }
}

/** Ends Season 2 without setting the spawn again, which would be refused here. */
async function endSeason2Without (ctx, admin) {
  await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
  await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
}
