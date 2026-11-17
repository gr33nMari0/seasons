// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A standard season's worth of gear, given with vanilla admin commands so every scenario that
// archives someone starts from the same, countable state.

/** Items in the inventory, armour and off hand, as /clear counts them. */
export const GEAR_ITEMS = 1 + 64 + 32 + 1 + 1
/** Stacks in the inventory, armour and off hand. */
export const GEAR_STACKS = 5
/** Items in the ender chest. */
export const ENDER_ITEMS = 5

/** Gives `name` the standard gear: a sword, dirt, torches, a helmet, a shield, ender emeralds. */
export async function giveGear (ctx, admin, name) {
  await ctx.command(admin, `/item replace entity ${name} hotbar.0 with minecraft:diamond_sword`, /^Replaced a slot/)
  await ctx.command(admin, `/item replace entity ${name} hotbar.1 with minecraft:dirt 64`, /^Replaced a slot/)
  await ctx.command(admin, `/item replace entity ${name} inventory.4 with minecraft:torch 32`, /^Replaced a slot/)
  await ctx.command(admin, `/item replace entity ${name} armor.head with minecraft:iron_helmet`, /^Replaced a slot/)
  await ctx.command(admin, `/item replace entity ${name} weapon.offhand with minecraft:shield`, /^Replaced a slot/)
  await ctx.command(admin, `/item replace entity ${name} enderchest.0 with minecraft:emerald 5`, /^Replaced a slot/)
}

/** XP, an effect, a respawn point and some advancements, all of which archiving resets. */
export async function giveProgress (ctx, admin, name) {
  await ctx.command(admin, `/xp add ${name} 30 levels`, /^Gave 30 experience levels/)
  await ctx.command(admin, `/effect give ${name} minecraft:speed 600`, /^Applied effect/)
  await ctx.command(admin, `/spawnpoint ${name} 10 -60 10`, /^Set spawn point/)
  await ctx.command(admin, `/advancement grant ${name} only minecraft:story/mine_stone`, /^Granted the advancement/)
  await ctx.command(admin, `/advancement grant ${name} only minecraft:story/root`, /^Granted the advancement/)
  await ctx.command(admin, `/advancement grant ${name} only minecraft:recipes/building_blocks/stone_slab`, /^Granted the advancement/)
}

/** Asserts a player carries nothing and has no ender chest items. */
export async function expectEmpty (ctx, admin, name, assert) {
  assert(await ctx.itemCount(admin, name) === 0, `${name} carries nothing`)
  assert(await ctx.entityData(admin, name, 'EnderItems') === '[]', `${name}'s ender chest is empty`)
}
