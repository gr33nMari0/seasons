// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The Seasons dashboard: one page for every address (/, /hall/<season>, /museum, /history,
// /player/<uuid>, /screen[/<name>]). It asks /api/v1/dashboard what this viewer may see, draws those
// widgets, keeps them up to date from the event feed and a timer, and in second-screen mode keeps the
// screen awake. No third-party script, style or font; every string is set as text.
import { get } from './api.mjs'
import { serverClock } from './clock.mjs'
import { el, icon, replace } from './dom.mjs'
import { fill } from './format.mjs'
import * as live from './live.mjs'
import { openLogin } from './login.mjs'
import * as pages from './pages.mjs'
import { keepOpen } from './stream.mjs'
import { keepAwake } from './wake.mjs'

/** The widgets of the dashboard's home, in order; each shows only if the viewer may see it. */
const HOME = [
  'season', 'online', 'event-feed', 'map', 'leaderboards', 'hall', 'rules', 'waypoints',
  'converter', 'links', 'server-health', 'pending-players', 'audit-log'
]

const FACTORIES = {
  season: live.season,
  online: live.online,
  leaderboards: live.leaderboards,
  'event-feed': live.eventFeed,
  map: live.map,
  links: live.links,
  rules: live.rules,
  waypoints: live.waypoints,
  converter: live.converter,
  'server-health': live.serverHealth,
  'pending-players': live.pendingPlayers,
  'audit-log': live.auditLog,
  hall: pages.hall,
  museum: pages.museum,
  history: pages.history,
  profile: pages.profile
}

const FEED_LENGTH = 50

const app = {
  config: null,
  clock: serverClock(),
  feed: [],
  season: null,
  converter: null,
  t (key, values) {
    const text = app.config?.texts?.[key]
    return fill(text ?? key, values)
  },
  sees (widget) {
    return app.config.widgets.includes(widget)
  },
  field (key) {
    return Boolean(app.config.fields[key])
  },
  viewer () {
    return app.config.viewer
  }
}

let shown = []
let stream = null
let wake = null
let notice = null

/** Which page an address is, and its parameter. */
function route (path) {
  let m
  if ((m = /^\/screen(?:\/([a-z0-9][a-z0-9-]*))?$/.exec(path))) return { page: 'screen', name: m[1] ?? 'second-screen' }
  if ((m = /^\/hall(?:\/(\d+))?$/.exec(path))) return { page: 'hall', season: m[1] ? Number(m[1]) : undefined }
  if ((m = /^\/player\/([0-9a-fA-F-]{36})$/.exec(path))) return { page: 'profile', uuid: m[1].toLowerCase() }
  if (path === '/museum') return { page: 'museum' }
  if (path === '/history') return { page: 'history' }
  return { page: 'home' }
}

/** The widgets a page shows to this viewer, with what each is given. */
function widgetsFor (r) {
  const can = (w) => w === 'profile' || (app.sees(w) && (w !== 'map' || app.config.map))
  switch (r.page) {
    case 'screen': return (app.config.screens[r.name] ?? []).filter(can).map((w) => [w, {}])
    case 'hall': return can('hall') ? [['hall', { season: r.season }]] : []
    case 'profile': return [['profile', { uuid: r.uuid }]]
    case 'museum': return can('museum') ? [['museum', {}]] : []
    case 'history': return can('history') ? [['history', {}]] : []
    default: return HOME.filter(can).map((w) => [w, {}])
  }
}

async function loadConfig () {
  const r = await get('/api/v1/dashboard')
  if (r.status !== 200) throw new Error(`the dashboard answered ${r.status}`)
  const before = app.config?.viewer
  app.config = r.json
  app.clock.sync(r.json.now)
  return before
}

function header (r) {
  const screen = r.page === 'screen'
  const v = app.viewer()
  const loginArea = el('div', { class: 'login-area' })
  const who = v.kind === 'player'
    ? el('span', { class: 'viewer', data: { viewer: v.name } }, icon('head'), app.t('login.logged-in', { name: v.name }))
    : el('button', { type: 'button', data: { action: 'login' }, on: { click: () => openLogin(app, loginArea, loggedIn) } }, app.t('login.button'))
  if (screen) {
    return el('header', { class: 'top screen-top' },
      el('strong', { class: 'brand' }, app.config.name),
      el('span', { class: 'wake', data: { wake: 'checking' } }),
      who, loginArea)
  }
  const nav = (href, key) => el('a', { href, data: { internal: true }, class: location.pathname === href ? 'on' : null }, app.t(key))
  return el('header', { class: 'top' },
    el('a', { class: 'brand', href: '/', data: { internal: true } }, icon('clock'), app.config.name),
    el('nav', {},
      nav('/', 'nav.dashboard'),
      app.sees('hall') ? nav('/hall', 'nav.hall') : null,
      app.sees('museum') ? nav('/museum', 'nav.museum') : null,
      app.sees('history') ? nav('/history', 'nav.history') : null),
    who, loginArea)
}

