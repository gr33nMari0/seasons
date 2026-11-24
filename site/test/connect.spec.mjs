// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The connect page in Chromium and WebKit (questions 336), with every server it may ask stubbed:
// the DNS-over-HTTPS resolvers, the status service and the dashboards. A game address finds its
// dashboard by its _seasons record, by its SRV target's record, or by seasons.<domain> when that
// lists it; a dashboard address typed is followed as before; each failure says why and goes nowhere.
import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { chromium, webkit } from 'playwright'
import { serve } from './serve.mjs'

const CORS = { 'Access-Control-Allow-Origin': '*' }
const instance = (name, extra = {}) => ({ service: 'seasons', name, version: '1.4.0', api: 'v1', apiVersion: '1.4.0', features: ['season'], login: ['device-code'], ...extra })
const json = (body) => ({ status: 200, headers: CORS, contentType: 'application/json', body: JSON.stringify(body) })
const ALLOWED = ['https://cloudflare-dns.com', 'https://dns.google', 'https://api.mcsrvstat.us']
let site

before(async () => { site = await serve() })
after(async () => { await site.close() })

/**
 * A browser whose requests are answered from `world`: dns maps "name TYPE" to record data,
 * status maps a game address to online or offline, and dashboards maps an origin to its
 * well-known reply ('hang' never answers).
 */
async function open (browserType, world) {
  const browser = await browserType.launch()
  const context = await browser.newContext()
  const requests = []
  context.on('request', (r) => requests.push(r.url()))
  await context.route(/^https:\/\//, async (route) => {
    const url = new URL(route.request().url())
    if (url.origin === 'https://cloudflare-dns.com' || url.origin === 'https://dns.google') {
      if (world.dnsDown) return route.abort('connectionrefused')
      const name = url.searchParams.get('name')
      const type = url.searchParams.get('type')
      const data = world.dns?.[`${name} ${type}`]
      const code = { TXT: 16, SRV: 33 }[type]
      return route.fulfill(json(data ? { Status: 0, Answer: [{ name, type: code, data }] } : { Status: 3 }))
    }
    if (url.origin === 'https://api.mcsrvstat.us') {
      const address = decodeURIComponent(url.pathname.replace('/3/', ''))
      const state = world.status?.[address]
      if (!state) return route.abort('connectionrefused')
      return route.fulfill(json({ online: state === 'online' }))
    }
    const answer = world.dashboards?.[url.origin]
    if (!answer) return route.abort('connectionrefused')
    if (answer === 'hang') return
    if (url.pathname === '/.well-known/seasons.json') return route.fulfill(json(answer))
    return route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>Dashboard</title><p id="dashboard">The dashboard</p>' })
  })
  const page = await context.newPage()
  await page.goto(`${site.origin}/connect/`)
  return { browser, page, requests }
}

async function find (page, address) {
  await page.fill('input[name="server"]', address)
  await page.click('form[data-connect] button[type="submit"]')
}

async function refused (page, address, reason, timeout = 10000) {
  await find(page, address)
  await page.waitForSelector(`[data-status][data-error="${reason}"]`, { timeout })
  assert.equal(new URL(page.url()).pathname, '/connect/', `${address} went nowhere`)
  assert.ok((await page.locator('[data-status]').innerText()).length > 10, 'a reason in words')
}

