// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Dashboards laid out on a 12-column grid (M9): where each widget sits and how big it is, kept in
// this browser only. Pure, so Node's test runner checks it: placing, moving, resizing and adding
// widgets without overlaps, share codes, the web.yml screens block, and what storage holds.

export const COLUMNS = 12
export const MAX_ROWS = 60

/** Each widget's default and smallest size, in columns and 44-pixel rows (rows grow with content). */
export const SIZES = {
  season: { w: 4, h: 4, minW: 3, minH: 3 },
  online: { w: 4, h: 5, minW: 3, minH: 3 },
  'event-feed': { w: 4, h: 5, minW: 3, minH: 3 },
  map: { w: 8, h: 9, minW: 4, minH: 5 },
  leaderboards: { w: 4, h: 6, minW: 3, minH: 4 },
  hall: { w: 12, h: 4, minW: 6, minH: 3 },
  rules: { w: 4, h: 4, minW: 3, minH: 3 },
  waypoints: { w: 4, h: 4, minW: 3, minH: 3 },
  converter: { w: 4, h: 5, minW: 3, minH: 4 },
  links: { w: 4, h: 4, minW: 3, minH: 2 },
  help: { w: 4, h: 5, minW: 3, minH: 3 },
  museum: { w: 12, h: 4, minW: 6, minH: 3 },
  history: { w: 12, h: 4, minW: 6, minH: 3 },
  'server-health': { w: 4, h: 4, minW: 3, minH: 3 },
  'pending-players': { w: 4, h: 4, minW: 3, minH: 3 },
  'audit-log': { w: 8, h: 6, minW: 4, minH: 4 }
}

const DEFAULT_SIZE = { w: 4, h: 4, minW: 3, minH: 3 }
export const sizeOf = (id) => SIZES[id] ?? DEFAULT_SIZE

const overlaps = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n))

/** One item made whole: whole numbers, within the grid, no smaller than its widget allows. */
export function normalise (item) {
  const s = sizeOf(item.id)
  const w = clamp(Math.round(Number(item.w) || s.w), s.minW, COLUMNS)
  const h = clamp(Math.round(Number(item.h) || s.h), s.minH, 20)
  const x = clamp(Math.round(Number(item.x) || 0), 0, COLUMNS - w)
  const y = clamp(Math.round(Number(item.y) || 0), 0, MAX_ROWS)
  return { id: String(item.id), x, y, w, h }
}

/** The first free spot for an item of a size, scanning rows from the top. */
function firstFit (placed, w, h) {
  for (let y = 0; y <= MAX_ROWS; y++) {
    for (let x = 0; x + w <= COLUMNS; x++) {
      const probe = { x, y, w, h }
      if (!placed.some((p) => overlaps(p, probe))) return { x, y }
    }
  }
  return { x: 0, y: MAX_ROWS }
}

/** Widgets in their default sizes, packed into the first free spots in order. */
export function place (ids) {
  const placed = []
  for (const id of ids) {
    const s = sizeOf(id)
    const at = firstFit(placed, s.w, s.h)
    placed.push({ id, ...at, w: s.w, h: s.h })
  }
  return placed
}

/**
 * Items without overlaps: the item that moved (if any) keeps its place, each other item keeps its
 * place if free or goes down until it is, then everything rises as far as it can, so no gaps open.
 */
export function resolve (items, moved = null) {
  const list = items.map(normalise)
  const first = list.filter((i) => i.id === moved)
  const rest = list.filter((i) => i.id !== moved).sort((a, b) => a.y - b.y || a.x - b.x)
  const placed = []
  for (const item of [...first, ...rest]) {
    const next = { ...item }
    while (placed.some((p) => overlaps(p, next)) && next.y < MAX_ROWS) next.y++
    placed.push(next)
  }
  // Gravity: each item rises while nothing is in its way.
  placed.sort((a, b) => a.y - b.y || a.x - b.x)
  for (const item of placed) {
    while (item.y > 0) {
      const up = { ...item, y: item.y - 1 }
      if (placed.some((p) => p !== item && overlaps(p, up))) break
      item.y--
    }
  }
  return order(placed)
}

