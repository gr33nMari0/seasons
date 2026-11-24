// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The connect page (spec, Connect flow; questions 336): type the address you play on and land on
// that server's own Seasons dashboard, or be told why not. In order:
//   1. DNS, over DNS-over-HTTPS (Cloudflare, then Google): the _seasons TXT record of the address and
//      of its Minecraft SRV target names the dashboard.
//   2. The address itself, in case it is the dashboard's own (seasons.example.com).
//   3. The conventional seasons.<domain>, accepted only if it lists the address in gameAddresses.
//   4. Nothing found: the public status service says whether the game server is online at all.
// A dashboard found by record or convention must list the address typed in its gameAddresses before
// the page goes there; one from before 1.4, which lists none, is trusted from a record and asked
// about ("Is this your server?") from a convention. This page sends only the address typed, and
// only to the places its privacy note names. "Recent on this device" stays in this browser.
import {
  checkInstance, confirms, conventions, dashboardFromRecords, explain, isIp, parseServer,
  serverStatus, srvTarget, txtRecords
} from './check.mjs'

const TIMEOUT_MS = 6000
const RESOLVERS = ['https://cloudflare-dns.com/dns-query', 'https://dns.google/resolve']
const STATUS = 'https://api.mcsrvstat.us/3/'
const RECENT_KEY = 'seasons-connect-recent'

const form = document.querySelector('form[data-connect]')
const input = form.querySelector('input[name="server"]')
const remember = form.querySelector('input[name="remember"]')
const button = form.querySelector('button[type="submit"]')
const status = document.querySelector('[data-status]')
const progress = document.querySelector('[data-progress]')
const actions = document.querySelector('[data-actions]')
const recent = document.querySelector('[data-recent]')
const recentList = document.querySelector('[data-recent-list]')

function say (text, kind, error) {
  status.textContent = text
  status.dataset.kind = kind
  if (error) status.dataset.error = error
  else delete status.dataset.error
}

function step (n) {
  progress.hidden = n === 0
  if (n) progress.dataset.step = String(n)
  else delete progress.dataset.step
}

function offer (buttons) {
  actions.replaceChildren(...buttons)
  actions.hidden = buttons.length === 0
}

function actionButton (label, onClick, secondary = false) {
  const b = document.createElement('button')
  b.type = 'button'
  b.className = secondary ? 'button secondary' : 'button'
  b.textContent = label
  b.addEventListener('click', onClick)
  return b
}

async function getJson (url, accept = 'application/json') {
  const timer = new AbortController()
  const stop = setTimeout(() => timer.abort(), TIMEOUT_MS)
  try {
    const res = await fetch(url, {
      credentials: 'omit',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      signal: timer.signal,
      headers: { Accept: accept }
    })
    if (!res.ok) return { ok: false, status: res.status }
    return { ok: true, json: await res.json().catch(() => null) }
  } catch (err) {
    return { ok: false, timeout: err?.name === 'AbortError' }
  } finally {
    clearTimeout(stop)
  }
}

/** A DNS-over-HTTPS JSON answer for a name, from the first resolver that answers; null if none does. */
async function dns (name, type) {
  for (const resolver of RESOLVERS) {
    const r = await getJson(`${resolver}?name=${encodeURIComponent(name)}&type=${type}`, 'application/dns-json')
    if (r.ok && r.json) return r.json
  }
  return null
}

/** What an origin's /.well-known/seasons.json says: { reached, instance } */
async function ask (origin) {
  const r = await getJson(`${origin}/.well-known/seasons.json`)
  if (!r.ok) return { reached: false, timeout: r.timeout }
  const instance = checkInstance(r.json, origin)
  return { reached: true, instance: instance.ok ? instance : null }
}

function readRecent () {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]')
    return Array.isArray(list) ? list.filter((r) => r && typeof r.address === 'string' && typeof r.dashboard === 'string') : []
  } catch { return [] }
}

function writeRecent (list) {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(list.slice(0, 5))) } catch {}
  showRecent()
}

function rememberServer (address, instance) {
  if (remember && !remember.checked) return
  const entry = { address, name: instance.name, dashboard: instance.dashboard }
  writeRecent([entry, ...readRecent().filter((r) => r.address !== address)])
}

function showRecent () {
  const list = readRecent()
  recent.hidden = list.length === 0
  recentList.replaceChildren(...list.map((r) => {
    const li = document.createElement('li')
    const go = document.createElement('button')
    go.type = 'button'
    go.className = 'recent-go'
    go.dataset.recent = r.address
    const img = document.createElement('img')
    img.src = '/design/art/logo.svg'
    img.alt = ''
    img.width = 28
    img.height = 25
    const text = document.createElement('span')
    const strong = document.createElement('strong')
    strong.textContent = r.name
    const small = document.createElement('span')
    small.textContent = r.address
    text.append(strong, small)
    go.append(img, text)
    go.addEventListener('click', () => {
      // A remembered dashboard is asked again, so a moved or retired one isn't followed blindly.
      input.value = r.address
      connect(r.address)
    })
    const forget = document.createElement('button')
    forget.type = 'button'
    forget.className = 'recent-forget'
    forget.setAttribute('aria-label', `Forget ${r.name}`)
    forget.textContent = '×'
    forget.addEventListener('click', () => writeRecent(readRecent().filter((x) => x.address !== r.address)))
    li.append(go, forget)
    return li
  }))
}

