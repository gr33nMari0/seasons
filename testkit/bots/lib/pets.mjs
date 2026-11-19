// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Pets in scenarios: summoning animals that stand still, taming them to a player through the test
// plugin (docs/adr/0003), registering them by looking at them, and checking where they are.
import { assert } from './context.mjs'
import { FLOOR } from './hall.mjs'
import { forceload } from './zones.mjs'

/** Where owners stand to register their pets, well away from the spawn and the zones. */
export const YARD = { x: 200, y: FLOOR, z: 200 }

/** Far from everyone, where a pet's chunk unloads once it isn't forceloaded. */
export const FAR = { x: 6000, y: FLOOR, z: 6000 }

/**
 * Summons an animal with a tag, standing still (no AI) so it stays where it was put, with a name
 * if given. Resolves once it is there.
 */
export async function summon (ctx, admin, type, tag, at, { name, nbt = '' } = {}) {
  const named = name ? `,CustomName:"${name}"` : ''
  await ctx.command(admin, `/summon minecraft:${type} ${at.x + 0.5} ${at.y} ${at.z + 0.5} {Tags:["${tag}"],NoAI:1b,PersistenceRequired:1b${named}${nbt ? ',' + nbt : ''}}`, /^Summoned new /)
}

/** Tames a tagged animal to a player, as the game does when they tame it. */
export async function tame (ctx, admin, tag, owner) {
  await ctx.command(admin, `/scenario tame ${tag} ${owner}`, /^Tamed /)
}

/** Puts a chest on a tagged donkey, mule or llama, or takes it off. */
export async function chest (ctx, admin, tag, on = true) {
  await ctx.command(admin, `/scenario chest ${tag} ${on ? 'on' : 'off'}`, /^(Put a chest on|Took the chest off) /)
}

/** Summons an animal and tames it to a player in one go. */
export async function pet (ctx, admin, type, tag, at, owner, options = {}) {
  await summon(ctx, admin, type, tag, at, options)
  await tame(ctx, admin, tag, owner)
}

/**
 * Stands a player at `from` looking at a tagged entity, then runs /pet add as them and resolves
 * with the reply matching `expect`. Bots send no movement, so the server keeps the rotation the
 * teleport gave them.
 */
export async function register (ctx, admin, bot, tag, expect, from = YARD) {
  await ctx.command(admin, `/tp ${bot.username} ${from.x + 0.5} ${from.y} ${from.z + 0.5} facing entity @e[tag=${tag},limit=1] eyes`, /^Teleported /)
  await ctx.sync(admin, [bot], 2)
  return ctx.command(bot, '/pet add', expect)
}

/** The lines /pet list answers a player with: the header, then one line per pet. */
export async function list (ctx, bot, count) {
  if (count === 0) {
    await ctx.command(bot, '/pet list', /^You have no pets registered\.$/)
    return []
  }
  const header = new RegExp(`^Your pets \\(${count} of \\d+\\):$`)
  const lines = await ctx.command(bot, '/pet list', [header, ...Array.from({ length: count }, () => /^- /)])
  return lines.slice(1)
}

/** Whether a tagged entity is loaded within `distance` of a position. */
export function near (ctx, admin, tag, at, distance = 2) {
  return ctx.holds(admin, `entity @e[tag=${tag},x=${at.x + 0.5},y=${at.y},z=${at.z + 0.5},distance=..${distance}]`)
}

/** Resolves once a tagged entity stands within `distance` of a player. */
export async function waitBeside (ctx, admin, tag, owner, distance = 2) {
  await ctx.eventually(async () => {
    const at = await ctx.position(admin, owner)
    assert(await ctx.holds(admin, `entity @e[tag=${tag},x=${at.x},y=${at.y},z=${at.z},distance=..${distance}]`), `${tag} is beside ${owner} at ${JSON.stringify(at)}`)
  }, 30_000)
}

/**
 * Moves a tagged entity to FAR (or `to`) and lets its chunk unload, entities and all, as when
 * nobody is near. Resolves once the entity is no longer loaded.
 */
export async function sendAway (ctx, admin, tag, to = FAR) {
  await forceload(ctx, admin, to, 1)
  await ctx.command(admin, `/tp @e[tag=${tag},limit=1] ${to.x + 0.5} ${to.y} ${to.z + 0.5}`, /^Teleported /)
  await ctx.eventually(async () => assert(await near(ctx, admin, tag, to), `${tag} is at ${JSON.stringify(to)}`))
  await ctx.command(admin, `/forceload remove ${to.x - 1} ${to.z - 1} ${to.x + 1} ${to.z + 1}`, /^(Unmarked|No chunks were removed)/)
  await ctx.eventually(async () => assert(!await ctx.holds(admin, `loaded ${to.x} ${to.y} ${to.z}`), 'the chunk unloaded'), 30_000)
  await ctx.eventually(async () => assert(!await ctx.holds(admin, `entity @e[tag=${tag}]`), `${tag} unloaded with its chunk`), 30_000)
}