/** Items in reading order: by row, then column. */
export const order = (items) => [...items].sort((a, b) => a.y - b.y || a.x - b.x)

export function move (items, id, x, y) {
  return resolve(items.map((i) => (i.id === id ? { ...i, x, y } : i)), id)
}

export function resize (items, id, w, h) {
  return resolve(items.map((i) => (i.id === id ? { ...i, w, h } : i)), id)
}

/** Adds a widget at the first free spot, in its default size; a widget already there stays. */
export function add (items, id) {
  if (items.some((i) => i.id === id)) return items
  const s = sizeOf(id)
  return resolve([...items, { id, ...firstFit(items.map(normalise), s.w, s.h), w: s.w, h: s.h }])
}

export function remove (items, id) {
  return resolve(items.filter((i) => i.id !== id))
}

/** "Season clock: column 3, row 2, 4 wide, 3 high", for the screen reader after a move. */
export function describe (item, title) {
  return { title, column: item.x + 1, row: item.y + 1, wide: item.w, high: item.h }
}

// Share codes: "S1." and the layout as base64url JSON, so a layout can be pasted on another device.

const CODE = 'S1.'

function b64url (text) {
  const bytes = new TextEncoder().encode(text)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function unb64url (code) {
  const bin = atob(code.replace(/-/g, '+').replace(/_/g, '/'))
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
}

/** A dashboard as a share code. */
export function encode (dashboard) {
  return CODE + b64url(JSON.stringify({ n: String(dashboard.name).slice(0, 40), i: dashboard.items.map((i) => [i.id, i.x, i.y, i.w, i.h]) }))
}

/** A share code as a dashboard { name, items }, or null if it isn't one. Unknown widgets are kept: the page says they aren't available. */
export function decode (code) {
  const text = String(code ?? '').trim()
  if (!text.startsWith(CODE) || text.length > 4000) return null
  try {
    const data = JSON.parse(unb64url(text.slice(CODE.length)))
    if (!data || !Array.isArray(data.i) || data.i.length > 40) return null
    const items = []
    for (const entry of data.i) {
      if (!Array.isArray(entry) || typeof entry[0] !== 'string' || !/^[a-z0-9-]{1,32}$/.test(entry[0])) return null
      if (entry.slice(1).some((n) => !Number.isFinite(n))) return null
      items.push({ id: entry[0], x: entry[1], y: entry[2], w: entry[3], h: entry[4] })
    }
    const name = typeof data.n === 'string' && data.n.trim() ? data.n.trim().slice(0, 40) : null
    return { name, items: resolve(items) }
  } catch {
    return null
  }
}

/** The screens: block of web.yml for a dashboard, its widgets in reading order. */
export function toWebYml (dashboard) {
  const slug = String(dashboard.name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 32) || 'my-screen'
  const widgets = order(dashboard.items).map((i) => (i.id === 'season' ? 'season-clock' : i.id === 'leaderboards' ? 'leaderboard' : i.id))
  return `screens:\n  ${slug}:\n    layout: landscape\n    widgets: [${widgets.join(', ')}]\n`
}

// Storage: this browser only, per dashboard address (each server is its own origin).

export const STORAGE_KEY = 'seasons-dashboards'

/** What storage holds, made safe: { dashboards: [{ id, name, items }], active, screen }. */
export function parseStore (text) {
  let data
  try { data = JSON.parse(text ?? 'null') } catch { data = null }
  const dashboards = Array.isArray(data?.dashboards)
    ? data.dashboards
      .filter((d) => d && typeof d.id === 'string' && Array.isArray(d.items))
      .slice(0, 12)
      .map((d) => ({ id: d.id, name: typeof d.name === 'string' && d.name.trim() ? d.name.slice(0, 40) : 'Dashboard', items: resolve(d.items.filter((i) => i && typeof i.id === 'string')) }))
    : []
  const ids = new Set(dashboards.map((d) => d.id))
  return {
    dashboards,
    active: ids.has(data?.active) ? data.active : null,
    screen: ids.has(data?.screen) ? data.screen : null
  }
}

export function newId (store) {
  let n = store.dashboards.length + 1
  while (store.dashboards.some((d) => d.id === `d${n}`)) n++
  return `d${n}`
}