function go (address, instance) {
  rememberServer(address, instance)
  say(`Found ${instance.name}${instance.version ? ` (Seasons Web ${instance.version})` : ''}. Taking you to its dashboard…`, 'ok')
  step(4)
  location.assign(instance.dashboard)
}

let running = 0

async function connect (text) {
  const run = ++running
  offer([])
  const server = parseServer(text)
  if (!server.ok) {
    step(0)
    say(explain(server.reason), 'error', server.reason)
    return
  }
  button.disabled = true
  const stale = () => run !== running
  try {
    const typed = [server.typed, server.host]
    // 1. Records: _seasons.<host>, and the SRV target's, through DNS-over-HTTPS.
    step(1)
    say(`Looking up ${server.typed}…`, 'busy')
    let named = null
    if (!isIp(server.host)) {
      const [own, srv] = await Promise.all([dns(`_seasons.${server.host}`, 'TXT'), dns(`_minecraft._tcp.${server.host}`, 'SRV')])
      named = dashboardFromRecords(txtRecords(own))
      const target = srvTarget(srv)
      if (target) typed.push(target)
      if (!named && target && target !== server.host) named = dashboardFromRecords(txtRecords(await dns(`_seasons.${target}`, 'TXT')))
    }
    if (stale()) return
    if (named) {
      step(2)
      say(`Found a dashboard record. Asking ${named}…`, 'busy')
      const answer = await ask(named)
      if (stale()) return
      if (!answer.reached || !answer.instance) {
        step(0)
        say(explain('found-down', named), 'error', 'found-down')
        offer([actionButton('Try again', () => connect(text))])
        return
      }
      const yes = confirms(answer.instance, typed)
      if (yes === 'no') {
        step(0)
        say(explain('unconfirmed', named), 'warn', 'unconfirmed')
        offer([actionButton(`Open ${answer.instance.name} anyway`, () => go(server.typed, answer.instance), true)])
        return
      }
      go(server.typed, answer.instance)
      return
    }

    // 2. The address itself may be the dashboard's own.
    step(2)
    say(`Asking ${server.direct}…`, 'busy')
    const direct = await ask(server.direct)
    if (stale()) return
    if (direct.reached && direct.instance) {
      go(server.typed, direct.instance)
      return
    }

    // 3. The conventional seasons.<domain>, which must say it is for this address.
    step(3)
    if (!isIp(server.host)) {
      const tries = conventions(server.host, server.direct)
      const answers = await Promise.all(tries.map(ask))
      if (stale()) return
      const found = answers.map((a, i) => ({ ...a, origin: tries[i] })).filter((a) => a.reached && a.instance)
      const sure = found.find((a) => confirms(a.instance, typed) === 'yes')
      if (sure) {
        go(server.typed, sure.instance)
        return
      }
      const maybe = found.find((a) => confirms(a.instance, typed) === 'unknown')
      if (maybe) {
        step(0)
        say(`We found a Seasons dashboard, ${maybe.instance.name}, at ${maybe.origin}. Is this your server?`, 'warn', 'ask')
        offer([actionButton(`Yes, open ${maybe.instance.name}`, () => go(server.typed, maybe.instance)), actionButton('No', () => notFound(server), true)])
        return
      }
    }
    await notFound(server)
  } finally {
    if (run === running) button.disabled = false
  }
}

/** 4. No dashboard: is the game server online at all? */
async function notFound (server) {
  offer([])
  step(3)
  say(`No dashboard found yet. Checking whether ${server.typed} is online…`, 'busy')
  const r = await getJson(STATUS + encodeURIComponent(server.typed))
  const online = r.ok ? serverStatus(r.json) : 'unknown'
  step(0)
  if (online === 'online') say(explain('no-seasons', server.typed), 'error', 'no-seasons')
  else if (online === 'offline') say(explain('offline', server.typed), 'error', 'offline')
  else say(explain('not-found', server.typed), 'error', 'not-found')
  offer([actionButton('Try again', () => connect(server.typed), true)])
}

form.addEventListener('submit', (e) => {
  e.preventDefault()
  connect(input.value)
})

showRecent()
const asked = new URLSearchParams(location.search).get('server')
if (asked) {
  input.value = asked
  connect(asked)
}
document.body.dataset.ready = 'true'
