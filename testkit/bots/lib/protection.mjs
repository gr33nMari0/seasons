// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The Hall's protection in scenarios: opening containers, placing and hitting entities as a player
// would through the test plugin (bots can't click blocks through the protocol translation,
// docs/adr/0001), right-clicking entities as the bot itself, and checking that a view-only copy
// gives nothing away.
import { assert, waitUntil } from './context.mjs'
import { blockItems } from './hall.mjs'

/**
 * Right-clicks a container block as a player, then opens it as the game would if the click went
 * through. Resolves with what the player has open afterwards: 'real' (the block's own
 * inventory), 'copy' (anything else, such as Seasons' view-only copy) or 'nothing'.
 */
export async function openContainer (ctx, admin, name, at) {
  const line = await ctx.command(admin, `/scenario open ${name} ${at.x} ${at.y} ${at.z}`, /^Opened .*: (the real one|a copy|nothing)$/)
  if (line.endsWith('the real one')) return 'real'
  return line.endsWith('a copy') ? 'copy' : 'nothing'
}

/**
 * Right-clicks a block as a player, firing PlayerInteractEvent. Resolves with whether the click
 * went through, rather than being taken over and refused.
 */
export async function clickBlock (ctx, admin, name, at) {
  const line = await ctx.command(admin, `/scenario use ${name} ${at.x} ${at.y} ${at.z}`, /^Used /)
  return !line.includes('(the click was taken over)')
}

/** Places an entity, such as a cushion, as a player would, firing EntityPlaceEvent. */
export async function placeEntity (ctx, admin, name, type, at) {
  const line = await ctx.command(admin, `/scenario place-entity ${name} ${type} ${at.x} ${at.y} ${at.z}`, /^(Placed|Placing) /)
  return line.startsWith('Placed')
}

/** A player attacks the nearest entity of a type within 2 blocks of `at`, as a real hit would. */
export async function hit (ctx, admin, name, type, at) {
  await ctx.command(admin, `/scenario hit ${name} ${type} ${at.x} ${at.y} ${at.z}`, /^Hit /)
  await ctx.sync(admin, [], 6)
}

/** Whether an entity of a type stands (or hangs) at a block. */
export async function entityAt (ctx, admin, type, at) {
  return ctx.holds(admin, `entity @e[type=${type},x=${at.x},y=${at.y},z=${at.z},dx=0,dy=0,dz=0]`)
}

/** The bot's view of the nearest entity with a name (such as item_frame) within 1.5 of `at`. */
export function seen (bot, name, at) {
  const away = (e) => Math.hypot(e.position.x - at.x - 0.5, e.position.y - at.y, e.position.z - at.z - 0.5)
  const found = Object.values(bot.entities)
    .filter((e) => e.name === name && away(e) < 1.5)
    .sort((a, b) => away(a) - away(b))[0]
  assert(found, `${bot.username} sees a ${name} at ${JSON.stringify(at)}`)
  return found
}

/**
 * The bot right-clicks an entity itself; armour stands at their head, where the helmet is. Resolves
 * once the server has had two ticks to handle the click.
 */
export async function rightClick (ctx, bot, entity) {
  if (entity.name === 'armor_stand') {
    await bot.activateEntityAt(entity, entity.position.offset(0, 1.7, 0))
  } else {
    await bot.activateEntity(entity)
  }
  await ctx.sync(ctx.adminBot, [bot], 2)
}

/** The bot hits an entity itself, and resolves once the server has had two ticks to handle it. */
export async function attack (ctx, bot, entity) {
  bot.attack(entity)
  await ctx.sync(ctx.adminBot, [bot], 2)
}

/** Resolves with the window the bot has open, once it has one. */
export async function openWindow (bot) {
  await waitUntil(() => bot.currentWindow != null, 5_000, `${bot.username}'s window`)
  return bot.currentWindow
}

/**
 * With a view-only copy of a container open, tries the clicks that would take something (left,
 * shift, number key, drop, double-click) and checks that the container, the viewer's inventory
 * and the ground are all unchanged.
 */
export async function expectViewOnly (ctx, admin, bot, block) {
  const window = await openWindow(bot)
  const stocked = await blockItems(ctx, admin, block)
  const carried = await ctx.itemCount(admin, bot.username)
  const slot = window.slots.findIndex((item, i) => item && i < window.inventoryStart)
  assert(slot >= 0, `the copy shows what the container holds: ${JSON.stringify(window.slots.slice(0, window.inventoryStart))}`)
  const clicks = {
    'left click': () => bot.clickWindow(slot, 0, 0),
    'shift click': () => bot.clickWindow(slot, 0, 1),
    'number key 1': () => bot.clickWindow(slot, 0, 2),
    'drop stack': () => bot.clickWindow(slot, 1, 4)
  }
  for (const [what, click] of Object.entries(clicks)) {
    await click()
    await bot._syncWindow(bot.currentWindow)
    await ctx.sync(admin, [bot], 2)
    assert(await blockItems(ctx, admin, block) === stocked, `${what}: the container is unchanged`)
    assert(await ctx.itemCount(admin, bot.username) === carried, `${what}: ${bot.username} took nothing`)
    assert(!await ctx.holds(admin, 'entity @e[type=item]'), `${what}: nothing was dropped`)
  }
  bot.closeWindow(bot.currentWindow)
  await ctx.sync(admin, [bot], 2)
}

/** The audit log's entries this month, oldest first. */
export function audit (ctx) {
  const month = new Date().toISOString().slice(0, 7)
  return ctx.readFile(`world/dimensions/minecraft/overworld/seasons/audit/${month}.jsonl`)
    .trim()
    .split('\n')
    .map((line) => JSON.parse(line))
}

/**
 * Unloads the chunks of a forceloaded box and loads them again, firing the chunk load events. A
 * chunk stops counting as loaded before its entities unload; with entities in the box, it waits for
 * them to go too, or loading it again would keep the same entities and fire no events for them.
 */
export async function reloadChunks (ctx, admin, from, to, probe) {
  const box = `@e[type=!minecraft:player,x=${from.x},y=-64,z=${from.z},dx=${to.x - from.x},dy=384,dz=${to.z - from.z}]`
  const hadEntities = await ctx.holds(admin, `entity ${box}`)
  await ctx.command(admin, `/forceload remove ${from.x} ${from.z} ${to.x} ${to.z}`, /^(Unmarked|No chunks were removed)/)
  await ctx.eventually(async () => assert(!await ctx.holds(admin, `loaded ${probe.x} ${probe.y} ${probe.z}`), 'the chunk unloaded'), 30_000)
  if (hadEntities) {
    await ctx.eventually(async () => assert(!await ctx.holds(admin, `entity ${box}`), 'the entities in the box unloaded'), 30_000)
  }
  await ctx.command(admin, `/forceload add ${from.x} ${from.z} ${to.x} ${to.z}`, /^(Marked|No chunks were marked)/)
  await ctx.eventually(async () => assert(await ctx.holds(admin, `loaded ${probe.x} ${probe.y} ${probe.z}`), 'the chunk loaded again'), 30_000)
}
