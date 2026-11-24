// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The Seasons dashboard: one page for every address (/, /hall/<season>, /museum, /history,
// /player/<uuid>, /help[/<guide>], /screen[/<name>]). It asks /api/v1/dashboard what this viewer
// may see, draws those widgets, keeps them up to date from the event feed and a timer, and in
// second-screen mode keeps the screen awake. The home page is laid out on a 12-column grid, the
// server's default or a dashboard the viewer made (M9), kept in this browser. No third-party
// script, style or font; every string is set as text.
import { get } from './api.mjs'
import { serverClock } from './clock.mjs'
import { applyPlacement, editorBar, makeEditable, unavailable } from './editor.mjs'
import { el, icon, px, replace } from './dom.mjs'
import { fill } from './format.mjs'
import { helpPage, helpWidget } from './help.mjs'
import * as L from './layout.mjs'
import * as live from './live.mjs'
import { openLogin } from './login.mjs'
import * as pages from './pages.mjs'
import { shareButton } from './share.mjs'
import { keepOpen } from './stream.mjs'
import { keepAwake } from './wake.mjs'

/** The widgets of the server's default home, in order; each shows only if the viewer may see it. */
const HOME = [
  'season', 'online', 'event-feed', 'map', 'leaderboards', 'hall', 'rules', 'waypoints',
  'converter', 'links', 'help', 'server-health', 'pending-players', 'audit-log'
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
  profile: pages.profile,
  help: helpWidget,
  'help-page': helpPage
}

/** Each widget's title, for the editor and what it announces. */
const TITLES = {
  season: 'season.title',
  online: 'online.title',
  leaderboards: 'leaderboards.title',
  'event-feed': 'feed.title',
  map: 'map.title',
  links: 'links.title',
  rules: 'rules.title',
  waypoints: 'waypoints.title',
  converter: 'converter.title',
  'server-health': 'health.title',
  'pending-players': 'pending.title',
  'audit-log': 'audit.title',
  hall: 'hall.title',
  museum: 'museum.title',
  history: 'history.title',
  help: 'help.title'
}

const FEED_LENGTH = 50

