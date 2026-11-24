// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Finding a dashboard from a game address (questions 336): the pure parts.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { confirms, conventions, dashboardFromRecords, isIp, parseServer, registrable, serverStatus, srvTarget, txtRecords } from '../public/connect/check.mjs'

test('a game address: its host, the address a dashboard lists, and where to ask directly', () => {
  assert.deepEqual(parseServer('Play.Example.com'), { ok: true, host: 'play.example.com', port: '', typed: 'play.example.com', direct: 'https://play.example.com', explicit: false })
  const withPort = parseServer('play.example.com:25566')
  assert.equal(withPort.typed, 'play.example.com:25566')
  assert.equal(withPort.direct, 'https://play.example.com', 'a game port is not a web port')
  assert.equal(parseServer('play.example.com:25565').typed, 'play.example.com')
  assert.equal(parseServer('https://seasons.example.com:8443').direct, 'https://seasons.example.com:8443')
  assert.deepEqual(parseServer('http://play.example.com'), { ok: false, reason: 'not-https' })
  assert.ok(isIp('203.0.113.7'))
  assert.ok(!isIp('play.example.com'))
})

test('conventions: seasons.<domain>, never the address itself', () => {
  assert.equal(registrable('play.example.com'), 'example.com')
  assert.equal(registrable('mc.server.co.uk'), 'server.co.uk')
  assert.equal(registrable('example.com'), 'example.com')
  assert.deepEqual(conventions('play.example.com', 'https://play.example.com'), ['https://seasons.example.com', 'https://example.com'])
  assert.deepEqual(conventions('example.com', 'https://example.com'), ['https://seasons.example.com'])
})

test('DNS answers: TXT pieces joined, SRV targets, and only Seasons https records', () => {
  assert.deepEqual(txtRecords({ Answer: [{ type: 16, data: '"v=seasons1; url=" "https://dash.example.com"' }, { type: 5, data: 'x' }] }), ['v=seasons1; url=https://dash.example.com'])
  assert.deepEqual(txtRecords({ Status: 3 }), [])
  assert.equal(srvTarget({ Answer: [{ type: 33, data: '0 5 25565 MC.example.com.' }] }), 'mc.example.com')
  assert.equal(srvTarget(null), null)
  assert.equal(dashboardFromRecords(['v=spf1 -all', 'v=seasons1; url=https://dash.example.com/path']), 'https://dash.example.com')
  assert.equal(dashboardFromRecords(['v=seasons1; url=http://dash.example.com']), null)
  assert.equal(dashboardFromRecords(['v=seasons1; url=https://user:pw@dash.example.com']), null)
  assert.equal(dashboardFromRecords(['url=https://dash.example.com']), null)
})

test('a dashboard confirms the address only by listing it', () => {
  assert.equal(confirms({ gameAddresses: ['play.example.com'] }, ['play.example.com']), 'yes')
  assert.equal(confirms({ gameAddresses: ['other.example.com'] }, ['play.example.com']), 'no')
  assert.equal(confirms({ gameAddresses: null }, ['play.example.com']), 'unknown')
  assert.equal(serverStatus({ online: true }), 'online')
  assert.equal(serverStatus({ online: false }), 'offline')
  assert.equal(serverStatus({}), 'unknown')
})
