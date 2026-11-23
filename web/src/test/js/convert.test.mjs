// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { module } from './paths.mjs'

const c = await module('convert.mjs')

test('the Nether is an eighth, rounded down as the game does', () => {
  assert.equal(c.toNether(50), 6)
  assert.equal(c.toNether(-100), -13)
  assert.equal(c.toNether(-9), -2)
  assert.equal(c.toNether(-8), -1)
  assert.equal(c.toNether(0), 0)
  assert.equal(c.toNether(7), 0)
})

test('the Overworld is eight times', () => {
  assert.equal(c.toOverworld(10), 80)
  assert.equal(c.toOverworld(-3), -24)
})

test('only whole numbers are read', () => {
  assert.equal(c.parseCoordinate(' -100 '), -100)
  assert.equal(c.parseCoordinate('+12'), 12)
  assert.equal(c.parseCoordinate('1.5'), null)
  assert.equal(c.parseCoordinate(''), null)
  assert.equal(c.parseCoordinate('1e5'), null)
})