const app = {
  config: null,
  clock: serverClock(),
  feed: [],
  season: null,
  converter: null,
  editing: false,
  t (key, values) {
    const text = app.config?.texts?.[key]
    return fill(text ?? key, values)
  },
  sees (widget) {
    // Help holds nothing about anyone, but a closed dashboard shows nothing but the login.
    if (widget === 'help') return app.config.public || app.config.viewer.kind === 'player'
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
let store = L.parseStore(null)
let liveRegion = null

// Layouts, kept in this browser only

function loadStore () {
  try { store = L.parseStore(localStorage.getItem(L.STORAGE_KEY)) } catch { store = L.parseStore(null) }
}

function saveStore () {
  try { localStorage.setItem(L.STORAGE_KEY, JSON.stringify(store)) } catch {}
}

const titleOf = (id) => (TITLES[id] ? app.t(TITLES[id]) : id)
const active = () => store.dashboards.find((d) => d.id === store.active) ?? null

/** What a viewer may see as a widget on a dashboard. */
function can (w) {
  return w === 'profile' || (app.sees(w) && (w !== 'map' || app.config.map))
}

/** Which page an address is, and its parameter. */
function route (path) {
  let m
  if ((m = /^\/screen(?:\/([a-z0-9][a-z0-9-]*))?$/.exec(path))) return { page: 'screen', name: m[1] ?? 'second-screen' }
  if ((m = /^\/hall(?:\/(\d+))?$/.exec(path))) return { page: 'hall', season: m[1] ? Number(m[1]) : undefined }
  if ((m = /^\/player\/([0-9a-fA-F-]{36})$/.exec(path))) return { page: 'profile', uuid: m[1].toLowerCase() }
  if ((m = /^\/help(?:\/([a-z0-9-]+))?$/.exec(path))) return { page: 'help', guide: m[1] }
  if (path === '/museum') return { page: 'museum' }
  if (path === '/history') return { page: 'history' }
  return { page: 'home' }
}

/** The default home's items: the server's widgets this viewer may see, packed in order. */
function defaultItems () {
  return L.place(HOME.filter(can))
}

/** The widgets a page shows to this viewer, with what each is given, and their layout if any. */
function widgetsFor (r) {
  switch (r.page) {
    case 'screen': {
      const mine = r.name === 'second-screen' && store.screen ? store.dashboards.find((d) => d.id === store.screen) : null
      if (mine) return { items: mine.items, list: mine.items.map((i) => [i.id, {}]) }
      return { items: null, list: (app.config.screens[r.name] ?? []).filter(can).map((w) => [w, {}]) }
    }
    case 'hall': return { items: null, list: can('hall') ? [['hall', { season: r.season }]] : [] }
    case 'profile': return { items: null, list: [['profile', { uuid: r.uuid }]] }
    case 'museum': return { items: null, list: can('museum') ? [['museum', {}]] : [] }
    case 'history': return { items: null, list: can('history') ? [['history', {}]] : [] }
    case 'help': return { items: null, list: can('help') ? [['help-page', { guide: r.guide }]] : [] }
    default: {
      const items = active()?.items ?? defaultItems()
      return { items, list: items.map((i) => [i.id, {}]) }
    }
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

function login () {
  openLogin(app, loggedIn)
}

function header (r) {
  const screen = r.page === 'screen'
  const v = app.viewer()
  const who = v.kind === 'player'
    ? el('span', { class: 'viewer', data: { viewer: v.name } }, icon('head'), el('span', { class: 'viewer-name' }, app.t('login.logged-in', { name: v.name })))
    : el('button', { type: 'button', class: 'button small', data: { action: 'login' }, on: { click: login } }, px('user'), app.t('login.button'))
  const theme = el('button', {
    type: 'button',
    class: 'icon-button theme-toggle',
    'data-theme-toggle': '',
    'aria-pressed': 'false',
    'aria-label': app.t('theme.light'),
    'data-label-light': app.t('theme.light'),
    'data-label-dark': app.t('theme.dark')
  }, px('sun'), px('moon'))
  if (screen) {
    return el('header', { class: 'top screen-top' },
      el('span', { class: 'brand' }, el('img', { src: '/app/design/art/logo.svg', alt: '', width: '28', height: '25' }), app.config.name),
      el('span', { class: 'wake', data: { wake: 'checking' } }),
      who)
  }
  const nav = (href, key, glyph) => el('a', {
    href,
    data: { internal: true },
    class: location.pathname === href || (href !== '/' && location.pathname.startsWith(href + '/')) ? 'on' : null,
    'aria-current': location.pathname === href ? 'page' : null
  }, px(glyph), app.t(key))
  const editable = r.page === 'home'
  return el('header', { class: 'top' },
    el('a', { class: 'brand', href: '/', data: { internal: true } },
      el('img', { src: '/app/design/art/logo.svg', alt: '', width: '28', height: '25' }),
      el('span', {}, app.config.name)),
    el('nav', { 'aria-label': app.t('nav.label') },
      nav('/', 'nav.dashboard', 'home'),
      app.sees('hall') ? nav('/hall', 'nav.hall', 'star') : null,
      app.sees('museum') ? nav('/museum', 'nav.museum', 'trophy') : null,
      app.sees('history') ? nav('/history', 'nav.history', 'clock') : null,
      app.sees('help') ? nav('/help', 'nav.help', 'help') : null),
    el('div', { class: 'actions' },
      editable
        ? el('button', { type: 'button', class: 'icon-button', data: { action: 'layout-edit' }, 'aria-pressed': String(app.editing), 'aria-label': app.t('layout.edit'), title: app.t('layout.edit'), on: { click: toggleEditing } }, px('layout'))
        : null,
      shareButton(app),
      theme,
      who))
}

/** One widget, made by its factory, or a placeholder for one this viewer can't see. */
function make (w, params) {
  if (!FACTORIES[w] || (w !== 'help-page' && !can(w))) return unavailable(app, w, titleOf(w))
  const made = FACTORIES[w](app, params)
  made.node.dataset.slot = w
  return made
}

/** Draws the page for the address now, and refreshes every widget once. */
async function render () {
  const r = route(location.pathname)
  const screen = r.page === 'screen'
  document.body.classList.toggle('screen', screen)
  document.body.classList.toggle('editing', app.editing && r.page === 'home')
  window.seasonsTheme?.refresh()
  document.title = r.page === 'home' ? app.config.name : `${pageTitle(r)} · ${app.config.name}`
  const { items, list } = widgetsFor(r)
  const main = el('main', { class: screen ? 'grid screen-grid' : 'grid', id: 'main' })
  shown = list.map(([w, params]) => make(w, params))
  if (shown.length === 0 && app.viewer().kind === 'anonymous') {
    main.append(el('section', { class: ['widget', 'widget-login'], data: { widget: 'login' } },
      el('h2', {}, app.t('login.title')),
      el('p', {}, app.t('login.closed')),
      el('button', { type: 'button', class: 'button', data: { action: 'login' }, on: { click: login } }, app.t('login.button'))))
  } else if (shown.length === 0) {
    main.append(el('section', { class: 'widget' }, el('p', { class: 'empty' }, app.t('page.nothing'))))
  }
  for (const w of shown) main.append(w.node)
  main.dataset.count = String(shown.length)
  liveRegion = el('div', { class: 'sr-only', role: 'status', 'aria-live': 'polite' })
  const editing = app.editing && r.page === 'home'
  replace(document.body,
    el('a', { class: 'skip', href: '#main' }, app.t('nav.skip')),
    header(r),
    notice,
    screen ? el('div', { class: 'notice rotate', data: { notice: 'rotate' } }, app.t('notice.rotate')) : null,
    editing ? editorBar(app, editorContext()) : null,
    main,
    liveRegion)
  if (items && (r.page === 'home' || screen)) applyPlacement(main, items)
  if (editing) {
    const state = { items: active().items }
    makeEditable(app, main, state, {
      onChange: (next) => { active().items = next; saveStore() },
      onRemove: () => render(),
      announce: (text) => { liveRegion.textContent = text },
      titleOf
    })
  }
  if (screen) startWake()
  else stopWake()
  await Promise.allSettled(shown.map((w) => w.refresh()))
  document.body.dataset.ready = 'true'
}

function pageTitle (r) {
  return {
    hall: app.t('nav.hall'),
    museum: app.t('nav.museum'),
    history: app.t('nav.history'),
    help: app.t('nav.help'),
    profile: app.t('profile.title'),
    screen: app.t('nav.dashboard')
  }[r.page] ?? app.t('nav.dashboard')
}

// Editing layouts

function toggleEditing () {
  app.editing = !app.editing
  if (app.editing && !active()) {
    // Editing the server's default starts the viewer's own copy of it.
    const id = L.newId(store)
    store.dashboards.push({ id, name: app.t('layout.name-default'), items: defaultItems() })
    store.active = id
    saveStore()
  }
  render()
}

function editorContext () {
  const current = active()
  const onIt = new Set((current?.items ?? []).map((i) => i.id))
  return {
    store,
    active: current,
    admin: Boolean(app.viewer().admin),
    titleOf,
    addable: [...HOME, 'museum', 'history'].filter((w) => can(w) && !onIt.has(w)),
    code: () => L.encode(current),
    webYml: () => L.toWebYml(current),
    onSelect: (id) => { store.active = id; saveStore(); if (!id) app.editing = false; render() },
    onNew: () => {
      const id = L.newId(store)
      store.dashboards.push({ id, name: `${app.t('layout.name-default')} ${store.dashboards.length + 1}`, items: current ? current.items : defaultItems() })
      store.active = id
      saveStore()
      render()
    },
    onRename: (name) => { if (current && name.trim()) { current.name = name.trim().slice(0, 40); saveStore(); render() } },
    onDelete: (id) => {
      store.dashboards = store.dashboards.filter((d) => d.id !== id)
      if (store.screen === id) store.screen = null
      store.active = null
      app.editing = false
      saveStore()
      render()
    },
    onScreen: (id) => { store.screen = store.screen === id ? null : id; saveStore(); render() },
    onAdd: (w) => { current.items = L.add(current.items, w); saveStore(); render() },
    onReset: () => { current.items = defaultItems(); saveStore(); render() },
    onPaste: (code) => {
      const got = L.decode(code)
      if (!got) return false
      const id = L.newId(store)
      store.dashboards.push({ id, name: got.name ?? app.t('layout.name-default'), items: got.items })
      store.active = id
      saveStore()
      render()
      return true
    },
    onDone: () => { app.editing = false; render() }
  }
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
  if (!app.editing) await Promise.allSettled(shown.map((w) => w.refresh()))
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
  app.editing = false
  render()
  window.scrollTo(0, 0)
}

async function start () {
  loadStore()
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
  window.addEventListener('popstate', () => { app.editing = false; render() })
  // Another tab of this dashboard changed the layouts: follow it.
  window.addEventListener('storage', (e) => { if (e.key === L.STORAGE_KEY && !app.editing) { loadStore(); render() } })
  startStream()
  await render()
  // Every second the countdowns; every refresh period everything else, and who this viewer is.
  setInterval(() => { for (const w of shown) if (w.tick) w.tick() }, 1000)
  let seconds = 0
  setInterval(() => {
    seconds += 10
    const period = app.config.refresh ?? 30
    if (seconds % period === 0) catchUp()
    else if (!app.editing) for (const w of shown) if (w.every && seconds % w.every === 0) w.refresh()
  }, 10_000)
}

start()
