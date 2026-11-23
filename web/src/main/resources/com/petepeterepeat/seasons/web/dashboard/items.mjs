// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Which of our own icons (icons.svg) stands for an item: one per kind of item, never a Minecraft
// texture (spec, Security).

const FOOD = new Set([
  'apple', 'golden_apple', 'enchanted_golden_apple', 'bread', 'cookie', 'cake', 'pumpkin_pie',
  'carrot', 'golden_carrot', 'potato', 'baked_potato', 'poisonous_potato', 'beetroot', 'melon_slice',
  'sweet_berries', 'glow_berries', 'chorus_fruit', 'dried_kelp', 'honey_bottle', 'mushroom_stew',
  'rabbit_stew', 'beetroot_soup', 'suspicious_stew', 'beef', 'cooked_beef', 'porkchop',
  'cooked_porkchop', 'chicken', 'cooked_chicken', 'mutton', 'cooked_mutton', 'rabbit',
  'cooked_rabbit', 'cod', 'cooked_cod', 'salmon', 'cooked_salmon', 'tropical_fish', 'pufferfish',
  'rotten_flesh', 'spider_eye'
])

const BOOKS = new Set(['book', 'enchanted_book', 'writable_book', 'written_book', 'knowledge_book'])
const POTIONS = new Set(['potion', 'splash_potion', 'lingering_potion', 'ominous_bottle', 'experience_bottle'])
const BLOCKISH = /(_block|_planks|_log|_wood|_stone|_bricks|_slab|_stairs|_wall|_ore|_glass|_wool|_terracotta|_concrete|_shulker_box|^stone|^dirt|^sand|^gravel|^cobblestone|^deepslate|^netherrack|^obsidian|chest$|^barrel)/

/** The icon's id in icons.svg for an item id such as "minecraft:diamond_sword". */
export function iconFor (id) {
  const name = String(id).replace(/^[a-z0-9_.-]+:/, '')
  if (name.endsWith('_sword') || name === 'mace') return 'sword'
  if (name.endsWith('_axe')) return 'axe'
  if (name.endsWith('_pickaxe')) return 'pickaxe'
  if (name.endsWith('_shovel')) return 'shovel'
  if (name.endsWith('_hoe')) return 'hoe'
  if (name === 'bow' || name === 'crossbow') return 'bow'
  if (name === 'trident') return 'trident'
  if (name.endsWith('_helmet') || name === 'turtle_helmet') return 'helmet'
  if (name.endsWith('_chestplate')) return 'chestplate'
  if (name.endsWith('_leggings')) return 'leggings'
  if (name.endsWith('_boots')) return 'boots'
  if (name === 'elytra') return 'elytra'
  if (name === 'shield') return 'shield'
  if (BOOKS.has(name)) return 'book'
  if (FOOD.has(name)) return 'food'
  if (POTIONS.has(name)) return 'potion'
  if (name === 'totem_of_undying') return 'totem'
  if (name === 'dragon_egg') return 'egg'
  if (name.startsWith('music_disc_')) return 'disc'
  if (BLOCKISH.test(name)) return 'block'
  return 'item'
}

/** Every icon iconFor gives, which icons.svg must hold. */
export const ICONS = [
  'sword', 'axe', 'pickaxe', 'shovel', 'hoe', 'bow', 'trident', 'helmet', 'chestplate', 'leggings',
  'boots', 'elytra', 'shield', 'book', 'food', 'potion', 'totem', 'egg', 'disc', 'block', 'item'
]
