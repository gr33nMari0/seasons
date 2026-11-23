// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The dashboard's live and everyday widgets: the season clock, who's online, leaderboards, the
// event feed, the map, links, rules and zones, waypoints, the coordinate converter, and the admin
// widgets. Each is { node, refresh(), tick?(), event?(type, data) }; the page refreshes them on a
// timer and passes on the feed's events.
import { get } from './api.mjs'
import { parseCoordinate, toNether, toOverworld } from './convert.mjs'
import { el, head, icon, replace } from './dom.mjs'
import { bytes, countdown, date, dimension, duration, group, region, spotifyEmbed, time } from './format.mjs'

/** A widget's frame: its name for tests and styles, a title, and its body. */
export function card (name, title, ...body) {
  return el('section', { class: ['widget', `widget-${name}`], data: { widget: name } },
    el('h2', {}, title),
    ...body)
}

/** A line saying there is nothing to show. */
export function empty (text) {
  return el('p', { class: 'empty' }, text)
}

/** A link to another of the dashboard's pages, followed without reloading. */
export function link (href, ...children) {
  return el('a', { href, data: { internal: true } }, ...children)
}

// The season clock

export function season (app) {
  const body = el('div', { class: 'season' })
  const node = card('season', app.t('season.title'), body)
  const counters = []
  let data = null

  function counter (key, iso) {
    const span = el('span', { class: 'count' })
    counters.push({ span, key, iso })
    return span
  }

  function draw () {
    counters.length = 0
    if (!data || data.season === null) {
      replace(body, el('p', { class: 'big' }, app.t('season.none')))
      return
    }
    const lines = []
    if (data.phase === 'countdown' && data.countdownEndsAt) {
      lines.push(el('p', { class: 'phase ending' }, counter('phase.countdown', data.countdownEndsAt)))
    } else if (data.phase === 'active') {
      lines.push(el('p', { class: 'phase' }, app.t(`stage.${data.stage ?? 'open'}`)))
      if (data.plannedEnd) lines.push(el('p', {}, counter('season.ends', data.plannedEnd)))
    } else {
      lines.push(el('p', { class: 'phase' }, app.t(`phase.${data.phase}`)))
    }
    if (data.plannedHomecoming) {
      lines.push(el('p', {}, counter('season.homecoming-in', data.plannedHomecoming)))
    } else if (data.homecomingAt) {
      lines.push(el('p', {}, app.t('season.homecoming-came', { date: date(data.homecomingAt) })))
    }
    if (data.launchedAt) lines.push(el('p', { class: 'quiet' }, app.t('season.launched', { date: date(data.launchedAt) })))
    replace(body, el('p', { class: 'big' }, app.t('season.number', { season: data.season })), lines)
    tick()
  }

  function tick () {
    for (const c of counters) {
      c.span.textContent = app.t(c.key, { time: countdown(app.clock.until(c.iso)) })
    }
  }

  async function refresh () {
    const r = await get('/api/v1/season')
    if (r.status !== 200) return
    app.clock.sync(r.json.now)
    data = r.json
    app.season = data
    draw()
  }

  return {
    node,
    refresh,
    tick,
    event (type) {
      if (type === 'phase' || type === 'homecoming') refresh()
    }
  }
}

// Who's online

export function online (app) {
  const list = el('ul', { class: 'players' })
  const node = card('online', app.t('online.title'), list)
  let soon = null

  async function refresh () {
    const r = await get('/api/v1/players/online')
    if (r.status !== 200) return
    const players = r.json.players
    if (players.length === 0) {
      replace(list, el('li', { class: 'empty' }, app.t('online.none')))
      return
    }
    replace(list, players.map((p) => el('li', { class: 'player' },
      head(p.skin, p.name),
      el('span', { class: 'name' }, p.name),
      p.dimension ? el('span', { class: 'dimension' }, dimension(p.dimension)) : null,
      p.region ? el('span', { class: 'region', data: { region: p.region.kind } }, region(p.region)) : null,
      p.afk ? el('span', { class: 'badge' }, app.t('online.afk')) : null)))
  }

  return {
    node,
    refresh,
    event (type) {
      // Who's online is looked at again within a second of a join.
      if (type === 'join') {
        clearTimeout(soon)
        soon = setTimeout(refresh, 1500)
      }
    }
  }
}

