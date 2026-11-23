// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Real browsers on the dashboard (docs/adr/0007): Chromium, or WebKit for the iPad's engine,
// opened on the tunnel's https address. Every request's origin and every CSP violation is recorded,
// and check() fails on any request to an origin the page may not use or any violation. Spotify's
// embed and Mojang's skin server are answered locally, so nothing leaves the machine.
import { chromium, webkit } from 'playwright'
import { SECOND, assert, sleep } from './context.mjs'
import { startTunnel } from './tunnel.mjs'
import { webConfig } from './web.mjs'

/** An iPad Pro 12.9" in landscape. */
export const IPAD_LANDSCAPE = { width: 1366, height: 1024 }

/** A phone in portrait. */
export const PHONE = { width: 390, height: 844 }

/** A 64 × 64 PNG standing in for a skin: not a Minecraft texture, just grey squares. */
const SKIN = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAvUlEQVR4nO3QsQkDQRDAwC/7Sryu7A4+W2aNFSgUCD333s8b55xXft1/dID2G6ADtN8AHaD9BugA7T/bA6f9BugA7TdAB2i/ATpA+w3YHjjtN0AHaL8BOkD7DdAB2m/A9sBpvwE6QPsN0AHab4AO0H4DtgdO+w3QAdpvgA7QfgN0gPYbsD1w2m+ADtB+A3SA9hugA7TfgO2B034DdID2G6ADtN8AHaD9BmwPnPYboAO03wAdoP0G6ADt//2AL9Tl8qS0XZ7QAAAAAElFTkSuQmCC',
  'base64')

/**
 * Writes web.yml for a dashboard behind the tunnel (public-url its https address, the tunnel's
 * address trusted, generous rate limits unless `body` sets its own) and starts the tunnel.
 * Resolves with { origin, ip }.
 */
export async function dashboard (ctx, admin, body = '', { expect } = {}) {
  const tunnel = await startTunnel(ctx)
  let text = body
  if (!/^public-url:/m.test(text)) text = `public-url: ${tunnel.origin}\n${text}`
  if (!/^proxy:/m.test(text)) text += 'proxy:\n  trusted: [127.0.0.1]\n  client-ip-header: CF-Connecting-IP\n'
  if (!/^rate-limits:/m.test(text)) text += 'rate-limits:\n  requests-per-minute-per-ip: 2000\n  requests-per-minute-per-account: 2000\n'
  await webConfig(ctx, admin, text, expect ? { expect } : undefined)
  return tunnel
}

/**
 * Opens a browser on `origin + path` and resolves with a Page (below) once the page has loaded.
 *
 * @param engine 'chromium' or 'webkit'
 * @param wakeLock 'stub' puts a recording stand-in for navigator.wakeLock before the page's own
 *   script; 'none' takes the API away, as on an iPad before iPadOS 16.4; unset leaves the browser's
 * @param frames origins the page may frame or load from, besides its own and the stubbed ones
 * @param offsetMs how far this browser's clock is wrong, ahead if positive
 */
export async function openPage (ctx, origin, path = '/', { engine = 'chromium', viewport, wakeLock, frames = [], offsetMs = 0, colorScheme = 'light' } = {}) {
  const browserType = engine === 'webkit' ? webkit : chromium
  const browser = await browserType.launch()
  const context = await browser.newContext({ ignoreHTTPSErrors: true, viewport: viewport ?? { width: 1280, height: 900 }, colorScheme })
  const page = new Page(ctx, browser, context, origin, frames)
  ctx.closers.push(() => page.close())
  await context.exposeBinding('__seasonsViolation', (_source, v) => {
    ctx.log(`[browser] CSP violation: ${JSON.stringify(v)}`)
    page.violations.push(v)
  })
  await context.addInitScript(() => {
    window.addEventListener('securitypolicyviolation', (e) => {
      window.__seasonsViolation({ directive: e.violatedDirective, blocked: e.blockedURI, source: e.sourceFile, line: e.lineNumber })
    })
  })
  if (offsetMs) {
    await context.addInitScript((offset) => {
      const RealDate = Date
      const shifted = class extends RealDate {
        constructor (...args) { super(...(args.length ? args : [RealDate.now() + offset])) }
        static now () { return RealDate.now() + offset }
      }
      window.Date = shifted
    }, offsetMs)
  }
  if (wakeLock === 'stub') {
    await context.addInitScript(() => {
      const state = { requests: 0, releases: 0, held: false }
      window.__wake = state
      const lock = {
        request: async (type) => {
          if (type !== 'screen') throw new TypeError('screen only')
          state.requests++
          state.held = true
          const listeners = []
          const sentinel = {
            type: 'screen',
            released: false,
            addEventListener: (name, fn) => { if (name === 'release') listeners.push(fn) },
            removeEventListener: () => {},
            release: async () => {
              if (sentinel.released) return
              sentinel.released = true
              state.held = false
              state.releases++
              listeners.forEach((fn) => fn())
            }
          }
          state.sentinel = sentinel
          return sentinel
        }
      }
      Object.defineProperty(Navigator.prototype, 'wakeLock', { configurable: true, get: () => lock })
    })
  } else if (wakeLock === 'none') {
    await context.addInitScript(() => {
      try { delete Navigator.prototype.wakeLock } catch {}
      Object.defineProperty(Navigator.prototype, 'wakeLock', { configurable: true, get: () => undefined })
    })
  }
  await context.route('https://open.spotify.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: '<!doctype html><title>Spotify</title><p id="spotify">Playlist</p>' }))
  await context.route('https://textures.minecraft.net/**', (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: SKIN }))
  context.on('request', (request) => {
    const url = request.url()
    page.requests.push(url)
  })
  context.on('response', async (response) => {
    const entry = { url: response.url(), status: response.status(), headers: response.headers(), session: false }
    page.responses.push(entry)
    try {
      const sent = await response.request().allHeaders()
      entry.session = /__Host-seasons_session=/.test(sent.cookie ?? '')
    } catch {}
  })
  page.page = await context.newPage()
  page.page.on('console', (message) => {
    const text = message.text()
    ctx.log(`[browser ${message.type()}] ${text}`)
    if (message.type() === 'error' && /content security policy|refused to (load|execute|apply|frame|connect)/i.test(text)) {
      page.violations.push({ console: text })
    }
  })
  page.page.on('pageerror', (err) => {
    ctx.log(`[browser] page error: ${err.stack ?? err}`)
    page.errors.push(String(err.message ?? err))
  })
  await page.goto(path)
  return page
}

