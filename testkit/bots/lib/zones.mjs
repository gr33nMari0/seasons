// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Zones in scenarios: a season launched into its Frontier stage, where zones lock, a base zone to
// keep out of, and the test plugin's stand-ins for what bots can't do through the protocol
// translation (docs/adr/0001): flying, riding, chorus fruit, portals, buckets and lighting.
import { SECOND, assert } from './context.mjs'
import { FLOOR } from './hall.mjs'
import { middle, move, near, pearl, standAt } from './visits.mjs'

/** Season 3's spawn, well away from every zone the scenarios draw. */
export const SPAWN = { x: 3000, y: FLOOR, z: 0 }

/** The base zone: a circle of radius RADIUS around BASE. */
export const BASE = { x: 500, y: FLOOR, z: 500 }
export const RADIUS = 30

/** Just outside the base's circle, on its east side. */
export const OUTSIDE = { x: BASE.x + RADIUS + 3, y: FLOOR, z: BASE.z }

/** Well inside the base. */
export const INSIDE = { x: BASE.x + 10, y: FLOOR, z: BASE.z }

/** Whether a position is inside the base's circle, as Seasons counts blocks. */
export function inBase (at) {
  const dx = Math.floor(at.x) + 0.5 - BASE.x
  const dz = Math.floor(at.z) + 0.5 - BASE.z
  return dx * dx + dz * dz <= RADIUS * RADIUS
}

/**
 * Records Season 2, ends it at once and launches Season 3 at SPAWN, so the season is in its
 * Frontier stage and zones drawn now are locked. Everyone online ends at SPAWN.
 */
export async function launchFrontier (ctx, admin) {
  await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
  await endSeason2(ctx, admin)
  await ctx.command(admin, '/season launch', /^Season 3 has begun!$/, 30_000)
}

/** Ends Season 2 at once and sets Season 3's spawn, leaving the server in the Intermission. */
export async function endSeason2 (ctx, admin) {
  await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
  await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)
  await standAt(ctx, admin, admin.username, SPAWN)
  await ctx.command(admin, '/season spawn set', /^Season 3's spawn is now /)
}

/**
 * Draws a cylinder zone standing at its centre, then keeps its chunks loaded. A zone that blocks
 * at once has the admin inside it, so this waits until Seasons has sent them out: the zone sweep's
 * teleport would otherwise land after the scenario's next move and undo it.
 */
export async function cylinder (ctx, admin, id, centre, radius, options = '') {
  await forceload(ctx, admin, centre, radius + 16)
  await standAt(ctx, admin, admin.username, centre)
  await ctx.command(admin, `/season zone add ${id} cylinder ${radius}${options ? ' ' + options : ''}`, new RegExp(`^Added zone ${id}: `))
  const { [id]: line } = await zoneList(ctx, admin, [id])
  if (line.endsWith('now block.')) {
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, admin.username)
      assert(Math.hypot(Math.floor(at.x) - centre.x, Math.floor(at.z) - centre.z) > radius, `${admin.username} is out of the ${id}, at ${JSON.stringify(at)}`)
    })
  }
}

/** Draws the base zone. */
export function drawBase (ctx, admin) {
  return cylinder(ctx, admin, 'base', BASE, RADIUS)
}

/** Draws a box zone from two corners, in the admin's dimension, and keeps its chunks loaded. */
export async function box (ctx, admin, id, from, to) {
  await forceload(ctx, admin, { x: (from.x + to.x) / 2, z: (from.z + to.z) / 2 }, Math.max(Math.abs(to.x - from.x), Math.abs(to.z - from.z)) + 16)
  await ctx.command(admin, `/season zone add ${id} box ${from.x} ${from.y} ${from.z} ${to.x} ${to.y} ${to.z}`, new RegExp(`^Added zone ${id}: `))
}

/** Keeps the chunks within `reach` of a point loaded. */
export async function forceload (ctx, admin, at, reach) {
  const x0 = Math.floor(at.x - reach)
  const z0 = Math.floor(at.z - reach)
  const x1 = Math.ceil(at.x + reach)
  const z1 = Math.ceil(at.z + reach)
  await ctx.command(admin, `/forceload add ${x0} ${z0} ${x1} ${z1}`, /^(Marked|No chunks were marked)/)
}

/** The refusal a player sees on the action bar when a route into a locked zone is refused. */
export const LOCKED = (name) => new RegExp(`^The ${name} is locked until `)

