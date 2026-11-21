// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The deaths display (Stats, season cards and awards): one objective per season, created at launch
// and shown in the tab list by default; the next launch makes a new one at zero in its place and
// keeps the old one; deaths.display moves the season's objective to the sidebar or below names, or
// takes it off the screen with none, at once; anything else is refused. The bots read the display
// slots the server sends them.
import { SECOND, assert } from '../lib/context.mjs'
import { launchFrontier } from '../lib/zones.mjs'
import { nextSeason } from '../lib/dragon.mjs'

const SLOTS = { list: 0, sidebar: 1, belowName: 2 }

/**
 * Keeps what the server last showed in each display slot, from the packets themselves: Mineflayer
 * doesn't forget a slot when it is cleared.
 */
function watchSlots (bot) {
  bot.seasonsSlots = {}
  bot._client.on('scoreboard_display_objective', (packet) => {
    bot.seasonsSlots[packet.position] = packet.name || null
  })
  // The game takes an objective shown nowhere else off the client altogether, slots and all.
  bot._client.on('scoreboard_objective', (packet) => {
    if (packet.action !== 1) return
    for (const slot of Object.keys(bot.seasonsSlots)) {
      if (bot.seasonsSlots[slot] === packet.name) bot.seasonsSlots[slot] = null
    }
  })
}

/** The objective a bot sees in a display slot (list, sidebar or belowName), or null. */
function shown (bot, slot) {
  return bot.seasonsSlots[SLOTS[slot]] ?? null
}

async function sees (ctx, bot, slot, name) {
  await ctx.eventually(async () => assert(shown(bot, slot) === name, `${bot.username} sees ${name} in ${slot}, not ${shown(bot, slot)}`))
}

export default {
  name: 'm4-deaths-display',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    watchSlots(pat)
    await launchFrontier(ctx, admin)
    await sees(ctx, pat, 'list', 'seasons_deaths_s3')
    // Past the arrival immunity, which a kill can't get through.
    await ctx.advance(admin, 6 * SECOND)
    await ctx.command(admin, '/kill Pat', /^Killed Pat/)
    await ctx.command(admin, '/scoreboard players get Pat seasons_deaths_s3', /^Pat has 1 /)

    // Moved at once by the setting.
    await ctx.command(admin, '/season config set deaths.display sidebar', /^Set deaths\.display to sidebar in config\.yml \(it was list\)\.$/)
    await sees(ctx, pat, 'sidebar', 'seasons_deaths_s3')
    await sees(ctx, pat, 'list', null)
    await ctx.command(admin, '/season config set deaths.display tab', /^deaths\.display wasn't changed: config\.yml, at deaths\.display: must be list, sidebar, below_name or none, not tab$/)

    // The next launch: a new objective at zero takes the slot; Season 3's is kept, off the screen.
    await nextSeason(ctx, admin, 3)
    await sees(ctx, pat, 'sidebar', 'seasons_deaths_s4')
    await ctx.command(admin, '/scoreboard objectives list', /\[Deaths, Season 3\].*\[Deaths, Season 4\]|\[Deaths, Season 4\].*\[Deaths, Season 3\]/)
    await ctx.command(admin, '/scoreboard players get Pat seasons_deaths_s3', /^Pat has 1 /)
    await ctx.command(admin, '/scoreboard players get Pat seasons_deaths_s4', /^Can't get value of seasons_deaths_s4 for Pat; none is set$/)

    await ctx.command(admin, '/season config set deaths.display below_name', /^Set deaths\.display to below_name /)
    await sees(ctx, pat, 'belowName', 'seasons_deaths_s4')
    await sees(ctx, pat, 'sidebar', null)
    await ctx.command(admin, '/season config set deaths.display none', /^Set deaths\.display to none /)
    await sees(ctx, pat, 'belowName', null)
    await sees(ctx, pat, 'list', null)
    await ctx.command(admin, '/season config set deaths.display list', /^Set deaths\.display to list /)
    await sees(ctx, pat, 'list', 'seasons_deaths_s4')
  }
}
