// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Dashboards on a 12-column grid (M9): widgets never overlap or leave the grid, moving and resizing
// push others aside and close gaps, share codes and web.yml blocks round-trip, and storage is read
// safely whatever it holds.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { module } from './paths.mjs'

const L = await module('layout.mjs')

const noOverlaps = (items) => {
  for (const a of items) {
    assert.ok(a.x >= 0 && a.x + a.w <= L.COLUMNS, `${a.id} within the columns`)
    assert.ok(a.y >= 0, `${a.id} within the rows`)
    for (const b of items) {
      if (a === b) continue
      const hit = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
      assert.ok(!hit, `${a.id} and ${b.id} overlap`)
    }
  }
}

test('the default layout packs widgets in order, in their default sizes', () => {
  const items = L.place(['season', 'online', 'event-feed', 'map'])
  assert.deepEqual(items.map((i) => [i.id, i.x, i.y, i.w, i.h]), [
    ['season', 0, 0, 4, 4], ['online', 4, 0, 4, 5], ['event-feed', 8, 0, 4, 5], ['map', 0, 5, 8, 9]
  ])
  noOverlaps(items)
})

test('moving a widget onto others pushes them down; gaps close', () => {
  const start = L.place(['season', 'online', 'event-feed'])
  const moved = L.move(start, 'event-feed', 0, 0)
  noOverlaps(moved)
  const feed = moved.find((i) => i.id === 'event-feed')
  assert.deepEqual([feed.x, feed.y], [0, 0], 'the moved widget keeps where it was put')
  assert.ok(moved.find((i) => i.id === 'season').y >= 5, 'the season clock went below it')
  const minY = Math.min(...moved.filter((i) => i.id !== 'event-feed').map((i) => i.y))
  assert.ok(minY <= 5, 'nothing floats with a gap above it')
})

test('resizing is kept within the grid and the widget\'s smallest size', () => {
  const items = L.place(['season', 'map'])
  const wide = L.resize(items, 'season', 20, 1)
  const s = wide.find((i) => i.id === 'season')
  assert.equal(s.w, 12)
  assert.equal(s.h, L.SIZES.season.minH)
  assert.equal(s.x, 0)
  noOverlaps(wide)
  const off = L.move(items, 'map', 11, 0)
  assert.equal(off.find((i) => i.id === 'map').x, 4, 'a move past the edge stops at it')
})

test('adding finds a free spot; adding twice changes nothing; removing closes the gap', () => {
  let items = L.place(['season', 'online'])
  items = L.add(items, 'converter')
  noOverlaps(items)
  assert.equal(L.add(items, 'converter'), items)
  items = L.remove(items, 'season')
  assert.ok(!items.some((i) => i.id === 'season'))
  assert.equal(Math.min(...items.map((i) => i.y)), 0)
})

test('a share code round-trips, refuses junk and keeps widgets it doesn\'t know', () => {
  const dashboard = { name: 'Build night', items: L.move(L.place(['season', 'map', 'online']), 'online', 8, 0) }
  const code = L.encode(dashboard)
  assert.match(code, /^S1\.[A-Za-z0-9_-]+$/)
  const back = L.decode(code)
  assert.equal(back.name, 'Build night')
  assert.deepEqual(back.items, L.resolve(dashboard.items))
  for (const junk of ['', 'hello', 'S1.!!!', 'S1.' + btoa('{"i":[["<b>",0,0,4,3]]}'), 'S1.' + btoa('{"i":[["season","x",0,4,3]]}')]) {
    assert.equal(L.decode(junk), null, junk)
  }
  const unknown = L.decode(L.encode({ name: 'x', items: [{ id: 'audit-log', x: 0, y: 0, w: 8, h: 6 }] }))
  assert.equal(unknown.items[0].id, 'audit-log')
})

test('web.yml\'s screens block names widgets in reading order, by the spec\'s names', () => {
  const yml = L.toWebYml({ name: 'Second Screen!', items: L.place(['season', 'leaderboards', 'map']) })
  assert.equal(yml, 'screens:\n  second-screen:\n    layout: landscape\n    widgets: [season-clock, leaderboard, map]\n')
})

test('storage is read safely, whatever it holds', () => {
  assert.deepEqual(L.parseStore(null), { dashboards: [], active: null, screen: null })
  assert.deepEqual(L.parseStore('not json'), { dashboards: [], active: null, screen: null })
  const store = L.parseStore(JSON.stringify({
    dashboards: [{ id: 'd1', name: 'Mine', items: [{ id: 'season', x: 99, y: -5, w: 1, h: 99 }] }, { nope: true }],
    active: 'd1',
    screen: 'gone'
  }))
  assert.equal(store.dashboards.length, 1)
  assert.deepEqual(store.dashboards[0].items[0], { id: 'season', x: 9, y: 0, w: 3, h: 20 })
  assert.equal(store.active, 'd1')
  assert.equal(store.screen, null)
  assert.equal(L.newId(store), 'd2')
})