/** The refusal for anything done to a locked zone from outside its edge. */
export const EDGE = (name) => new RegExp(`^That's in the ${name}, which is locked until `)

/**
 * Every route into a zone a player has: walking, flying, riding, a real pearl, chorus fruit and a
 * portal, as [name, go(name, to)].
 */
export function entryRoutes (ctx, admin) {
  return [
    ['walking', (name, to) => move(ctx, admin, name, middle(to), 'walk')],
    ['flying', (name, to) => move(ctx, admin, name, { ...middle(to), y: to.y + 4 }, 'fly')],
    ['riding', (name, to) => ride(ctx, admin, name, middle(to))],
    ['a pearl', (name, to) => pearl(ctx, admin, name, to)],
    ['chorus fruit', (name, to) => chorus(ctx, admin, name, middle(to))],
    ['a portal', (name, to) => portal(ctx, admin, name, 'minecraft:overworld', middle(to))]
  ]
}

/**
 * Tries every route from `from` into a locked zone and checks each is refused: the player hears
 * why, stays outside where they stood, and is neither riding nor gliding.
 */
export async function expectEveryRouteRefused (ctx, admin, bot, from, to, zone, outside) {
  for (const [route, go] of entryRoutes(ctx, admin)) {
    await standAt(ctx, admin, bot.username, from)
    const told = ctx.waitForMessages(bot, LOCKED(zone), 10 * SECOND, `the ${route} refusal`)
    await go(bot.username, to)
    await told
    // Long enough for a refused move to have put them back: a round of the zone sweep and more.
    await ctx.sync(admin, [bot], 20)
    const at = await ctx.position(admin, bot.username)
    assert(outside(at), `${route}: ${bot.username} stays out, at ${JSON.stringify(at)}`)
    assert(near(at, middle(from), 2), `${route}: ${bot.username} is back where they stood, at ${JSON.stringify(at)}`)
    assert(await ctx.entityData(admin, bot.username, 'RootVehicle') === null, `${route}: not riding anything`)
    assert(await ctx.entityData(admin, bot.username, 'FallFlying') !== '1b', `${route}: not gliding`)
  }
}

/**
 * Seats a player in a new boat and moves the boat with them in it, as the server sees a boat
 * move: VehicleMoveEvent, then the boat and its passenger are there.
 */
export async function ride (ctx, admin, name, to) {
  await ctx.command(admin, `/scenario ride ${name} ${to.x} ${to.y} ${to.z}`, /^(Rode|Riding) /)
}

/** A chorus fruit teleport to a spot, with the teleport cause eating one gives. */
export async function chorus (ctx, admin, name, to) {
  const line = await ctx.command(admin, `/scenario chorus ${name} ${to.x} ${to.y} ${to.z}`, /^Chorus teleport of .* (went|was refused)/)
  return line.endsWith('went')
}

/** A trip through a nether portal to a spot, as PlayerPortalEvent then the teleport. */
export async function portal (ctx, admin, name, dimension, to) {
  const line = await ctx.command(admin, `/scenario portal ${name} ${dimension} ${to.x} ${to.y} ${to.z}`, /^Portal trip of .* (went|was refused)/)
  return line.endsWith('went')
}

/**
 * The game making a new nether portal at a spot for a player's trip, as PortalCreateEvent with
 * the pairing reason. Resolves with whether the portal was made.
 */
export async function portalPair (ctx, admin, name, dimension, at) {
  const line = await ctx.command(admin, `/scenario portal-pair ${name} ${dimension} ${at.x} ${at.y} ${at.z}`, /^(Made|Making) a portal/)
  return line.startsWith('Made')
}

/**
 * Uses a bucket at a block as a player: `water` or `lava` empties one, `fill` fills an empty one.
 * Resolves with whether it was allowed.
 */
export async function bucket (ctx, admin, name, at, what) {
  const line = await ctx.command(admin, `/scenario bucket ${name} ${at.x} ${at.y} ${at.z} ${what}`, /^Bucket .* (went|was refused)/)
  return line.endsWith('went')
}

/** Lights a block with flint and steel as a player: TNT is primed, anything else set on fire. */
export async function ignite (ctx, admin, name, at) {
  const line = await ctx.command(admin, `/scenario ignite ${name} ${at.x} ${at.y} ${at.z}`, /^(Lit|Lighting) /)
  return line.startsWith('Lit')
}

