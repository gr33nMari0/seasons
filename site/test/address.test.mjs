// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { explain, parseAddress } from '../public/connect/check.mjs'

const ok = (text, origin) => assert.deepEqual(parseAddress(text), { ok: true, origin }, text)
const no = (text, reason) => assert.deepEqual(parseAddress(text), { ok: false, reason }, text)

test('hosts, host:port and https addresses, with any path dropped', () => {
  ok('seasons.petepeterepeat.com', 'https://seasons.petepeterepeat.com')
  ok('  Seasons.Example.COM  ', 'https://seasons.example.com')
  ok('https://seasons.example.com/', 'https://seasons.example.com')
  ok('https://seasons.example.com/player/abc?x=1#y', 'https://seasons.example.com')
  ok('seasons.example.com:8443', 'https://seasons.example.com:8443')
  ok('seasons.example.com:443', 'https://seasons.example.com')
  ok('localhost:25713', 'https://localhost:25713')
  ok('192.168.1.5:8019', 'https://192.168.1.5:8019')
  ok('seasons.example.com.', 'https://seasons.example.com')
})

test('anything but https, user info, junk and bad ports are refused with a reason', () => {
  no('', 'empty')
  no('   ', 'empty')
  no('http://seasons.example.com', 'not-https')
  no('ftp://seasons.example.com', 'not-https')
  no('javascript:alert(1)', 'not-https')
  no('data:text/html,hi', 'not-https')
  no('https://user:pass@seasons.example.com', 'user-info')
  no('seasons', 'bad-address')
  no('not an address', 'bad-address')
  no('-bad-.example.com', 'bad-address')
  no('seasons.example.123', 'bad-address')
  no('seasons.example.com:0', 'bad-port')
  no('seasons.example.com:99999', 'bad-port')
  no('seasons.example.com:', 'bad-port')
})

test('reasons in words, with the address', () => {
  assert.match(explain('not-https'), /https/)
  assert.match(explain('not-seasons', 'https://x.example'), /^https:\/\/x\.example answered/)
})