// Leaderboards

const BOARDS = [
  ['deaths', 'leaderboards.deaths', (v) => group(v)],
  ['play-time', 'leaderboards.play-time', (v) => duration(v)],
  ['distance', 'leaderboards.distance', (v) => `${group(v)} blocks`]
]

export function leaderboards (app) {
  const body = el('div', { class: 'boards' })
  const note = el('div', {})
  const node = card('leaderboards', app.t('leaderboards.title'), note, body)

  async function refresh () {
    const r = await get('/api/v1/leaderboards')
    if (r.status !== 200) return
    const me = app.viewer().uuid
    replace(note, r.json.you?.hidden ? el('p', { class: 'note' }, app.t('leaderboards.hidden')) : null)
    replace(body, BOARDS.map(([key, title, show]) => {
      const rows = r.json.boards[key] ?? []
      return el('div', { class: 'board' },
        el('h3', {}, app.t(title)),
        rows.length === 0
          ? empty(app.t('leaderboards.empty'))
          : el('ol', {}, rows.slice(0, 10).map((e) => el('li', { data: { you: e.uuid === me ? 'true' : null } },
            el('span', { class: 'rank' }, `${e.rank}.`), ' ',
            link(`/player/${e.uuid}`, e.name), ' ',
            el('span', { class: 'value' }, show(e.value))))))
    }))
  }

  return { node, refresh }
}

// The event feed

/** One event as a line of text. */
export function feedLine (app, type, d) {
  const name = d.player?.name ?? ''
  switch (type) {
    case 'join': return app.t('feed.join', { name })
    case 'first': return app.t('feed.first', { name, label: d.label })
    case 'dragon': return d.slayer ? app.t('feed.dragon', { name: d.slayer.name }) : app.t('feed.dragon-nobody')
    case 'zone': return app.t('feed.zone', { name, zone: d.zone })
    case 'phase': return app.t(`feed.phase-${d.phase}`, { season: d.season, next: d.season + 1 })
    case 'homecoming': return app.t('feed.homecoming', { season: d.season })
    case 'archived': return app.t('feed.archived', { name, season: d.season })
    default: return null
  }
}

export function eventFeed (app) {
  const list = el('ol', { class: 'feed' })
  const node = card('event-feed', app.t('feed.title'), list)

  function draw () {
    if (app.feed.length === 0) {
      replace(list, el('li', { class: 'empty' }, app.t('feed.empty')))
      return
    }
    replace(list, app.feed.map((e) => el('li', { data: { type: e.type } },
      el('time', { datetime: e.at }, time(e.at)), ' ', e.text)))
  }

  draw()
  return {
    node,
    async refresh () { draw() },
    event () { draw() }
  }
}

// The map

export function map (app) {
  const m = app.config.map
  const note = el('p', { class: 'quiet' }, app.t('map.note'))
  const body = el('div', { class: 'map' })
  const node = card('map', m.label || app.t('map.title'), body, note)

  const asCard = () => replace(body,
    el('a', { class: 'map-card', href: m.url, target: '_blank', rel: 'noopener noreferrer', data: { mapLink: true } },
      icon('map'), el('span', {}, app.t('map.card')), el('strong', {}, app.t('map.open'))))

  if (m.framing === 'blocked' || m.framing === 'unreachable') {
    asCard()
  } else {
    const frame = el('iframe', { src: m.url, title: m.label || app.t('map.title'), referrerpolicy: 'no-referrer', allow: 'fullscreen' })
    let loaded = false
    frame.addEventListener('load', () => { loaded = true })
    // A frame that never loads gives way to the link (docs/questions.md).
    setTimeout(() => { if (!loaded && frame.isConnected) asCard() }, 10_000)
    replace(body, frame,
      el('a', { class: 'under', href: m.url, target: '_blank', rel: 'noopener noreferrer' }, app.t('map.open')))
  }
  return { node, async refresh () {} }
}

