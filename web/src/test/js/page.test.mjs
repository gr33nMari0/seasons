// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Rules the whole page keeps: no code that parses HTML, nothing loaded from another site, every
// line of text it asks for in messages.yml, and an icon for every kind of item.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { DASHBOARD, MESSAGES, module } from './paths.mjs'

const files = readdirSync(DASHBOARD)
const code = files.filter((f) => f.endsWith('.mjs')).map((f) => [f, readFileSync(join(DASHBOARD, f), 'utf8')])

test('nothing in the page parses HTML, so text stays text', () => {
  for (const [name, text] of code) {
    for (const banned of ['innerHTML', 'outerHTML', 'insertAdjacentHTML', 'document.write', 'eval(', 'new Function', 'DOMParser', 'createContextualFragment']) {
      assert.ok(!text.includes(banned), `${name} uses ${banned}`)
    }
  }
})

test('nothing is loaded from another site', () => {
  for (const name of files) {
    const text = readFileSync(join(DASHBOARD, name), 'utf8')
    const urls = [...text.matchAll(/https?:\/\/[^\s'"`)<>]+/g)].map((m) => m[0])
    const allowed = (u) => u.startsWith('https://textures.minecraft.net') || u.startsWith('https://open.spotify.com') || u === 'http://www.w3.org/2000/svg'
    assert.deepEqual(urls.filter((u) => !allowed(u)), [], `${name} names another site`)
  }
  const html = readFileSync(join(DASHBOARD, 'index.html'), 'utf8')
  assert.ok(!/<script>|<style|\sstyle=|\son[a-z]+=/i.test(html), 'no inline script, style or handler in the page')
})

/** The dashboard's lines in messages.yml, as dotted keys: a small reader for its simple YAML. */
function dashboardKeys () {
  const lines = readFileSync(MESSAGES, 'utf8').split('\n')
  const start = lines.indexOf('dashboard:')
  assert.ok(start >= 0, 'messages.yml has a dashboard section')
  const keys = new Set()
  const path = []
  for (const line of lines.slice(start + 1)) {
    if (/^\S/.test(line)) break
    const m = /^( +)"?([a-z0-9-]+)"?:\s*(.*)$/.exec(line)
    if (!m) continue
    const depth = m[1].length / 2 - 1
    path.length = depth
    path.push(m[2])
    if (m[3]) keys.add(path.join('.'))
  }
  return keys
}

test('every line the page asks for is in messages.yml', () => {
  const keys = dashboardKeys()
  const asked = new Set()
  for (const [, text] of code) {
    for (const m of text.matchAll(/\bt\('([a-z0-9.-]+)'/g)) asked.add(m[1])
  }
  const families = {
    stage: ['frontier', 'open'],
    phase: ['countdown', 'archiving', 'intermission'],
    zone: ['block', 'no-build', 'announce', 'off'],
    wake: ['checking', 'on', 'off', 'refused', 'unsupported'],
    feed: ['phase-active', 'phase-countdown', 'phase-archiving', 'phase-intermission']
  }
  for (const [family, members] of Object.entries(families)) members.forEach((m) => asked.add(`${family}.${m}`))
  const missing = [...asked].filter((k) => !keys.has(k))
  assert.deepEqual(missing, [], 'lines the page uses but messages.yml lacks')
})

test('icons.svg has every icon the page uses', async () => {
  const { ICONS, iconFor } = await module('items.mjs')
  const svg = readFileSync(join(DASHBOARD, 'icons.svg'), 'utf8')
  const ids = new Set([...svg.matchAll(/<symbol id="([a-z-]+)"/g)].map((m) => m[1]))
  for (const id of [...ICONS, 'head', 'map', 'clock', 'link', 'trophy']) assert.ok(ids.has(id), `icons.svg has ${id}`)
  for (const [, text] of code) {
    for (const m of text.matchAll(/\bicon\('([a-z-]+)'/g)) assert.ok(ids.has(m[1]), `icons.svg has ${m[1]}`)
  }
  assert.equal(iconFor('minecraft:diamond_sword'), 'sword')
  assert.equal(iconFor('minecraft:netherite_pickaxe'), 'pickaxe')
  assert.equal(iconFor('minecraft:diamond_helmet'), 'helmet')
  assert.equal(iconFor('minecraft:enchanted_book'), 'book')
  assert.equal(iconFor('minecraft:bread'), 'food')
  assert.equal(iconFor('minecraft:oak_planks'), 'block')
  assert.equal(iconFor('minecraft:music_disc_cat'), 'disc')
  assert.equal(iconFor('minecraft:heavy_core'), 'item')
  assert.ok(!/<style/.test(svg), 'icons are styled by attributes, which the policy allows')
})
