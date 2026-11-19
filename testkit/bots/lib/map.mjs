// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// What BlueMap holds in Seasons' marker sets, read through BlueMap's own API by the test plugin's
// /scenario bluemap, on a scenario server that has BlueMap (// @plugins bluemap, docs/adr/0004).
import { assert } from './context.mjs'

const TIMEOUT_MS = 10_000

/**
 * Every marker in a marker set of Seasons' on every BlueMap map, and BlueMap's API instance, which
 * a reload replaces. Resolves with { api, maps, markers, sets }: each marker
 * { dimension, map, set, id, type, x, y, z, minY, maxY, points, holes, label }, each set
 * { dimension, map, id, label }. Rejects while BlueMap's API isn't enabled.
 */
export function bluemap (ctx, admin) {
  return new Promise((resolve, reject) => {
    const markers = []
    const sets = []
    const onMessage = (text) => {
      if (text.startsWith('BMSET|')) {
        const [, dimension, map, id, label] = text.split('|')
        sets.push({ dimension, map, id, label })
      } else if (text.startsWith('BM|')) {
        const [, dimension, map, set, id, type, at, shape, label] = text.split('|')
        const [x, y, z] = at.split(' ').map(Number)
        const marker = { dimension, map, set, id, type, x, y, z, label }
        if (shape !== '-') {
          const [range, points, holes] = shape.split(' ')
          const [minY, maxY] = range.split('..').map(Number)
          Object.assign(marker, { minY, maxY, points: Number(points), holes: Number(holes) })
        }
        markers.push(marker)
      } else if (text === 'BM off') {
        cleanup()
        reject(new Error("BlueMap's API isn't enabled"))
      } else if (text.startsWith('BM done ')) {
        const [, , maps, api] = text.split(' ')
        cleanup()
        resolve({ api, maps: Number(maps), markers, sets })
      }
    }
    const timer = setTimeout(() => {
      cleanup()
      reject(new Error(`no answer from /scenario bluemap within ${TIMEOUT_MS} ms`))
    }, TIMEOUT_MS)
    const cleanup = () => { clearTimeout(timer); admin.off('messagestr', onMessage) }
    admin.on('messagestr', onMessage)
    ctx.log(`[${admin.username}] -> /scenario bluemap`)
    admin.chat('/scenario bluemap')
  })
}

/**
 * The markers on one dimension's maps, keyed "set/id". Every map of the dimension must hold the
 * same ones, with the same labels.
 */
export function markersIn (found, dimension) {
  const byMap = new Map()
  for (const marker of found.markers.filter((m) => m.dimension === dimension)) {
    if (!byMap.has(marker.map)) byMap.set(marker.map, new Map())
    byMap.get(marker.map).set(`${marker.set}/${marker.id}`, marker)
  }
  const maps = [...byMap.values()]
  if (maps.length === 0) return new Map()
  const keys = (m) => [...m.entries()].map(([k, v]) => `${k}=${v.label}`).sort().join(',')
  for (const other of maps.slice(1)) {
    assert(keys(other) === keys(maps[0]), `every ${dimension} map holds the same markers: ${keys(maps[0])} and ${keys(other)}`)
  }
  return maps[0]
}

/** A short list of markers, for assertion messages. */
export function describe (markers) {
  return [...markers.values()].map((m) => `${m.set}/${m.id} "${m.label}"`).join(', ')
}
