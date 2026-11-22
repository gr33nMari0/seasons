// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A60. With hof.entry.requireBed: false, /hof works with no bed near while the other checks still
// refuse; with it on, an op (seasons.hof.anywhere) uses /hof with no bed, by a monster and just
// after damage, while a player without the node is refused, and the rate limit holds for both
// (questions 261 and 262).
import { FLOOR } from '../lib/hall.mjs'
import { HOME, RATE_LIMIT, hallToVisit, hof, standAt, waitInHall } from '../lib/visits.mjs'

const WELCOME = /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/
const NO_BED = /^You need to be within 3 blocks of a bed\.$/

export default {
  name: 'a60-hof-anywhere',
  async run (ctx) {
    const admin = await ctx.admin()
    const me = ctx.adminName
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin, { sealed: null })
    await standAt(ctx, admin, 'Visitor', HOME)

    // The bed rule as a setting: off, /hof works with no bed, but monsters still stop it.
    await hof(ctx, visitor, '/hof', NO_BED)
    await ctx.command(admin, '/season config set hof.entry.requireBed false', /^Set hof\.entry\.requireBed to false in config\.yml/)
    await hof(ctx, visitor, '/hof', WELCOME)
    await waitInHall(ctx, admin, 'Visitor')
    await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
    await ctx.command(admin, `/summon minecraft:zombie ${HOME.x - 3} ${FLOOR} ${HOME.z} {NoAI:1b,Tags:["a60"]}`, /^Summoned /)
    await hof(ctx, visitor, '/hof', /^Monsters are nearby\.$/)

    // Back on: the player is refused again, while the op skips the bed, the monster and the damage.
    await ctx.command(admin, '/season config set hof.entry.requireBed true', /^Set hof\.entry\.requireBed to true in config\.yml/)
    await hof(ctx, visitor, '/hof', NO_BED)
    await standAt(ctx, admin, me, HOME)
    await ctx.command(admin, `/damage ${me} 1 minecraft:generic`, /^Applied 1/)
    await ctx.command(admin, '/hof', WELCOME)
    // The rate limit holds even for the op: /hof again at once, from inside, is refused.
    await ctx.command(admin, '/hof', /^Wait a few seconds before trying \/hof again\.$/)
    await ctx.advance(admin, RATE_LIMIT)
    // The monster goes first, or return safety would turn the spot down on the way back.
    await ctx.command(admin, '/kill @e[tag=a60]', /^Killed /)
    await hof(ctx, admin, '/hof leave', /^Back where you left\.$/)

    // The bound on bedDistance.
    await ctx.command(admin, '/season config set hof.entry.bedDistance 17', /bedDistance.*between 0 and 16/)
    await ctx.command(admin, '/season config get hof.entry.bedDistance', /^hof\.entry\.bedDistance = 3$/)
  }
}
