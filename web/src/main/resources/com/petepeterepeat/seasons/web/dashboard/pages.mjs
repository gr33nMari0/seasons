// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The reference pages' widgets: the Hall of Fame browser, the museum, season history and a player's
// profile with their season cards and archived gear as text.
import { get } from './api.mjs'
import { el, head, icon, replace } from './dom.mjs'
import { date, duration, item, statLabel, statValue } from './format.mjs'
import { iconFor } from './items.mjs'
import { card, empty, link } from './live.mjs'

/** The seasons that have a gallery, newest first, or the season now when history is hidden. */
async function gallerySeasons (app) {
  if (app.sees('history')) {
    const r = await get('/api/v1/seasons')
    if (r.status === 200) return r.json.seasons.filter((s) => s.gallery).map((s) => s.season).reverse()
  }
  const s = await get('/api/v1/season')
  return s.status === 200 && s.json.season ? [s.json.season] : []
}

// The Hall of Fame

/** A season's gallery: its cells, their owners and awards; with no season, the newest gallery. */
export function hall (app, { season } = {}) {
  const picker = el('nav', { class: 'picker' })
  const body = el('div', {})
  const title = el('h2', {}, app.t('hall.title'))
  const node = el('section', { class: ['widget', 'widget-hall'], data: { widget: 'hall' } }, title, picker, body)

  async function refresh () {
    const seasons = await gallerySeasons(app)
    const shown = season ?? seasons[0]
    replace(picker, seasons.map((n) => el('a', { href: `/hall/${n}`, class: n === shown ? 'on' : null, data: { internal: true } }, app.t('hall.season', { season: n }))))
    replace(title, app.t('hall.title'), shown ? [' · ', app.t('hall.season', { season: shown })] : null)
    if (!shown) {
      replace(body, empty(app.t('hall.none')))
      return
    }
    const r = await get(`/api/v1/hall/${shown}`)
    if (r.status !== 200) {
      replace(body, empty(app.t('hall.none')))
      return
    }
    replace(body, el('ul', { class: 'cells' }, r.json.cells.map((c) => el('li', { class: c.owner ? 'cell' : 'cell vacant' },
      el('span', { class: 'cell-id' }, c.cell),
      c.owner
        ? link(`/player/${c.owner.uuid}`, head(null, c.owner.name), c.owner.name)
        : el('span', { class: 'quiet' }, app.t('hall.empty-cell')),
      c.awards.length ? el('span', { class: 'awards' }, icon('trophy'), c.awards.map(words).join(', ')) : null))))
  }
  return { node, refresh }
}

/** An award's id in words, such as "furthest-builder" as "Furthest builder". */
function words (id) {
  const t = String(id).replace(/[-_]+/g, ' ').trim()
  return t.charAt(0).toUpperCase() + t.slice(1)
}

// The museum

export function museum (app) {
  const body = el('div', {})
  const node = card('museum', app.t('museum.title'), body)
  async function refresh () {
    const r = await get('/api/v1/museum')
    if (r.status !== 200) return
    const { trophies, eggs } = r.json
    if (trophies.length === 0 && eggs.length === 0) {
      replace(body, empty(app.t('museum.none')))
      return
    }
    replace(body,
      el('ul', { class: 'trophies' }, trophies.map((t) => {
        const text = t.item ? item(t.item) : null
        return el('li', {},
          icon(t.item ? iconFor(t.item.id) : 'trophy'),
          el('div', {},
            el('strong', {}, t.label ?? text?.title ?? t.anchor),
            text ? el('span', { class: 'kind' }, text.kind ?? (t.label ? text.title : null)) : null,
            text && text.details.length ? el('span', { class: 'details' }, text.details.join(', ')) : null,
            el('span', { class: 'quiet' }, app.t('museum.trophy-line', { season: t.season, credit: t.credit }))))
      })),
      eggs.length
        ? [el('h3', {}, app.t('museum.eggs')),
            el('ul', { class: 'eggs' }, eggs.map((e) => el('li', {}, icon('egg'),
              app.t('museum.egg', { season: e.season, name: e.slayer.name }))))]
        : null)
  }
  return { node, refresh }
}

// Season history