/** One browser tab on the dashboard, with what it asked for and what it was refused. */
export class Page {
  constructor (ctx, browser, context, origin, frames) {
    Object.assign(this, { ctx, browser, context, origin, frames })
    this.requests = []
    this.responses = []
    this.violations = []
    this.errors = []
    this.closed = false
  }

  /** Goes to a path of the dashboard and waits for the page to say it has drawn. */
  async goto (path) {
    await this.page.goto(this.origin + path)
    await this.page.waitForSelector('body[data-ready="true"]', { timeout: 15 * SECOND })
  }

  /** The visible text of a widget, or null if it isn't on the page. */
  async widget (name) {
    const at = this.page.locator(`[data-widget="${name}"]`)
    if (await at.count() === 0) return null
    return (await at.first().innerText()).replace(/\s+/g, ' ').trim()
  }

  /** Resolves with a widget's text once it matches `pattern`. */
  async waitForWidget (name, pattern, timeoutMs = 15 * SECOND, what = `the ${name} widget to match ${pattern}`) {
    const deadline = Date.now() + timeoutMs
    let last
    while (Date.now() < deadline) {
      last = await this.widget(name)
      if (last !== null && pattern.test(last)) return last
      await sleep(250)
    }
    throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}; it reads: ${last}`)
  }

  /** Resolves once a widget is gone from the page, or was never there. */
  async waitForNoWidget (name, timeoutMs = 15 * SECOND) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (await this.page.locator(`[data-widget="${name}"]`).count() === 0) return
      await sleep(250)
    }
    throw new Error(`the ${name} widget is still on the page after ${timeoutMs} ms`)
  }

  /** The names of every widget on the page, in order. */
  widgets () {
    return this.page.locator('[data-widget]').evaluateAll((all) => all.map((e) => e.dataset.widget))
  }

  /** The text of the whole page. */
  async text () {
    return (await this.page.locator('body').innerText()).replace(/\s+/g, ' ').trim()
  }

  /** The paths of this origin's API requested since the page opened, without the query. */
  apiPaths () {
    return this.requests
      .filter((u) => u.startsWith(this.origin + '/api/'))
      .map((u) => new URL(u).pathname)
  }

  /**
   * Fails on any CSP violation, uncaught page error, or request to an origin other than the
   * dashboard's own, Mojang's skin server, Spotify and the frames allowed.
   */
  check (what = 'the page') {
    const allowed = new Set([this.origin, 'https://textures.minecraft.net', 'https://open.spotify.com', ...this.frames])
    const strangers = this.requests
      .filter((u) => !u.startsWith('data:') && !u.startsWith('about:'))
      .filter((u) => !allowed.has(new URL(u).origin))
    assert(strangers.length === 0, `${what} asked other sites: ${JSON.stringify([...new Set(strangers)])}`)
    assert(this.violations.length === 0, `${what} broke its content security policy: ${JSON.stringify(this.violations)}`)
    assert(this.errors.length === 0, `${what} threw: ${JSON.stringify(this.errors)}`)
  }

  async close () {
    if (this.closed) return
    this.closed = true
    await this.browser.close().catch(() => {})
  }
}

/**
 * Logs a dashboard tab in as the player `bot`, as a person would: presses the login button, reads
 * the code the page shows, types /web link in Minecraft, and waits for the page to say who is
 * logged in.
 */
export async function loginOnPage (ctx, page, bot) {
  await page.page.locator('[data-action="login"]').first().click()
  const code = (await page.page.locator('[data-login-code]').first().innerText({ timeout: 10 * SECOND })).trim()
  assert(/^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(code), `the page shows a code: ${code}`)
  await ctx.command(bot, `/web link ${code}`, /^Linked: /)
  await page.page.waitForSelector(`[data-viewer="${bot.username}"]`, { timeout: 15 * SECOND })
  return code
}
