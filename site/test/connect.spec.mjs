// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The connect page in Chromium and WebKit, with the servers it asks stubbed: a Seasons instance
// leads to its dashboard; not https, junk, not Seasons, no answer and a timeout each say why and go
// nowhere; ?server= connects at once. (A CORS refusal fails the fetch as no answer does; Playwright
// answers stubbed requests as CORS allows, so it isn't a case of its own here.)
import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { chromium, webkit } from 'playwright'
import { serve } from './serve.mjs'

const WELL_KNOWN = { service: 'seasons', name: 'The Ranch', version: '1.2.0', api: 'v1', apiVersion: '1.3.0', dashboard: 'https://seasons.test', features: ['season'], login: ['device-code'] }
const CORS = { 'Access-Control-Allow-Origin': '*' }
let site

before(async () => { site = await serve() })
after(async () => { await site.close() })

/** A browser whose requests to the stubbed servers are answered by `answers`, by origin. */
async function open (browserType, answers) {
  const browser = await browserType.launch()
  const context = await browser.newContext()
  const requests = []
  context.on('request', (r) => requests.push(r.url()))
  await context.route(/^https:\/\//, async (route) => {
    const url = new URL(route.request().url())
    const answer = answers[url.origin]
    if (!answer) return route.abort('connectionrefused')
    if (answer === 'hang') return
    if (url.pathname === '/.well-known/seasons.json') return route.fulfill(answer)
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>Dashboard</title><p id="dashboard">The dashboard</p>' })
  })
  const page = await context.newPage()
  return { browser, page, requests }
}

for (const [engine, browserType] of [['chromium', chromium], ['webkit', webkit]]) {
  test(`${engine}: a Seasons instance leads to its own dashboard`, async () => {
    const { browser, page, requests } = await open(browserType, {
      'https://seasons.test': { status: 200, headers: CORS, contentType: 'application/json', body: JSON.stringify(WELL_KNOWN) }
    })
    try {
      await page.goto(`${site.origin}/connect/`)
      await page.fill('input[name="server"]', 'seasons.test')
      await page.click('button[type="submit"]')
      await page.waitForURL('https://seasons.test/')
      await page.locator('#dashboard').waitFor()
      assert.ok(requests.every((u) => u.startsWith(site.origin) || u.startsWith('https://seasons.test/')), JSON.stringify(requests))
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: each refusal says why and goes nowhere`, async () => {
    const { browser, page } = await open(browserType, {
      'https://other.test': { status: 200, headers: CORS, contentType: 'application/json', body: '{"service":"something-else"}' },
      'https://evil.test': { status: 200, headers: CORS, contentType: 'application/json', body: JSON.stringify({ ...WELL_KNOWN, dashboard: 'https://phish.test' }) },
      'https://slow.test': 'hang'
    })
    try {
      await page.goto(`${site.origin}/connect/`)
      const tryAddress = async (address, reason, timeout = 5000) => {
        await page.fill('input[name="server"]', address)
        await page.click('button[type="submit"]')
        await page.waitForSelector(`[data-status][data-error="${reason}"]`, { timeout })
        assert.equal(page.url(), `${site.origin}/connect/`, `${address} went nowhere`)
        assert.ok((await page.locator('[data-status]').innerText()).length > 10, 'a reason in words')
      }
      await tryAddress('http://seasons.test', 'not-https')
      await tryAddress('not an address', 'bad-address')
      await tryAddress('other.test', 'not-seasons')
      await tryAddress('nothing.test', 'unreachable')
      await tryAddress('slow.test', 'timeout', 15000)
      // A seasons.json naming another site's dashboard leads to the address typed.
      await page.fill('input[name="server"]', 'evil.test')
      await page.click('button[type="submit"]')
      await page.waitForURL('https://evil.test/')
    } finally {
      await browser.close()
    }
  })
}

test('?server= connects at once', async () => {
  const { browser, page } = await open(chromium, {
    'https://seasons.test': { status: 200, headers: CORS, contentType: 'application/json', body: JSON.stringify(WELL_KNOWN) }
  })
  try {
    await page.goto(`${site.origin}/connect/?server=seasons.test`)
    await page.waitForURL('https://seasons.test/')
  } finally {
    await browser.close()
  }
})
