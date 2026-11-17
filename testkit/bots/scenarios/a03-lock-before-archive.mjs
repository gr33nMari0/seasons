// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A3. At the end of the countdown every gallery locks before the first player is archived, and an
// owner standing in their cell switches to adventure mode first.
import { assert } from '../lib/context.mjs'
import { buildGallery, cellOf, gameMode, place, visit } from '../lib/hall.mjs'

export default {
  name: 'a03-lock-before-archive',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    // No time after archiving, so the owner stays locked out once archived.
    ctx.writeFile('plugins/Seasons/config.yml', 'schema-version: 1\ndecorating:\n  afterArchive: 0s\n')
    await ctx.command(admin, '/season reload', /^Reloaded /)
    const cells = await buildGallery(ctx, admin, { cells: 3 })
    await ctx.command(admin, '/season decorate open', /^The decorating window for Season 2 is open: 2 players have cells\.$/)

    // During the window the owner decorates their own cell in survival.
    const cell = await cellOf(ctx, admin, 'season-2', cells, 'Owner')
    await visit(ctx, admin, 'Owner', cell)
    await ctx.eventually(async () => assert(await gameMode(ctx, admin, 'Owner') === 0, 'the owner is in survival in their own cell'))
    assert(await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 3 }), 'the owner can build in their cell during the window')

    // Watch the order things happen in, from the owner's side.
    const events = []
    owner.on('game', () => events.push(`mode:${owner.game.gameMode}`))
    owner.on('messagestr', (text) => {
      if (/^The Season 2 galleries are locked\.$/.test(text)) events.push('locked')
      if (/^Your Season 2 is archived in the Hall of Fame\.$/.test(text)) events.push('archived')
    })
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
    await ctx.eventually(async () => assert(events.includes('archived'), `the owner was archived: ${events}`))

    const adventure = events.indexOf('mode:adventure')
    const locked = events.indexOf('locked')
    const archived = events.indexOf('archived')
    assert(locked >= 0 && locked < archived, `the galleries locked before the owner was archived: ${events}`)
    assert(adventure >= 0 && adventure < archived, `the owner switched to adventure before being archived: ${events}`)
    assert(await gameMode(ctx, admin, 'Owner') === 2, 'the owner is in adventure mode')
    assert(!await place(ctx, admin, 'Owner', { x: cell.x0 + 1, y: -60, z: cell.z0 + 4 }), 'the locked cell refuses building')
  }
}
