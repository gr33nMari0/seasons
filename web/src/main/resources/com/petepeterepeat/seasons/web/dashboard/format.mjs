// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Words for what the API sends: countdowns, names from ids, enchantments, trims, statistics,
// dimensions and regions. Pure functions, so Node's test runner checks them. Nothing here comes from
// Minecraft's language files (docs/questions.md): names are the id in words.

/** "2d 3h", "3h 20m", "9m 05s" or "0s", for a time left in milliseconds. */
export function countdown (ms) {
  const total = Math.max(0, Math.floor(ms / 1000))
  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m ${String(seconds).padStart(2, '0')}s`
  return `${seconds}s`
}

/** "3h 20m", "45m" or "under a minute", for a duration in seconds. */
export function duration (seconds) {
  const s = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(s / 3600)
  const minutes = Math.floor((s % 3600) / 60)
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m`
  return 'under a minute'
}

/** A number with thousands separators, as the season card writes them. */
export function group (n) {
  return Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/** "Diamond Sword" from "minecraft:diamond_sword". */
export function words (id) {
  const name = String(id ?? '').replace(/^[a-z0-9_.-]+:/, '')
  return name
    .split(/[_/]+/)
    .filter(Boolean)
    .map((w, i) => (i > 0 && SMALL.has(w)) ? w : w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

const SMALL = new Set(['of', 'on', 'the', 'a', 'an', 'and', 'in', 'with', 'to', 'by'])

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X']

/** I to X, and the number itself beyond. */
export function roman (n) {
  return n >= 1 && n <= 10 ? ROMAN[n] : String(n)
}

/** Enchantments that only ever have one level, written without one. */
const ONE_LEVEL = new Set([
  'mending', 'silk_touch', 'infinity', 'flame', 'channeling', 'multishot', 'aqua_affinity',
  'binding_curse', 'vanishing_curse'
])

/** "Sharpness V", "Mending", "Curse of Binding". */
export function enchantment ({ id, level }) {
  const bare = String(id).replace(/^[a-z0-9_.-]+:/, '')
  let name = words(id)
  if (bare.endsWith('_curse')) name = `Curse of ${words(bare.replace(/_curse$/, ''))}`
  return ONE_LEVEL.has(bare) && level === 1 ? name : `${name} ${roman(level)}`
}

/** "Coast trim, Gold". */
export function trim ({ pattern, material }) {
  return `${words(pattern)} trim, ${words(material)}`
}

/**
 * An archived item as text: its title (the name given, else the item's), the item when named, the
 * count, and its enchantments and trim.
 */
export function item ({ id, count = 1, name, enchantments = [], trim: t }) {
  const kind = words(id)
  const title = name ?? kind
  return {
    title: count > 1 ? `${count} × ${title}` : title,
    kind: name ? kind : null,
    details: [
      ...enchantments.map(enchantment),
      ...(t ? [trim(t)] : [])
    ]
  }
}

const DIMENSIONS = {
  'minecraft:overworld': 'Overworld',
  'minecraft:the_nether': 'The Nether',
  'minecraft:the_end': 'The End'
}

/** "Overworld", "The Nether", "The End", or another dimension's id in words. */
export function dimension (id) {
  return DIMENSIONS[id] ?? words(id)
}

/** Where a player is: a waypoint's or zone's name, or a biome in words; never coordinates. */
export function region (r) {
  if (!r) return ''
  return r.kind === 'biome' ? words(r.name) : r.name
}

/** Statistics counted in ticks, shown as time. */
const TICKS = new Set([
  'minecraft:play_time', 'minecraft:total_world_time', 'minecraft:time_since_death',
  'minecraft:time_since_rest', 'minecraft:sneak_time'
])

/** The General tab's statistics in the game's own words where the id says less. */
const LABELS = {
  'minecraft:play_time': 'Play time',
  'minecraft:total_world_time': 'Time with the world open',
  'minecraft:time_since_death': 'Since last death',
  'minecraft:time_since_rest': 'Since last rest',
  'minecraft:sneak_time': 'Sneak time',
  'minecraft:deaths': 'Deaths',
  'minecraft:jump': 'Jumps',
  'minecraft:leave_game': 'Games quit',
  'minecraft:mob_kills': 'Mob kills',
  'minecraft:player_kills': 'Player kills',
  'minecraft:animals_bred': 'Animals bred',
  'minecraft:fish_caught': 'Fish caught',
  'minecraft:drop': 'Items dropped',
  'minecraft:enchant_item': 'Items enchanted',
  'minecraft:traded_with_villager': 'Trades with villagers',
  'minecraft:talked_to_villager': 'Talks with villagers',
  'minecraft:sleep_in_bed': 'Times slept in a bed',
  'minecraft:raid_win': 'Raids won',
  'minecraft:raid_trigger': 'Raids triggered',
  'minecraft:damage_dealt': 'Damage dealt',
  'minecraft:damage_taken': 'Damage taken',
  'minecraft:damage_blocked_by_shield': 'Damage blocked by a shield',
  'minecraft:damage_absorbed': 'Damage absorbed',
  'minecraft:damage_resisted': 'Damage resisted',
  'minecraft:damage_dealt_absorbed': 'Damage dealt (absorbed)',
  'minecraft:damage_dealt_resisted': 'Damage dealt (resisted)'
}

const DISTANCES = {
  walk: 'Distance walked',
  walk_on_water: 'Distance walked on water',
  walk_under_water: 'Distance walked under water',
  sprint: 'Distance sprinted',
  crouch: 'Distance crouched',
  swim: 'Distance swum',
  climb: 'Distance climbed',
  fall: 'Distance fallen',
  fly: 'Distance flown',
  aviate: 'Distance by elytra'
}

/** A statistic's name in words: "Jumps"; "minecraft:walk_one_cm" is "Distance walked". */
export function statLabel (id) {
  if (LABELS[id]) return LABELS[id]
  const cm = /^minecraft:(\w+)_one_cm$/.exec(id)
  if (cm) return DISTANCES[cm[1]] ?? `Distance by ${words(cm[1]).toLowerCase()}`
  return words(id)
}

/** A statistic's value as the season card shows it: ticks as time, centimetres as blocks. */
export function statValue (id, value) {
  if (TICKS.has(id)) return duration(value / 20)
  if (id.endsWith('_one_cm')) return `${group(value / 100)} blocks`
  if (id.startsWith('minecraft:damage_')) return (value / 10).toFixed(1)
  return group(value)
}

/** Fills <name> placeholders in a line of text from messages.yml; never parsed as markup. */
export function fill (text, values = {}) {
  return String(text).replace(/<([a-z-]+)>/g, (whole, key) => (key in values ? String(values[key]) : whole))
}

/** A short local date, such as "23 Nov 2026". */
export function date (iso) {
  const d = new Date(iso)
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
}

/** A short local time, such as "10:42". */
export function time (iso) {
  const d = new Date(iso)
  return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
}

/** Bytes as "512 MB" or "1.5 GB". */
export function bytes (n) {
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GB`
  return `${Math.round(n / 1024 ** 2)} MB`
}

/** The Spotify embed's address for an open.spotify.com link, or null. */
export function spotifyEmbed (url) {
  let u
  try {
    u = new URL(url)
  } catch {
    return null
  }
  if (u.protocol !== 'https:' || u.hostname !== 'open.spotify.com') return null
  const m = /^\/(?:intl-[a-z-]+\/)?(playlist|album|track|artist|show|episode)\/([A-Za-z0-9]+)/.exec(u.pathname)
  return m ? `https://open.spotify.com/embed/${m[1]}/${m[2]}` : null
}
