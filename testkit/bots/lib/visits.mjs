// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Visiting the Hall of Fame in scenarios: a gallery to visit, a spot to leave from with a bed
// beside it, and the test plugin's stand-ins for what bots can't do through the protocol
// translation (docs/adr/0001): sneak-clicking a bed, walking, using items and sitting down.
import { SECOND, assert } from './context.mjs'
import { FLOOR, buildGallery, gameMode } from './hall.mjs'

/** How long /hof makes a player wait between attempts (hof.entry.rateLimit). */
export const RATE_LIMIT = 5 * SECOND

/** Where visitors leave from, well away from the gallery and the world spawn. */
export const HOME = { x: 100, y: FLOOR, z: 100 }

/** A position as the middle of its block, as players stand. */
export const middle = (at) => ({ x: at.x + 0.5, y: at.y, z: at.z + 0.5 })

/**
 * Records Season 2, builds its gallery (x 20 to 38, z 20 to 30, entry at 21, 21) and keeps the
 * chunks around HOME loaded. Resolves with the cells.
 */
export async function hallToVisit (ctx, admin, { cells = 2 } = {}) {
  await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
  const built = await buildGallery(ctx, admin, { cells })
  await ctx.command(admin, `/forceload add ${HOME.x - 40} ${HOME.z - 40} ${HOME.x + 40} ${HOME.z + 40}`, /^(Marked|No chunks were marked)/)
  return built
}

/** The gallery buildGallery makes, as bounds. */
export function inGallery (at, cells = 2) {
  const width = 6 * (cells + 1)
  return at.x >= 20 && at.x < 20 + width + 1 && at.z >= 20 && at.z < 31 && at.y >= FLOOR - 1 && at.y < FLOOR + 11
}

/** Moves a player to a block, standing in its middle, and waits until the server has them there. */
export async function standAt (ctx, admin, name, at) {
  const to = middle(at)
  await ctx.command(admin, `/tp ${name} ${to.x} ${to.y} ${to.z}`, /^Teleported /)
  await ctx.eventually(async () => assert(near(await ctx.position(admin, name), to, 0.1), `${name} stands at ${JSON.stringify(to)}`))
}

/** Places a bed block (one half is a bed as far as the game and Seasons are concerned). */
export async function bed (ctx, admin, at, kind = 'minecraft:red_bed') {
  await ctx.command(admin, `/setblock ${at.x} ${at.y} ${at.z} ${kind}`, /^Changed the block/)
}

/** Whether two positions are within `distance` of each other. */
export function near (a, b, distance) {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z) <= distance
}

/** Resolves once a player is inside the gallery, in adventure mode. */
export async function waitInHall (ctx, admin, name) {
  await ctx.eventually(async () => assert(inGallery(await ctx.position(admin, name)), `${name} is in the Hall`))
  await ctx.eventually(async () => assert(await gameMode(ctx, admin, name) === 2, `${name} is in adventure mode in the Hall`))
}

/** Resolves once a player stands within `distance` of `at`, and with that position. */
export async function waitNear (ctx, admin, name, at, distance = 0.6) {
  return ctx.eventually(async () => {
    const pos = await ctx.position(admin, name)
    assert(near(pos, at, distance), `${name} is within ${distance} of ${JSON.stringify(at)}, at ${JSON.stringify(pos)}`)
    return pos
  })
}

/** Runs /hof as a bot and waits for the reply; then moves Seasons' clock past the rate limit. */
export async function hof (ctx, bot, command, expect) {
  const reply = await ctx.command(bot, command, expect)
  await ctx.advance(ctx.adminBot, RATE_LIMIT)
  return reply
}

/** Sneak-right-clicks a block with an empty hand, as the bot would (see /scenario use). */
export async function sneakUse (ctx, admin, name, at) {
  await ctx.command(admin, `/scenario use ${name} ${at.x} ${at.y} ${at.z} sneak`, /^Used /)
}

/**
 * Moves a player as the client would (walking, being pushed or falling): fires PlayerMoveEvent
 * from where they stand to `to` and, unless it is cancelled, puts them there, with no teleport
 * event any other plugin sees. With `back`, a second move returns them in the same tick, so only
 * the move events, not a sweep, can notice they were ever away.
 */
export async function move (ctx, admin, name, to, how = 'walk', { back = false } = {}) {
  await ctx.command(admin, `/scenario move ${name} ${to.x} ${to.y} ${to.z} ${how}${back ? ' back' : ''}`, /^(Moved|Moving) /)
}

/** Throws a real ender pearl from a player's eyes towards a block, with them as its thrower. */
export async function pearl (ctx, admin, name, to) {
  await ctx.command(admin, `/scenario pearl ${name} ${to.x} ${to.y} ${to.z}`, /^Threw /)
}

/**
 * Right-clicks the air with what a player holds, as PlayerInteractEvent sees it. Resolves with
 * whether using the item was allowed.
 */
export async function useItem (ctx, admin, name) {
  const line = await ctx.command(admin, `/scenario use-item ${name}`, /^Using .* was (allowed|refused)$/)
  return line.endsWith('allowed')
}

/** Eats what a player holds, firing PlayerItemConsumeEvent. Resolves with whether it was allowed. */
export async function consume (ctx, admin, name) {
  const line = await ctx.command(admin, `/scenario consume ${name}`, /^Eating .* was (allowed|refused)$/)
  return line.endsWith('allowed')
}

/** Drops what a player holds, as pressing Q would. */
export async function drop (ctx, admin, name) {
  await ctx.command(admin, `/scenario drop ${name}`, /^Dropped /)
}

/**
 * Seats a player on the nearest entity of a type within 2 blocks of `at`, firing EntityMountEvent.
 * Resolves with whether they sat down.
 */
export async function sit (ctx, admin, name, type, at) {
  const line = await ctx.command(admin, `/scenario sit ${name} ${type} ${at.x} ${at.y} ${at.z}`, /^(Sat|Sitting) /)
  return line.startsWith('Sat')
}

/** Sets a player gliding with an elytra, then runs a command as them in the same tick. */
export async function whileGliding (ctx, admin, name, command) {
  await ctx.command(admin, `/scenario glide ${name} ${command}`, /^Gliding /)
}
