// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkInstance } from '../public/connect/check.mjs'

const ORIGIN = 'https://seasons.example.com'
const WELL_KNOWN = { service: 'seasons', name: 'Example SMP', version: '1.2.0', api: 'v1', apiVersion: '1.3.0', dashboard: ORIGIN, features: ['season', 'online'], login: ['device-code'] }

test('a Seasons instance: its name, version, features, game addresses and dashboard', () => {
  assert.deepEqual(checkInstance(WELL_KNOWN, ORIGIN), { ok: true, name: 'Example SMP', version: '1.2.0', features: ['season', 'online'], gameAddresses: null, dashboard: `${ORIGIN}/` })
  assert.deepEqual(checkInstance({ ...WELL_KNOWN, gameAddresses: ['Play.Example.com', 3] }, ORIGIN).gameAddresses, ['play.example.com'])
})

test('anything else is not a Seasons instance', () => {
  for (const json of [null, 'seasons', [], {}, { ...WELL_KNOWN, service: 'other' }, { ...WELL_KNOWN, api: 'v2' }, { ...WELL_KNOWN, name: 3 }]) {
    assert.deepEqual(checkInstance(json, ORIGIN), { ok: false, reason: 'not-seasons' }, JSON.stringify(json))
  }
})

test("a dashboard named on another site, or not over https, isn't followed: the address typed is", () => {
  for (const dashboard of ['https://evil.example', 'http://seasons.example.com', 'javascript:alert(1)', 'not a url', null]) {
    assert.equal(checkInstance({ ...WELL_KNOWN, dashboard }, ORIGIN).dashboard, `${ORIGIN}/`, String(dashboard))
  }
})

test('an empty name falls back to the address', () => {
  assert.equal(checkInstance({ ...WELL_KNOWN, name: '  ' }, ORIGIN).name, 'seasons.example.com')
})