/** Draws the page for the address now, and refreshes every widget once. */
async function render () {
  const r = route(location.pathname)
  const screen = r.page === 'screen'
  document.body.classList.toggle('screen', screen)
  document.title = app.config.name
  const main = el('main', { class: screen ? 'grid screen-grid' : 'grid' })
  shown = widgetsFor(r).map(([w, params]) => FACTORIES[w](app, params))
  if (shown.length === 0 && app.viewer().kind === 'anonymous') {
    const login = el('section', { class: ['widget', 'widget-login'], data: { widget: 'login' } },
      el('h2', {}, app.t('login.title')),
      el('p', {}, app.t('login.closed')))
    const area = el('div', {})
    login.append(el('button', { type: 'button', data: { action: 'login' }, on: { click: () => openLogin(app, area, loggedIn) } }, app.t('login.button')), area)
    main.append(login)
  } else if (shown.length === 0) {
    main.append(el('section', { class: 'widget' }, el('p', { class: 'empty' }, app.t('page.nothing'))))
  }
  for (const w of shown) main.append(w.node)
  main.dataset.count = String(shown.length)
  replace(document.body,
    header(r),
    notice,
    screen ? el('div', { class: 'notice rotate', data: { notice: 'rotate' } }, app.t('notice.rotate')) : null,
    main)
  if (screen) startWake()
  else stopWake()
  await Promise.allSettled(shown.map((w) => w.refresh()))
  document.body.dataset.ready = 'true'
}

function startWake () {
  const dot = () => document.querySelector('[data-wake]')
  const show = (state) => {
    const d = dot()
    if (!d) return
    d.dataset.wake = state
    replace(d, el('span', { class: 'dot' }), app.t(`wake.${state}`))
  }
  if (wake) {
    show(wake.state)
    return
  }
  wake = { state: 'checking' }
  wake.lock = keepAwake({ onState: (s) => { wake.state = s; show(s) } })
  // Once a day, at 4 in the morning, the screen loads the page again to pick up a new version.
  const next = new Date()
  next.setHours(4, 0, 0, 0)
  if (next <= new Date()) next.setDate(next.getDate() + 1)
  setTimeout(() => location.reload(), next - new Date())
}

function stopWake () {
  if (wake) {
    wake.lock.release()
    wake = null
  }
}

function event (type, data) {
  if (type === 'join' || type === 'first' || type === 'dragon' || type === 'zone' || type === 'phase' || type === 'homecoming' || type === 'archived') {
    const text = live.feedLine(app, type, data)
    if (text) {
      app.feed.unshift({ type, at: data.at ?? new Date(app.clock.now()).toISOString(), text })
      app.feed.length = Math.min(app.feed.length, FEED_LENGTH)
    }
  }
  for (const w of shown) if (w.event) w.event(type, data)
}

function startStream () {
  stopStream()
  if (!app.sees('event-feed')) return
  stream = keepOpen({
    url: '/api/v1/events',
    onEvent: event,
    onOpen: (dropped) => { if (dropped) catchUp() }
  })
}

function stopStream () {
  if (stream) stream.close()
  stream = null
}

/** Looks again at who this viewer is; a logout elsewhere sends the page back to the public view. */
async function catchUp () {
  let before
  try {
    before = await loadConfig()
  } catch {
    return
  }
  const now = app.config.viewer
  const changed = before && (before.kind !== now.kind || before.uuid !== now.uuid || before.admin !== now.admin)
  if (changed) {
    if (before.kind === 'player' && now.kind === 'anonymous') {
      notice = el('div', { class: 'notice', data: { notice: 'logged-out' }, role: 'status' }, app.t('notice.logged-out'))
    }
    startStream()
    await render()
    return
  }
  await Promise.allSettled(shown.map((w) => w.refresh()))
}

async function loggedIn () {
  notice = null
  await loadConfig()
  startStream()
  await render()
}

function navigate (path) {
  if (path === location.pathname) return
  history.pushState(null, '', path)
  render()
  window.scrollTo(0, 0)
}

async function start () {
  try {
    await loadConfig()
  } catch {
    replace(document.body, el('main', { class: 'grid' }, el('section', { class: 'widget' }, el('p', {}, 'The dashboard can\'t be reached. Try again in a moment.'))))
    document.body.dataset.ready = 'true'
    setTimeout(start, 10_000)
    return
  }
  document.addEventListener('click', (e) => {
    const a = e.target.closest('a[data-internal]')
    if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    e.preventDefault()
    navigate(a.getAttribute('href'))
  })
  window.addEventListener('popstate', () => render())
  startStream()
  await render()
  // Every second the countdowns; every refresh period everything else, and who this viewer is.
  setInterval(() => { for (const w of shown) if (w.tick) w.tick() }, 1000)
  let seconds = 0
  setInterval(() => {
    seconds += 10
    const period = app.config.refresh ?? 30
    if (seconds % period === 0) catchUp()
    else for (const w of shown) if (w.every && seconds % w.every === 0) w.refresh()
  }, 10_000)
}

start()
