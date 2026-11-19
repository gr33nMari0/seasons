// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The launch button (questions 172): a gallery's launch block, set by /hof gallery launch. Outside
// the Intermission it says why and does nothing; a player without seasons.admin.season is refused
// and audited; an admin presses once to be asked, and a second press within 10 seconds launches
// the next season, as /season launch would. A second press after 10 seconds only asks again.
import { SECOND, assert } from '../lib/context.mjs'
import { FLOOR, buildGallery, use } from '../lib/hall.mjs'
import { audit } from '../lib/protection.mjs'
import { permit } from '../lib/zones.mjs'

const BUTTON = { x: 30, y: FLOOR, z: 21 }
const SPAWN = { x: 2000, y: FLOOR, z: -1500 }
const ASK = /^Press the launch button again within 10 seconds to launch Season 3\.$/

export default {
  name: 'm1-launch-button',
  async run (ctx) {
    const admin = await ctx.admin()
    const sam = await ctx.join('Sam')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await buildGallery(ctx, admin, { cells: 2 })
    await ctx.command(admin, `/setblock ${BUTTON.x} ${BUTTON.y} ${BUTTON.z} minecraft:stone_button[face=floor]`, /^Changed the block/)
    await ctx.command(admin, '/hof gallery launch season-2 0 -60 0', /^The launch button must sit inside gallery season-2\.$/)
    await ctx.command(admin, `/hof gallery launch season-2 ${BUTTON.x} ${BUTTON.y} ${BUTTON.z}`, new RegExp(`^Gallery season-2's launch button is now at ${BUTTON.x}, ${BUTTON.y}, ${BUTTON.z}\\.$`))

    let told = ctx.waitForMessages(admin, /^Season 2 can only be launched from the Intermission\.$/)
    await use(ctx, admin, 'SeasonsAdmin', BUTTON)
    await told

    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    // Between seasons the Hall sends anyone who leaves it back to its entry; the admin holds the
    // bypass to stand outside and set the spawn.
    await permit(ctx, admin, 'SeasonsAdmin', 'seasons.hof.bypass')
    await ctx.command(admin, `/tp SeasonsAdmin ${SPAWN.x} ${SPAWN.y} ${SPAWN.z}`, /^Teleported /)
    await ctx.eventually(async () => assert(Math.floor((await ctx.position(admin, 'SeasonsAdmin')).x) === SPAWN.x, 'admin moved'))
    await ctx.command(admin, '/season spawn set', new RegExp(`^Season 3's spawn is now ${SPAWN.x}, ${SPAWN.y}, ${SPAWN.z} `))

    // Not an admin: refused and audited.
    told = ctx.waitForMessages(sam, /^Only an admin can launch the next season\.$/)
    await use(ctx, admin, 'Sam', BUTTON)
    await told
    // The audit log is written on its own thread, so the entry can land just after the message.
    await ctx.eventually(async () => assert(audit(ctx).some((e) => e.action === 'hall-blocked' && e.actor === 'Sam' && e.details.startsWith('launch')), 'the refused press is audited'))

    // One press asks; a second after 10 seconds only asks again.
    told = ctx.waitForMessages(admin, ASK)
    await use(ctx, admin, 'SeasonsAdmin', BUTTON)
    await told
    await ctx.advance(admin, 11 * SECOND)
    told = ctx.waitForMessages(admin, ASK)
    await use(ctx, admin, 'SeasonsAdmin', BUTTON)
    await told

    // The second press within 10 seconds launches Season 3.
    told = ctx.waitForMessages(admin, /^Season 3 has begun!$/, 30_000)
    await use(ctx, admin, 'SeasonsAdmin', BUTTON)
    await told
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Sam')
      assert(Math.abs(at.x - SPAWN.x - 0.5) <= 1 && Math.abs(at.z - SPAWN.z - 0.5) <= 1, `Sam is at the new spawn, not ${JSON.stringify(at)}`)
    })
    await ctx.command(admin, '/hof gallery launch season-2 none', /^Gallery season-2 has no launch button now\.$/)
  }
}
