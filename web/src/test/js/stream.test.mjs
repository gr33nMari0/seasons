// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { module } from './paths.mjs'

const { backoff, keepOpen } = await module('stream.mjs')

class FakeSource {
  static all = []
  constructor (url) {
    this.url = url
    this.listeners = {}
    this.closed = false
    FakeSource.all.push(this)
  }

  addEventListener (type, fn) {
    (this.listeners[type] ??= []).push(fn)
  }

  emit (type, e = {}) {
    for (const fn of this.listeners[type] ?? []) fn(e)
  }

  close () {
    this.closed = true
  }
}

function fakeTimers () {
  const waits = []
  return {
    waits,
    setTimeout (fn, ms) { waits.push({ fn, ms }); return waits.length },
    clearTimeout () {},
    runNext () { waits.shift().fn() }
  }
}

test('1 s, doubling, at most 30 s between tries', () => {
  assert.deepEqual([0, 1, 2, 3, 4, 5, 6].map(backoff), [1000, 2000, 4000, 8000, 16000, 30000, 30000])
})

test('events reach the page; a dropped stream is opened again and the page told it dropped', () => {
  FakeSource.all = []
  const timers = fakeTimers()
  const events = []
  const opens = []
  const s = keepOpen({ url: '/api/v1/events', EventSourceImpl: FakeSource, timers, onEvent: (t, d) => events.push([t, d]), onOpen: (d) => opens.push(d) })
  const first = FakeSource.all[0]
  first.emit('open')
  first.emit('join', { data: '{"player":{"name":"Sam"}}' })
  first.emit('join', { data: 'not json' })
  assert.deepEqual(events, [['join', { player: { name: 'Sam' } }]])
  first.emit('error')
  assert.ok(first.closed)
  assert.equal(timers.waits[0].ms, 1000)
  timers.runNext()
  const second = FakeSource.all[1]
  second.emit('error')
  assert.equal(timers.waits[0].ms, 2000, 'the wait doubles')
  timers.runNext()
  FakeSource.all[2].emit('open')
  assert.deepEqual(opens, [false, true])
  FakeSource.all[2].emit('error')
  assert.equal(timers.waits[0].ms, 1000, 'an open resets the wait')
  s.close()
  timers.runNext()
  assert.equal(FakeSource.all.length, 3, 'nothing opens after close')
})
