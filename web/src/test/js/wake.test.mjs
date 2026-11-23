// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { module } from './paths.mjs'

const { keepAwake } = await module('wake.mjs')

function fakeDocument () {
  const listeners = {}
  return {
    visibilityState: 'visible',
    addEventListener (type, fn) { (listeners[type] ??= []).push(fn) },
    removeEventListener (type, fn) { listeners[type] = (listeners[type] ?? []).filter((f) => f !== fn) },
    emit (type) { for (const fn of [...(listeners[type] ?? [])]) fn() },
    listeners
  }
}

function fakeLock ({ refuse = 0 } = {}) {
  const state = { requests: 0, sentinels: [] }
  return {
    state,
    wakeLock: {
      async request (type) {
        assert.equal(type, 'screen')
        state.requests++
        if (state.requests <= refuse) throw new Error('NotAllowedError')
        const releases = []
        const s = {
          released: false,
          addEventListener (n, fn) { if (n === 'release') releases.push(fn) },
          async release () { s.released = true; releases.forEach((fn) => fn()) }
        }
        state.sentinels.push(s)
        return s
      }
    }
  }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

test('asked for on load and again each time the page is visible after the browser let go', async () => {
  const document = fakeDocument()
  const navigator = fakeLock()
  const states = []
  const lock = keepAwake({ navigator, document, onState: (s) => states.push(s) })
  await settle()
  assert.equal(navigator.state.requests, 1)
  document.emit('visibilitychange')
  await settle()
  assert.equal(navigator.state.requests, 1, 'not again while held')
  await navigator.state.sentinels[0].release()
  document.visibilityState = 'hidden'
  document.emit('visibilitychange')
  await settle()
  assert.equal(navigator.state.requests, 1, 'not while hidden')
  document.visibilityState = 'visible'
  document.emit('visibilitychange')
  await settle()
  assert.equal(navigator.state.requests, 2)
  assert.deepEqual(states, ['on', 'off', 'on'])
  lock.release()
  assert.ok(navigator.state.sentinels[1].released)
})

test('a refusal asks again at the next tap', async () => {
  const document = fakeDocument()
  const navigator = fakeLock({ refuse: 1 })
  const states = []
  keepAwake({ navigator, document, onState: (s) => states.push(s) })
  await settle()
  assert.deepEqual(states, ['refused'])
  document.emit('pointerdown')
  await settle()
  assert.deepEqual(states, ['refused', 'on'])
})

test('without the API the page carries on', () => {
  const states = []
  const lock = keepAwake({ navigator: {}, document: fakeDocument(), onState: (s) => states.push(s) })
  assert.deepEqual(states, ['unsupported'])
  lock.release()
})
