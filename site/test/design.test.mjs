// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// design/tokens.css: every text colour reads on every surface it is used on, to WCAG AA (4.5:1),
// in the dark theme and the parchment light theme.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'

const css = readFileSync(join(import.meta.dirname, '../../design/tokens.css'), 'utf8')

function block (selector) {
  const start = css.indexOf(selector + ' {')
  assert.ok(start >= 0, selector)
  return css.slice(start, css.indexOf('}', start))
}

function vars (text) {
  return Object.fromEntries([...text.matchAll(/(--[a-z0-9-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
}

const palette = vars(block(':root'))
const dark = palette
const light = { ...palette, ...vars(block(":root[data-theme='light']")) }

function resolve (theme, name) {
  let v = theme[name]
  for (let i = 0; v && v.startsWith('var(') && i < 5; i++) v = theme[v.slice(4, -1)]
  assert.match(v ?? '', /^#[0-9a-f]{6}$/i, `${name} is a colour`)
  return v
}

function luminance (hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((x) => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4))
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}

function contrast (a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p)
  return (x + 0.05) / (y + 0.05)
}

const TEXT = ['--text', '--text-2', '--text-3', '--accent-text', '--link', '--link-hover', '--success', '--info', '--warning', '--danger', '--travel']
const SURFACES = ['--bg', '--surface', '--surface-2']

for (const [name, theme] of [['dark', dark], ['light', light]]) {
  test(`${name}: every text colour reads on every surface`, () => {
    for (const fg of TEXT) {
      for (const bg of SURFACES) {
        const ratio = contrast(resolve(theme, fg), resolve(theme, bg))
        assert.ok(ratio >= 4.5, `${name} ${fg} on ${bg}: ${ratio.toFixed(2)}:1`)
      }
    }
  })

  test(`${name}: text on an accent button reads`, () => {
    for (const bg of ['--accent', '--accent-hover']) {
      const ratio = contrast(resolve(theme, '--on-accent'), resolve(theme, bg))
      assert.ok(ratio >= 4.5, `${name} --on-accent on ${bg}: ${ratio.toFixed(2)}:1`)
    }
  })
}

test('the media-query light theme matches the data-theme light theme', () => {
  const media = vars(block(':root:not([data-theme])'))
  for (const [k, v] of Object.entries(media)) assert.equal(v, light[k], k)
})
