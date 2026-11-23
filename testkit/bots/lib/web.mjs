// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Seasons Web in scenarios: web.yml written for the shard's own web port and reloaded through
// Seasons Web's scenario hooks (docs/adr/0003), an HTTP client that keeps its own cookies as a
// browser would, the device-code login, and a reader for the event stream.
import { SECOND, assert, sleep } from './context.mjs'
import { offlineUuid } from './hall.mjs'

export const SESSION_COOKIE = '__Host-seasons_session'
export const DEVICE_COOKIE = '__Host-seasons_device'

/** A user agent the session list names as "Safari on iPad". */
export const IPAD = 'Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'

/** A user agent the session list names as "Firefox on Windows". */
export const FIREFOX = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0'

/**
 * Writes plugins/SeasonsWeb/web.yml from `body` (YAML after the schema version) and reloads it.
 * Unless `body` names its own bind, the dashboard listens on the shard's web port. Resolves with
 * the reload's answer, such as "Web reloaded: listening on 127.0.0.1:25712".
 */
export async function webConfig (ctx, admin, body = '', { expect = /^Web reloaded: listening on / } = {}) {
  const bind = /^bind:/m.test(body) ? '' : `bind: 127.0.0.1:${ctx.webPort}\n`
  const enabled = /^enabled:/m.test(body) ? '' : 'enabled: true\n'
  ctx.writeFile('plugins/SeasonsWeb/web.yml', `schema-version: 1\n${enabled}${bind}${body}`)
  return ctx.command(admin, '/scenario web reload', expect)
}

/** An offline-mode player's UUID, as the API writes it. */
export function uuid (name) {
  const hex = offlineUuid(name).toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** The shard's dashboard address. */
export function webBase (ctx) {
  return `http://127.0.0.1:${ctx.webPort}`
}

/**
 * A browser on one device: it keeps the cookies the dashboard sets and sends them back, and can
 * send extra headers, such as the one a tunnel adds.
 */
export class WebClient {
  constructor (ctx, { userAgent = IPAD, headers = {} } = {}) {
    this.ctx = ctx
    this.base = webBase(ctx)
    this.userAgent = userAgent
    this.headers = headers
    /** name -> value */
    this.cookies = new Map()
    /** Every Set-Cookie line received, in order, as sent. */
    this.setCookies = []
    this.streams = []
    ctx.closers.push(() => this.close())
  }

  cookieHeader () {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ')
  }

  /** Resolves with { status, headers, text, json }. */
  async request (method, path, { headers = {}, body } = {}) {
    const sent = { 'User-Agent': this.userAgent, ...this.headers, ...headers }
    const cookie = this.cookieHeader()
    if (cookie) sent.Cookie = cookie
    const res = await fetch(this.base + path, { method, headers: sent, body, redirect: 'manual' })
    for (const line of res.headers.getSetCookie()) {
      this.setCookies.push(line)
      const [pair, ...attributes] = line.split(';').map((s) => s.trim())
      const at = pair.indexOf('=')
      const name = pair.slice(0, at)
      const value = pair.slice(at + 1)
      const maxAge = attributes.find((a) => /^max-age=/i.test(a))
      if (value === '' || (maxAge && Number(maxAge.split('=')[1]) <= 0)) this.cookies.delete(name)
      else this.cookies.set(name, value)
    }
    const text = await res.text()
    let json
    try {
      json = text ? JSON.parse(text) : undefined
    } catch {
      json = undefined
    }
    this.ctx.log(`[web] ${method} ${path} -> ${res.status} ${text.length > 300 ? text.slice(0, 300) + '...' : text}`)
    return { status: res.status, headers: res.headers, text, json }
  }

  get (path, options) {
    return this.request('GET', path, options)
  }

  post (path, options) {
    return this.request('POST', path, options)
  }

  /** The Set-Cookie line that set `name` last. */
  setCookie (name) {
    return [...this.setCookies].reverse().find((line) => line.startsWith(`${name}=`))
  }

  /**
   * Opens the event stream and resolves with { status, headers, events, comments, close }: events
   * as { event, data } with data parsed, and comments as their text, each with its arrival time.
   */
  async stream (path = '/api/v1/events', { headers = {} } = {}) {
    const controller = new AbortController()
    const sent = { 'User-Agent': this.userAgent, Accept: 'text/event-stream', ...this.headers, ...headers }
    const cookie = this.cookieHeader()
    if (cookie) sent.Cookie = cookie
    const res = await fetch(this.base + path, { headers: sent, signal: controller.signal })
    const events = []
    const comments = []
    const open = { status: res.status, headers: res.headers, events, comments, ended: false }
    open.close = () => controller.abort()
    this.streams.push(open)
    if (res.status !== 200) {
      open.text = await res.text()
      open.ended = true
      return open
    }
    const decoder = new TextDecoder()
    let buffer = ''
    const reader = res.body.getReader()
    ;(async () => {
      try {
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let cut
          while ((cut = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, cut)
            buffer = buffer.slice(cut + 2)
            const message = { event: 'message', data: '', at: Date.now() }
            let isEvent = false
            for (const line of block.split('\n')) {
              if (line.startsWith(':')) comments.push({ text: line.slice(1).trim(), at: Date.now() })
              else if (line.startsWith('event:')) { message.event = line.slice(6).trim(); isEvent = true }
              else if (line.startsWith('data:')) { message.data += line.slice(5).trim(); isEvent = true }
              else if (line.startsWith('id:')) message.id = line.slice(3).trim()
            }
            if (isEvent) {
              try { message.data = JSON.parse(message.data) } catch {}
              this.ctx.log(`[web stream] ${message.event} ${JSON.stringify(message.data)}`)
              events.push(message)
            }
          }
        }
      } catch {}
      open.ended = true
    })()
    return open
  }

  close () {
    for (const s of this.streams) s.close()
    this.streams = []
  }
}

/** Asks for a device code. Resolves with the response; its json holds the code. */
export function deviceCode (client) {
  return client.post('/api/v1/auth/device')
}

/**
 * Logs `client` in as the player `bot` through the device code: the dashboard asks for a code,
 * the player types /web link, and the dashboard's next poll gets the session. Resolves with the
 * poll's response.
 */
export async function login (ctx, bot, client) {
  const asked = await deviceCode(client)
  assert(asked.status === 200, `a device code: ${asked.status} ${asked.text}`)
  await ctx.command(bot, `/web link ${asked.json.code}`, /^Linked: /)
  const polled = await client.get('/api/v1/auth/poll')
  assert(polled.status === 200, `the poll after linking gets the session: ${polled.status} ${polled.text}`)
  assert(client.cookies.has(SESSION_COOKIE), 'the session cookie is set')
  return polled
}

/** Resolves once `check()` holds for the stream's events, or rejects after `timeoutMs`. */
export async function waitForEvent (stream, check, timeoutMs = 10 * SECOND, what = 'an event') {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const found = stream.events.find(check)
    if (found) return found
    await sleep(100)
  }
  throw new Error(`no ${what} on the event stream within ${timeoutMs} ms: ${JSON.stringify(stream.events.map((e) => e.event))}`)
}

/** The Cache-Control a response that depends on a login must carry. */
export function assertPrivate (res, what) {
  assert(res.headers.get('cache-control') === 'private, no-store', `${what} is private, no-store: ${res.headers.get('cache-control')}`)
}

/** Whether a response may be cached publicly, briefly. */
export function assertPublic (res, what) {
  assert(/^public, max-age=\d+$/.test(res.headers.get('cache-control') ?? ''), `${what} is public: ${res.headers.get('cache-control')}`)
}
