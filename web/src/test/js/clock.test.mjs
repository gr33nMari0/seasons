// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { module } from './paths.mjs'

const { serverClock } = await module('clock.mjs')

test("countdowns run by the server's clock, whatever the device's says", () => {
  let local = Date.parse('2026-11-23T11:00:00Z') // an hour fast
  const clock = serverClock(() => local)
  clock.sync('2026-11-23T10:00:00Z')
  assert.equal(clock.now(), Date.parse('2026-11-23T10:00:00Z'))
  assert.equal(clock.until('2026-11-23T10:10:00Z'), 10 * 60 * 1000)
  local += 1000
  assert.equal(clock.until('2026-11-23T10:10:00Z'), 10 * 60 * 1000 - 1000)
  clock.sync('not a time')
  assert.equal(clock.now(), Date.parse('2026-11-23T10:00:01Z'), 'a bad time changes nothing')
})
