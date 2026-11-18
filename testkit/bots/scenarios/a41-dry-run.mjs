// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A41 (the spawn's half). /season end --dry-run reports a next spawn inside a zone, and launch is
// refused until it moves. The zone commands refuse to make that happen (a zone around the spawn, a
// spawn inside a zone), so the scenario makes it by hand, as an admin editing the season file
// might; /season reload takes the edit and warns. The cell shortfall is reported since M1 part 3;
// the 24,000-block warning comes with spawn candidates in M3 part 2.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { standAt } from '../lib/visits.mjs'
import { SPAWN, forceload } from '../lib/zones.mjs'

const SEASON_FILE = 'world/dimensions/minecraft/overworld/seasons/seasons/3.yml'

export default {
  name: 'a41-dry-run',
  async run (ctx) {
    const admin = await ctx.admin()
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await forceload(ctx, admin, SPAWN, 40)

    // The commands won't put the spawn in a zone.
    await standAt(ctx, admin, 'SeasonsAdmin', SPAWN)
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now /)
    await ctx.command(admin, '/season zone add pen cylinder 20', /^That zone would hold Season 3's spawn at 3000, -60, 0; the spawn must stay outside every zone\.$/)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: SPAWN.x + 100, y: FLOOR, z: SPAWN.z })
    await ctx.command(admin, '/season zone add fold cylinder 20', /^Added zone fold: /)
    await standAt(ctx, admin, 'SeasonsAdmin', { x: SPAWN.x + 105, y: FLOOR, z: SPAWN.z })
    await ctx.command(admin, '/season spawn set', /^Season 3's spawn can't be inside zone fold; the spawn must stay outside every zone\.$/)

    // A hand edit can.
    const file = ctx.readFile(SEASON_FILE)
    assert(/fold/.test(file), `the zone is in Season 3's file: ${file}`)
    const pen = `pen: { dimension: minecraft:overworld, shape: { cylinder: { center: [${SPAWN.x}, ${SPAWN.z}], radius: 20 } } }`
    ctx.writeFile(SEASON_FILE, file.replace(/zones:\n( +)/, (line, indent) => `zones:\n${indent}${pen}\n${indent}`))
    await ctx.command(admin, '/season reload', [/^Reloaded /, /Season 3's spawn at 3000, -60, 0 is inside zone pen/])
    await ctx.command(admin, '/season end --dry-run', [/^Dry run of \/season end/, /^Season 3's spawn at 3000, -60, 0 is inside zone pen\. \/season launch is refused until it moves\.$/])

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