/** Places a block as a player, taking one from what they carry. Resolves with whether it went. */
export async function placeCarried (ctx, admin, name, at, block) {
  const line = await ctx.command(admin, `/scenario place ${name} ${at.x} ${at.y} ${at.z} ${block} consume`, /^(Placed|Placing) /)
  return line.startsWith('Placed')
}

/** Places a block as a player. Resolves with whether it went. */
export async function place (ctx, admin, name, at, block) {
  const line = await ctx.command(admin, `/scenario place ${name} ${at.x} ${at.y} ${at.z} ${block}`, /^(Placed|Placing) /)
  return line.startsWith('Placed')
}

/** Breaks a block as a player. Resolves with whether it went. */
export async function breakBlock (ctx, admin, name, at) {
  const line = await ctx.command(admin, `/scenario break ${name} ${at.x} ${at.y} ${at.z}`, /^(Broke|Breaking) /)
  return line.startsWith('Broke')
}

/** How many of one item a player carries, as /clear counts them. */
export async function carried (ctx, admin, name, item) {
  const line = await ctx.command(admin, `/clear ${name} ${item} 0`, /^(Found \d+ matching item|No items were found on player)/)
  const match = /^Found (\d+) matching item/.exec(line)
  return match ? Number(match[1]) : 0
}

/** Gives a player a permission, such as seasons.zone.bypass, until they leave. */
export async function permit (ctx, admin, name, node) {
  await ctx.command(admin, `/scenario permit ${name} ${node}`, /^Permitted /)
}

/** Resolves with the next title a bot is shown whose text matches `pattern`. */
export function waitForTitle (bot, pattern, timeoutMs = 10 * SECOND) {
  return new Promise((resolve, reject) => {
    const onTitle = (text) => {
      const plain = typeof text === 'string' ? text : JSON.stringify(text)
      if (pattern.test(plain)) {
        clearTimeout(timer)
        bot.off('title', onTitle)
        resolve(plain)
      }
    }
    const timer = setTimeout(() => {
      bot.off('title', onTitle)
      reject(new Error(`no title matching ${pattern} within ${timeoutMs} ms`))
    }, timeoutMs)
    bot.on('title', onTitle)
  })
}

/** The zone lines /season zone list prints, by zone id. */
export async function zoneList (ctx, admin, ids) {
  const lines = await ctx.command(admin, '/season zone list', ids.map((id) => new RegExp(`^${id}: `)))
  return Object.fromEntries(ids.map((id, i) => [id, lines[i]]))
}

/** Whether a position is inside a circle of `radius` around `centre`, as Seasons counts blocks. */
export function inCircle (centre, radius, at) {
  const dx = Math.floor(at.x) + 0.5 - centre.x
  const dz = Math.floor(at.z) + 0.5 - centre.z
  return dx * dx + dz * dz <= radius * radius
}

/**
 * Draws an `outside circle` zone standing at its centre, which must hold the spawn: everything
 * beyond the circle is the zone. Keeps the chunks out to a little past the edge loaded.
 */
export async function outsideCircle (ctx, admin, id, centre, radius) {
  await forceload(ctx, admin, centre, radius + 24)
  await standAt(ctx, admin, admin.username, centre)
  await ctx.command(admin, `/season zone add ${id} outside circle ${radius}`, new RegExp(`^Added zone ${id}: everywhere beyond a circle of radius ${radius} around ${centre.x}, ${centre.z} in `))
}

/**
 * Follows the world border a bot's client knows, from the border packets the server sends:
 * `bot.border` is { x, z, size }, and starts as whatever the server sent at login.
 */
export function trackBorder (bot) {
  bot.border = { x: 0, z: 0, size: undefined }
  bot._client.on('initialize_world_border', (p) => { bot.border = { x: p.x, z: p.z, size: Number(p.newDiameter) } })
  bot._client.on('world_border_center', (p) => { bot.border = { ...bot.border, x: p.x, z: p.z } })
  bot._client.on('world_border_size', (p) => { bot.border = { ...bot.border, size: Number(p.diameter) } })
  bot._client.on('world_border_lerp_size', (p) => { bot.border = { ...bot.border, size: Number(p.newDiameter) } })
}

/** The size of the world's own border, which Seasons never changes. */
export const WORLD_BORDER = 59999968