for (const [engine, browserType] of [['chromium', chromium], ['webkit', webkit]]) {
  test(`${engine}: a game address finds its dashboard by its _seasons record`, async () => {
    const { browser, page, requests } = await open(browserType, {
      dns: { '_seasons.play.example.test TXT': '"v=seasons1; url=https://dash.example.test"' },
      dashboards: { 'https://dash.example.test': instance('Example SMP', { gameAddresses: ['play.example.test'], dashboard: 'https://dash.example.test' }) }
    })
    try {
      await find(page, 'play.example.test')
      await page.waitForURL('https://dash.example.test/')
      await page.locator('#dashboard').waitFor()
      const others = requests.filter((u) => !u.startsWith(site.origin) && !u.startsWith('https://dash.example.test/') && !ALLOWED.some((o) => u.startsWith(o + '/')))
      assert.deepEqual(others, [], 'only the resolvers and the dashboard were asked')
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: an SRV record's target, a dashboard typed directly, and seasons.<domain>`, async () => {
    const { browser, page } = await open(browserType, {
      dns: {
        '_minecraft._tcp.example.test SRV': '0 5 25565 mc.example.test.',
        '_seasons.mc.example.test TXT': '"v=seasons1; url=https://dash.example.test"'
      },
      dashboards: {
        'https://dash.example.test': instance('Example SMP', { gameAddresses: ['mc.example.test'] }),
        'https://seasons.direct.test': instance('Direct'),
        'https://seasons.conv.test': instance('Convention', { gameAddresses: ['play.conv.test'] })
      }
    })
    try {
      await find(page, 'example.test')
      await page.waitForURL('https://dash.example.test/')
      await page.goto(`${site.origin}/connect/`)
      await find(page, 'seasons.direct.test')
      await page.waitForURL('https://seasons.direct.test/')
      await page.goto(`${site.origin}/connect/`)
      await find(page, 'play.conv.test')
      await page.waitForURL('https://seasons.conv.test/')
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: a dashboard that doesn't list the address is asked about, never followed blindly`, async () => {
    const { browser, page } = await open(browserType, {
      dns: { '_seasons.play.other.test TXT': '"v=seasons1; url=https://dash.other.test"' },
      dashboards: {
        'https://dash.other.test': instance('Other', { gameAddresses: ['elsewhere.test'] }),
        'https://seasons.old.test': instance('Old SMP'),
        'https://seasons.stranger.test': instance('Stranger', { gameAddresses: ['someone-else.test'] })
      },
      status: { 'play.stranger.test': 'online' }
    })
    try {
      await refused(page, 'play.other.test', 'unconfirmed')
      await page.waitForSelector('[data-actions] button')
      // A dashboard from before 1.4 at seasons.<domain> lists no addresses: "Is this your server?"
      await find(page, 'play.old.test')
      await page.waitForSelector('[data-status][data-error="ask"]')
      assert.equal(new URL(page.url()).pathname, '/connect/')
      await page.click('[data-actions] button:first-child')
      await page.waitForURL('https://seasons.old.test/')
      await page.goto(`${site.origin}/connect/`)
      // One at seasons.<domain> that lists someone else's address is ignored.
      await refused(page, 'play.stranger.test', 'no-seasons')
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: each failure says why and goes nowhere`, async () => {
    const { browser, page } = await open(browserType, {
      dns: { '_seasons.down.test TXT': '"v=seasons1; url=https://dash.down.test"' },
      dashboards: { 'https://evil.test': instance('Evil', { dashboard: 'https://phish.test' }) },
      status: { 'online.test': 'online', 'gone.test': 'offline' }
    })
    try {
      await refused(page, 'http://seasons.test', 'not-https')
      await refused(page, 'not an address', 'bad-address')
      await refused(page, 'down.test', 'found-down')
      await refused(page, 'online.test', 'no-seasons')
      await refused(page, 'gone.test', 'offline')
      await refused(page, 'unknown.test', 'not-found')
      // A seasons.json naming another site's dashboard leads to the address typed, never the other.
      await find(page, 'evil.test')
      await page.waitForURL('https://evil.test/')
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: ?server= finds at once, and a found server is remembered on this device`, async () => {
    const { browser, page } = await open(browserType, {
      dns: { '_seasons.play.example.test TXT': '"v=seasons1; url=https://dash.example.test"' },
      dashboards: { 'https://dash.example.test': instance('Example SMP', { gameAddresses: ['play.example.test'] }) }
    })
    try {
      await page.goto(`${site.origin}/connect/?server=play.example.test`)
      await page.waitForURL('https://dash.example.test/')
      await page.goto(`${site.origin}/connect/`)
      await page.waitForSelector('[data-recent]:not([hidden]) [data-recent="play.example.test"]')
      assert.match(await page.locator('[data-recent-list]').innerText(), /Example SMP/)
      await page.click('[data-recent="play.example.test"]')
      await page.waitForURL('https://dash.example.test/')
      await page.goto(`${site.origin}/connect/`)
      await page.click('.recent-forget')
      await page.waitForSelector('[data-recent][hidden]', { state: 'attached' })
    } finally {
      await browser.close()
    }
  })

  test(`${engine}: with DNS-over-HTTPS blocked, a dashboard address typed still works`, async () => {
    const { browser, page } = await open(browserType, {
      dnsDown: true,
      dashboards: { 'https://seasons.test': instance('Example SMP') }
    })
    try {
      await find(page, 'seasons.test')
      await page.waitForURL('https://seasons.test/')
    } finally {
      await browser.close()
    }
  })
}
