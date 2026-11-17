// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// /season archive and /season reprocess, and archiving being harmless to repeat: a player is
// archived exactly once per season. Session 2 covers a player who missed a whole season and a
// pre-Seasons player with no record, both archived into the season they last played.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, giveGear } from '../lib/gear.mjs'

export default {
  name: 'm1-archive-commands',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      // Old-timer played before Seasons was installed, and isn't seen again until Season 3.
      const oldTimer = await ctx.join('OldTimer')
      await ctx.command(admin, '/give OldTimer minecraft:cobblestone 7', /^Gave 7/)
      // Players' files are written as they leave; Seasons is installed after OldTimer left.
      await ctx.leave(oldTimer)
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)

      await ctx.join('Again')
      await ctx.command(admin, '/season archive Again', /^Again has no finished season waiting to be archived\.$/)
      await ctx.command(admin, '/season archive Nobody', /^Nobody isn't online\b/)
      await giveGear(ctx, admin, 'Again')
      await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
      await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
      assert((await ctx.archive(admin, 'Again', 2)).items === GEAR_ITEMS, 'archived once')

      // Running it again changes nothing: the gear given now stays with the player.
      await ctx.command(admin, '/give Again minecraft:apple 3', /^Gave 3/)
      await ctx.command(admin, '/season archive Again', /^Again has no finished season waiting to be archived\.$/)
      assert(await ctx.itemCount(admin, 'Again') === 3, 'a second archive takes nothing')
      assert((await ctx.archive(admin, 'Again', 2)).items === GEAR_ITEMS, 'the archive is unchanged')
      await ctx.command(admin, '/season reprocess Again', /^Again has nothing pending\.$/)
      await ctx.command(admin, '/season reprocess Nobody', /^Nobody has nothing pending\.$/)

      await ctx.command(admin, '/tp SeasonsAdmin 300 -60 300', /^Teleported /)
      await ctx.eventually(async () => assert(Math.floor((await ctx.position(admin, 'SeasonsAdmin')).x) === 300, 'admin moved'))
      await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now 300, -60, 300/)
      await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.command(admin, '/season info', /^Phase: Active, Frontier stage$/)
      await ctx.join('OldTimer')
      await ctx.eventually(async () => {
        const archive = await ctx.archive(admin, 'OldTimer', 2)
        assert(archive.archivedThrough === 2 && archive.items === 7, `OldTimer is archived into Season 2: ${JSON.stringify(archive)}`)
      }, 20_000)
      assert(await ctx.itemCount(admin, 'OldTimer') === 0, 'OldTimer starts Season 3 empty-handed')
      await ctx.eventually(async () => {
        const at = await ctx.position(admin, 'OldTimer')
        assert(Math.abs(at.x - 300.5) <= 1 && Math.abs(at.z - 300.5) <= 1, `OldTimer is at the Season 3 spawn, not ${JSON.stringify(at)}`)
      })
    }
  ]
}
