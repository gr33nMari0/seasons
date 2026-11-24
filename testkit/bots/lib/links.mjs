// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Hall links in scenarios (M8): making them with the new grammar, taking them, and reading
// links.yml.
import { linksFile } from './hub.mjs'
import { RATE_LIMIT } from './visits.mjs'

export const WELCOME = /^Welcome to the Hall of Fame\. \/hof leave takes you back\.$/
export const FOLLOWED = /^You followed the link\.$/

/** Sets a block by command. */
export async function setBlock (ctx, admin, at, block) {
  await ctx.command(admin, `/setblock ${at.x} ${at.y} ${at.z} ${block}`, /^(Changed the block|Could not set the block)/)
}

/** Runs /hof link <words> as the admin and resolves with the reply. */
export function link (ctx, admin, words, expect) {
  return ctx.command(admin, `/hof link ${words}`, expect)
}

/** Takes a link as a player with /hof link go, then moves Seasons' clock past the rate limit. */
export async function go (ctx, bot, id, expect) {
  const reply = await ctx.command(bot, `/hof link go ${id}`, expect)
  await ctx.advance(ctx.adminBot, RATE_LIMIT)
  // A second, so a run of commands isn't kicked as chat spam.
  await ctx.sync(ctx.adminBot, [bot], 20)
  return reply
}

/** One link as links.yml has it. */
export function linkInFile (ctx, id) {
  return linksFile(ctx).links?.[id]
}

/** The block a position is in, as Seasons counts blocks. */
export function blockOf (at) {
  return { x: Math.floor(at.x), y: Math.floor(at.y), z: Math.floor(at.z) }
}

/** Whether two positions are in the same block. */
export function sameBlock (a, b) {
  const p = blockOf(a)
  const q = blockOf(b)
  return p.x === q.x && p.y === q.y && p.z === q.z
}
