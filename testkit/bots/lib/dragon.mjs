// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The End's dragon fight in scenarios, played through /scenario dragon (docs/adr/0003): bots in the
// End, the dragon respawned with four crystals, and hits made as a player would make them.
import { SECOND, assert } from './context.mjs'
import { standAt } from './visits.mjs'
import { SPAWN } from './zones.mjs'

/** The 10-second death animation, then the egg on the portal: well within this. */
export const EGG_WAIT = 40 * SECOND

/**
 * The fight as the test plugin sees it: { alive, health, killed, phase, portal, egg }, with the
 * portal and the egg as "x,y,z" or "none".
 */
export async function dragonInfo (ctx, admin) {
  const line = await ctx.command(admin, '/scenario dragon info', /^Dragon /)
  const field = (key) => new RegExp(`${key}=(\\S+)`).exec(line)?.[1]
  return {
    alive: field('alive') === 'true',
    health: Number(field('health')),
    killed: field('killed') === 'true',
    phase: field('phase'),
    portal: field('portal'),
    egg: field('egg')
  }
}

/** Puts players in the End beside the exit portal, its chunks kept loaded, and waits for a dragon. */
export async function toTheEnd (ctx, admin, names) {
  await ctx.command(admin, '/execute in minecraft:the_end run forceload add -32 -32 32 32', /^(Marked|No chunks were marked)/)
  for (const [i, name] of names.entries()) {
    await ctx.command(admin, `/execute in minecraft:the_end run tp ${name} ${4 * i} 90 40`, /^Teleported /)
  }
  await ctx.sync(admin, [], 40)
}

/** Waits for a living dragon in the fight. */
export async function waitForDragon (ctx, admin, timeoutMs = 90 * SECOND) {
  await ctx.eventually(async () => assert((await dragonInfo(ctx, admin)).alive, 'a dragon is alive'), timeoutMs)
}

/**
 * Respawns the dragon as players do: a player places four end crystals on the exit portal and the
 * game's own check starts the summoning, with the previously-killed flag left alone. Then waits for
 * it; the summoning takes a while, so its ticks run as fast as the server can for a moment.
 */
export async function respawn (ctx, admin, name) {
  await ctx.command(admin, `/give ${name} minecraft:end_crystal 4`, /^Gave 4 /)
  await ctx.command(admin, '/tick sprint 3000', /./)
  await ctx.command(admin, `/scenario dragon respawn ${name}`, /^Respawning the dragon$/)
  await waitForDragon(ctx, admin)
}

/**
 * Hurts the dragon as a player, by a hit or an arrow, and resolves with the line. A body hit takes
 * a quarter of the amount and one more. Half a second passes first, so the hit isn't lost to the
 * dragon's moment of invulnerability after the last one.
 */
export async function hurt (ctx, admin, name, amount, how = 'melee') {
  await ctx.sync(admin, [], 12)
  return ctx.command(admin, `/scenario dragon hurt ${name} ${amount} ${how}`, /^Hurt the dragon as /)
}

/** Has a player break an end crystal by the dragon's head, so its explosion hurts the dragon. */
export async function crystal (ctx, admin, name) {
  await ctx.sync(admin, [], 12)
  return ctx.command(admin, `/scenario dragon crystal ${name}`, /^Set off a crystal as /)
}

/** Kills the dragon with no player involved, as /kill does. */
export async function killWithNobody (ctx, admin) {
  await ctx.command(admin, '/kill @e[type=minecraft:ender_dragon]', /^Killed /)
}

/** Resolves with the egg's position once one stands on the exit portal. */
export async function waitForEgg (ctx, admin) {
  return ctx.eventually(async () => {
    const { egg } = await dragonInfo(ctx, admin)
    assert(egg !== 'none', 'an egg is on the portal')
    return egg
  }, EGG_WAIT)
}

/** Whether any celebration firework is in the air over the exit portal. */
export async function fireworksOverPortal (ctx, admin) {
  const line = await ctx.command(admin, '/execute in minecraft:the_end if entity @e[type=minecraft:firework_rocket,x=0,y=75,z=0,distance=..24]', /^Test (passed|failed)/)
  return line.startsWith('Test passed')
}

/** Ends season `from` at once and launches the next at SPAWN, everyone online ending there. */
export async function nextSeason (ctx, admin, from) {
  await ctx.command(admin, '/season end --now', new RegExp(`^Ending Season ${from} now`))
  await ctx.waitForMessages(admin, new RegExp(`^Season ${from} has ended\\.`), 20 * SECOND)
  await standAt(ctx, admin, admin.username, SPAWN)
  await ctx.command(admin, '/season spawn set', new RegExp(`^Season ${from + 1}'s spawn is now `))
  await ctx.command(admin, '/season launch', new RegExp(`^Season ${from + 1} has begun!$`), 30 * SECOND)
}

/** Sends Seasons' Discord posts to a receiver. */
export async function discordTo (ctx, admin, discord) {
  ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\n`)
  await ctx.command(admin, '/season reload', /^Reloaded /)
}
