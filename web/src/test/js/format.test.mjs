// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { module } from './paths.mjs'

const f = await module('format.mjs')

test('countdowns in the largest two units', () => {
  const s = 1000
  assert.equal(f.countdown(((2 * 24 + 3) * 3600 + 30 * 60) * s), '2d 3h')
  assert.equal(f.countdown((3 * 3600 + 20 * 60 + 5) * s), '3h 20m')
  assert.equal(f.countdown((9 * 60 + 5) * s), '9m 05s')
  assert.equal(f.countdown(42 * s), '42s')
  assert.equal(f.countdown(-5 * s), '0s')
})

test('durations in hours and minutes', () => {
  assert.equal(f.duration(3 * 3600 + 20 * 60), '3h 20m')
  assert.equal(f.duration(45 * 60), '45m')
  assert.equal(f.duration(10), 'under a minute')
})

test('ids in words', () => {
  assert.equal(f.words('minecraft:diamond_sword'), 'Diamond Sword')
  assert.equal(f.words('minecraft:music_disc_pigstep'), 'Music Disc Pigstep')
  assert.equal(f.words('minecraft:heart_of_the_sea'), 'Heart of the Sea')
  assert.equal(f.words('plains'), 'Plains')
})

test('enchantments with roman levels, one-level ones without', () => {
  assert.equal(f.enchantment({ id: 'minecraft:sharpness', level: 5 }), 'Sharpness V')
  assert.equal(f.enchantment({ id: 'minecraft:unbreaking', level: 3 }), 'Unbreaking III')
  assert.equal(f.enchantment({ id: 'minecraft:mending', level: 1 }), 'Mending')
  assert.equal(f.enchantment({ id: 'minecraft:binding_curse', level: 1 }), 'Curse of Binding')
  assert.equal(f.enchantment({ id: 'minecraft:efficiency', level: 12 }), 'Efficiency 12')
})

test('items as text: the name given, the item, the count, enchantments and trim', () => {
  assert.deepEqual(
    f.item({ id: 'minecraft:diamond_sword', count: 1, name: 'Edge', enchantments: [{ id: 'minecraft:sharpness', level: 5 }] }),
    { title: 'Edge', kind: 'Diamond Sword', details: ['Sharpness V'] })
  assert.deepEqual(f.item({ id: 'minecraft:bread', count: 12, enchantments: [] }), { title: '12 × Bread', kind: null, details: [] })
  assert.deepEqual(
    f.item({ id: 'minecraft:diamond_helmet', count: 1, enchantments: [], trim: { pattern: 'minecraft:coast', material: 'minecraft:gold' } }),
    { title: 'Diamond Helmet', kind: null, details: ['Coast trim, Gold'] })
  const html = '<img src=x onerror=alert(1)>'
  assert.equal(f.item({ id: 'minecraft:iron_sword', name: html }).title, html, 'a name is kept exactly')
})

test('dimensions and regions, never coordinates', () => {
  assert.equal(f.dimension('minecraft:the_nether'), 'The Nether')
  assert.equal(f.dimension('minecraft:overworld'), 'Overworld')
  assert.equal(f.dimension('mymod:sky'), 'Sky')
  assert.equal(f.region({ kind: 'biome', name: 'minecraft:dark_forest' }), 'Dark Forest')
  assert.equal(f.region({ kind: 'waypoint', name: 'Big Tree' }), 'Big Tree')
})

test('statistics as the season card shows them', () => {
  assert.equal(f.statLabel('minecraft:play_time'), 'Play time')
  assert.equal(f.statLabel('minecraft:jump'), 'Jumps')
  assert.equal(f.statLabel('minecraft:walk_one_cm'), 'Distance walked')
  assert.equal(f.statLabel('minecraft:aviate_one_cm'), 'Distance by elytra')
  assert.equal(f.statLabel('minecraft:boat_one_cm'), 'Distance by boat')
  assert.equal(f.statValue('minecraft:play_time', 20 * 3600 * 2), '2h 0m')
  assert.equal(f.statValue('minecraft:walk_one_cm', 123456700), '1,234,567 blocks')
  assert.equal(f.statValue('minecraft:damage_dealt', 125), '12.5')
  assert.equal(f.statValue('minecraft:jump', 1234), '1,234')
})

test('placeholders filled as text', () => {
  assert.equal(f.fill('<name> joined', { name: '<b>Sam</b>' }), '<b>Sam</b> joined')
  assert.equal(f.fill('Season <season>', {}), 'Season <season>')
})

test("Spotify's embed address, for open.spotify.com links only", () => {
  assert.equal(f.spotifyEmbed('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc'), 'https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M')
  assert.equal(f.spotifyEmbed('https://open.spotify.com/intl-de/album/1A2B3C'), 'https://open.spotify.com/embed/album/1A2B3C')
  assert.equal(f.spotifyEmbed('https://evil.example/playlist/1'), null)
  assert.equal(f.spotifyEmbed('http://open.spotify.com/playlist/1'), null)
  assert.equal(f.spotifyEmbed('not a url'), null)
})

test('bytes', () => {
  assert.equal(f.bytes(512 * 1024 ** 2), '512 MB')
  assert.equal(f.bytes(1.5 * 1024 ** 3), '1.5 GB')
})