export function history (app) {
  const body = el('div', {})
  const node = card('history', app.t('history.title'), body)
  async function refresh () {
    const r = await get('/api/v1/seasons')
    if (r.status !== 200) return
    const seasons = [...r.json.seasons].reverse()
    if (seasons.length === 0) {
      replace(body, empty(app.t('history.none')))
      return
    }
    replace(body, el('ol', { class: 'history' }, seasons.map((s) => {
      const name = app.t('hall.season', { season: s.season })
      const span = s.firstSeen
        ? app.t('history.span', { from: date(s.firstSeen), to: s.current ? app.t('history.now') : date(s.lastSeen) })
        : null
      return el('li', { data: { current: s.current ? 'true' : null } },
        el('strong', {}, s.gallery && app.sees('hall') ? link(`/hall/${s.season}`, name) : name),
        s.current ? el('span', { class: 'badge' }, app.t('history.now')) : null,
        el('span', {}, s.players === 1 ? app.t('history.player') : app.t('history.players', { count: s.players })),
        el('span', {}, s.firsts === 1 ? app.t('history.first') : app.t('history.firsts', { count: s.firsts })),
        span ? el('span', { class: 'quiet' }, span) : null)
    })))
  }
  return { node, refresh }
}

// A player's profile

/** Archived gear as text, each item with our icon for its kind. */
export function gearList (items) {
  return el('ul', { class: 'gear', data: { gear: true } }, items.map((g) => {
    const text = item(g)
    return el('li', {},
      icon(iconFor(g.id)),
      el('span', { class: 'title' }, text.title),
      text.kind ? el('span', { class: 'kind' }, `(${text.kind})`) : null,
      text.details.length ? el('span', { class: 'details' }, text.details.join(', ')) : null)
  }))
}

export function profile (app, { uuid }) {
  const body = el('div', {})
  const node = card('profile', app.t('profile.title'), body)

  async function refresh () {
    const r = await get(`/api/v1/players/${uuid}`)
    if (r.status === 401 || r.status === 403) {
      replace(body, empty(app.t('profile.login')))
      return
    }
    if (r.status !== 200) {
      replace(body, empty(app.t('profile.not-found')))
      return
    }
    const p = r.json
    const seasons = [...p.seasons].sort((a, b) => b.season - a.season)
    const full = seasons.some((s) => s.statistics)
    const gear = app.field('hall.gear') && app.sees('hall')
      ? await Promise.all(seasons.map(async (s) => {
        const g = await get(`/api/v1/hall/${s.season}/${uuid}`)
        return g.status === 200 && Array.isArray(g.json.gear) ? g.json.gear : null
      }))
      : seasons.map(() => null)
    replace(body,
      el('div', { class: 'who' }, head(p.skin, p.name), el('strong', { class: 'big' }, p.name),
        el('span', {}, seasons.length === 1 ? app.t('profile.season') : app.t('profile.seasons', { count: seasons.length }))),
      full ? null : el('p', { class: 'note' }, app.t('profile.login')),
      seasons.map((s, i) => seasonCard(app, s, gear[i])))
  }
  return { node, refresh }
}

function seasonCard (app, s, gear) {
  const stats = s.statistics
    ? Object.entries(s.statistics)
      .map(([id, v]) => [statLabel(id), statValue(id, v)])
      .sort((a, b) => a[0].localeCompare(b[0]))
    : []
  const n = s.cell ? s.cell.split('/').pop() : null
  return el('article', { class: 'card', data: { season: s.season } },
    el('h3', {}, app.t('hall.season', { season: s.season })),
    s.cell
      ? el('p', {}, link(`/hall/${s.season}`, icon('trophy'), app.t('profile.cell', { cell: n })))
      : null,
    s.playTime !== undefined
      ? el('dl', { class: 'facts' },
        el('dt', {}, app.t('profile.play-time')), el('dd', {}, duration(s.playTime)),
        el('dt', {}, app.t('profile.first-seen')), el('dd', {}, date(s.firstSeen)),
        el('dt', {}, app.t('profile.last-seen')), el('dd', {}, date(s.lastSeen)))
      : null,
    s.awards.length ? [el('h4', {}, app.t('profile.awards')), el('p', {}, s.awards.map(words).join(', '))] : null,
    s.firsts.length ? [el('h4', {}, app.t('profile.firsts')), el('ul', {}, s.firsts.map((f) => el('li', {}, f.label)))] : null,
    stats.length
      ? [el('h4', {}, app.t('profile.statistics')),
          el('table', { class: 'stats' }, el('tbody', {}, stats.map(([label, value]) => el('tr', {}, el('th', { scope: 'row' }, label), el('td', {}, value)))))]
      : null,
    gear ? [el('h4', {}, app.t('profile.gear')), gear.length ? gearList(gear) : empty(app.t('profile.no-gear'))] : null)
}