// Links

export function links (app) {
  const list = el('ul', { class: 'links' })
  const embeds = el('div', { class: 'embeds' })
  const node = card('links', app.t('links.title'), list, embeds)
  let drawn = ''

  async function refresh () {
    const r = await get('/api/v1/links')
    if (r.status !== 200) return
    const mapUrl = app.config.map?.url
    const shown = r.json.links.filter((l) => !(l.embed === 'frame' && l.url === mapUrl))
    // Drawn again only when they change, so a playing embed isn't cut off.
    const key = JSON.stringify(shown)
    if (key === drawn) return
    drawn = key
    replace(list, shown.map((l) => el('li', {},
      el('a', { href: l.url, target: '_blank', rel: 'noopener noreferrer' }, icon('link'), l.label))))
    replace(embeds, shown.map((l) => {
      if (l.embed === 'spotify') {
        const src = spotifyEmbed(l.url)
        return src ? el('iframe', { class: 'spotify', src, title: l.label, allow: 'encrypted-media', loading: 'lazy', referrerpolicy: 'no-referrer' }) : null
      }
      if (l.embed === 'frame') {
        return el('iframe', { class: 'frame', src: l.url, title: l.label, loading: 'lazy', referrerpolicy: 'no-referrer' })
      }
      return null
    }))
  }

  return { node, refresh }
}

// Rules and zones

export function rules (app) {
  const body = el('div', {})
  const node = card('rules', app.t('rules.title'), body)

  async function refresh () {
    const [s, l] = await Promise.all([
      get('/api/v1/season'),
      app.sees('links') ? get('/api/v1/links') : Promise.resolve({ status: 0 })
    ])
    if (s.status !== 200) return
    const zones = s.json.zones ?? []
    let homecoming
    if (s.json.homecomingAt) homecoming = app.t('rules.homecoming-came')
    else if (s.json.plannedHomecoming) homecoming = app.t('rules.homecoming-date', { date: date(s.json.plannedHomecoming) })
    else homecoming = app.t('rules.homecoming-vote')
    const ruleLinks = l.status === 200 ? l.json.links.filter((x) => /rule/i.test(x.label)) : []
    replace(body,
      zones.length === 0
        ? empty(app.t('rules.none'))
        : el('ul', { class: 'zones' }, zones.map((z) => el('li', {},
          el('span', { class: 'name' }, z.name), ' ',
          el('span', { class: `mode mode-${z.mode}` }, app.t(`zone.${z.mode}`))))),
      s.json.season === null ? null : el('p', {}, homecoming),
      ruleLinks.map((x) => el('p', {}, el('a', { href: x.url, target: '_blank', rel: 'noopener noreferrer' }, icon('link'), x.label))))
  }

  return { node, refresh }
}

// Shared waypoints

export function waypoints (app) {
  const body = el('div', {})
  const node = card('waypoints', app.t('waypoints.title'), body)

  async function refresh () {
    const r = await get('/api/v1/waypoints')
    if (r.status !== 200) return
    const all = r.json.waypoints
    if (all.length === 0) {
      replace(body, empty(app.t('waypoints.none')))
      return
    }
    const sets = new Map()
    for (const w of all) {
      if (!sets.has(w.set)) sets.set(w.set, [])
      sets.get(w.set).push(w)
    }
    replace(body, [...sets].map(([set, points]) => el('div', { class: 'set' },
      el('h3', {}, set),
      el('ul', {}, points.map((w) => el('li', {},
        el('span', { class: 'name' }, w.name), ' ',
        el('span', { class: 'at' }, `${w.x}, ${w.y}, ${w.z}`), ' ',
        el('span', { class: 'quiet' }, dimension(w.dimension)),
        w.dimension === 'minecraft:overworld'
          ? [' · ', el('span', { class: 'nether' }, app.t('waypoints.nether', { x: toNether(w.x), z: toNether(w.z) }))]
          : null,
        app.converter
          ? el('button', { type: 'button', class: 'small', data: { action: 'convert' }, on: { click: () => app.converter.fill(w) } }, app.t('waypoints.convert'))
          : null))))))
  }

  return { node, refresh }
}

