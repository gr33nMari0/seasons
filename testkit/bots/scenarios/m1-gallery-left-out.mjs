// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A broken gallery file at startup is left out, and nothing else is: the other galleries and the
// planned end load. /season status shows it, admins hear about it as they join, and /season end and
// /season launch are refused until it is fixed and /season reload succeeds.
import { DAY, SECOND } from '../lib/context.mjs'
import { buildGallery } from '../lib/hall.mjs'

const BROKEN = 'world/dimensions/minecraft/overworld/seasons/galleries/broken.yml'

export default {
  name: 'm1-gallery-left-out',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
      await buildGallery(ctx, admin, { cells: 2 })
      await ctx.command(admin, `/season schedule end ${ctx.isoIn(30 * DAY)}`, /^Season 2 now ends at /)
      // A hand edit gone wrong, found at the next start.
      ctx.writeFile(BROKEN, 'schema-version: 1\nid: broken\nkind: season\nseason: 1\nbounds: { min: [0, -61\n')
    },
    async (ctx) => {
      const admin = await ctx.admin()
      // Admins hear about it a few seconds after joining, by Seasons' clock.
      const notice = ctx.waitForMessages(admin, /^Gallery broken couldn't be loaded at startup/, 15_000, 'the notice as an admin joins')
      await ctx.advance(admin, 3 * SECOND)
      await notice
      await ctx.command(admin, '/season status', [/^Planned end: /, /^Gallery broken couldn't be loaded at startup/])
      await ctx.command(admin, '/hof gallery info season-2', /^Gallery season-2 /)
      await ctx.command(admin, '/season end --now', /^Season 2 can't end while a gallery file failed to load/)
      await ctx.command(admin, '/hof gallery create broken 1', /failed to load at startup, so a new gallery would write over it/)

      // A reload that still fails keeps everything as it is.
      await ctx.command(admin, '/season reload', /^Reload failed\./)
      await ctx.command(admin, '/season end --now', /^Season 2 can't end while a gallery file failed to load/)

      ctx.writeFile(BROKEN, 'schema-version: 1\nid: broken\nkind: season\nseason: 1\ndimension: minecraft:overworld\nbounds: { min: [200, -61, 200], max: [210, -50, 210] }\n')
      await ctx.command(admin, '/season reload', /^Reloaded /)
      const quiet = ctx.quiet(admin, /^Gallery broken/)
      await ctx.command(admin, '/season status', /^Journal: /)
      await ctx.advance(admin, 3 * SECOND)
      await ctx.sync(admin)
      quiet.verify()
      await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
      await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    }
  ]
}