// The coordinate converter

export function converter (app) {
  const field = (name) => el('input', { name, inputmode: 'numeric', autocomplete: 'off', 'aria-label': name.replace('-', ' ') })
  const ox = field('overworld-x')
  const oz = field('overworld-z')
  const nx = field('nether-x')
  const nz = field('nether-z')
  const pair = (fromX, fromZ, toX, toZ, convert) => () => {
    for (const [from, to] of [[fromX, toX], [fromZ, toZ]]) {
      const n = parseCoordinate(from.value)
      to.value = n === null ? '' : String(convert(n))
    }
  }
  ox.addEventListener('input', pair(ox, oz, nx, nz, toNether))
  oz.addEventListener('input', pair(ox, oz, nx, nz, toNether))
  nx.addEventListener('input', pair(nx, nz, ox, oz, toOverworld))
  nz.addEventListener('input', pair(nx, nz, ox, oz, toOverworld))
  const node = card('converter', app.t('converter.title'),
    el('div', { class: 'converter' },
      el('fieldset', {}, el('legend', {}, app.t('converter.overworld')), el('label', {}, 'X ', ox), el('label', {}, 'Z ', oz)),
      el('fieldset', {}, el('legend', {}, app.t('converter.nether')), el('label', {}, 'X ', nx), el('label', {}, 'Z ', nz))),
    el('p', { class: 'quiet' }, app.t('converter.note')))
  app.converter = {
    fill (w) {
      if (w.dimension === 'minecraft:the_nether') {
        nx.value = String(w.x)
        nz.value = String(w.z)
        pair(nx, nz, ox, oz, toOverworld)()
      } else {
        ox.value = String(w.x)
        oz.value = String(w.z)
        pair(ox, oz, nx, nz, toNether)()
      }
      node.scrollIntoView({ block: 'nearest' })
    }
  }
  return { node, async refresh () {} }
}

// Admin

export function serverHealth (app) {
  const body = el('dl', { class: 'health' })
  const node = card('server-health', app.t('health.title'), body)
  async function refresh () {
    const r = await get('/api/v1/admin/health')
    if (r.status !== 200) return
    const h = r.json
    replace(body,
      el('dt', {}, app.t('health.tps')), el('dd', {}, h.tps.map((v) => v.toFixed(1)).join(' · ')),
      el('dt', {}, app.t('health.tick')), el('dd', {}, `${h.tickMs.toFixed(1)} ms`),
      el('dt', {}, app.t('health.memory')), el('dd', {}, `${bytes(h.memory.used)} / ${bytes(h.memory.max)}`))
  }
  return { node, refresh, every: 10 }
}

export function pendingPlayers (app) {
  const body = el('div', {})
  const node = card('pending-players', app.t('pending.title'), body)
  async function refresh () {
    const r = await get('/api/v1/admin/pending')
    if (r.status !== 200) return
    const players = r.json.players
    replace(body, players.length === 0
      ? empty(app.t('pending.none'))
      : el('ul', {}, players.map((p) => el('li', {}, app.t('pending.line', { name: p.name, season: p.season })))))
  }
  return { node, refresh }
}

export function auditLog (app) {
  const body = el('div', {})
  const node = card('audit-log', app.t('audit.title'), body)
  async function refresh () {
    const r = await get('/api/v1/admin/audit')
    if (r.status !== 200) return
    const entries = [...r.json.entries].reverse()
    replace(body, entries.length === 0
      ? empty(app.t('audit.none'))
      : el('ol', { class: 'audit' }, entries.map((e) => el('li', {},
        el('time', { datetime: e.time }, `${date(e.time)} ${time(e.time)}`), ' ',
        el('span', { class: 'actor' }, e.actor), ' ',
        el('span', { class: 'action' }, e.action), ' ',
        el('span', { class: 'target' }, e.target), ' ',
        el('span', { class: `result result-${e.result}` }, e.result)))))
  }
  return { node, refresh }
}
