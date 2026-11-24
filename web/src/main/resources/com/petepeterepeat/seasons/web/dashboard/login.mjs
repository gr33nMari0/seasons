// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Logging in with a device code (spec, Login; M9's design): a page of its own that shows the code
// in large cells, the command to type in Minecraft with a copy button, a blocky bar counting down
// the code's five minutes, and "Waiting for you in-game…" until the player has typed it. The page
// polls; the session comes back as an HttpOnly cookie the page never sees. An expired code offers
// a new one.
import { get, post } from './api.mjs'
import { el, px, replace } from './dom.mjs'

const SEGMENTS = 12

/** "4:52" */
function clockText (ms) {
  const s = Math.max(0, Math.ceil(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/**
 * Opens the login view over the page and resolves nothing; it closes itself when logged in or
 * cancelled.
 *
 * @param onLinked called once the dashboard has the session
 */
export function openLogin (app, onLinked) {
  let timer = null
  let ticker = null
  let closed = false
  const before = document.activeElement
  const card = el('section', { class: 'login-card', 'aria-labelledby': 'login-title' })
  const view = el('div', { class: 'login-view', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'login-title', data: { login: 'open' } },
    el('header', { class: 'login-top' },
      el('div', { class: 'login-brand' },
        el('img', { src: '/app/design/art/logo.svg', alt: '', width: '28', height: '25' }),
        el('div', {},
          el('p', { class: 'login-server' }, app.config.name),
          el('p', { class: 'login-meta' }, `${location.host} · Seasons Web ${app.config.version}`))),
      app.config.site ? el('a', { class: 'login-wrong', href: `${app.config.site}connect/` }, app.t('login.wrong-server')) : null),
    el('main', { class: 'login-main' }, card))
  document.body.append(view)
  document.body.classList.add('login-open')

  const close = () => {
    closed = true
    clearTimeout(timer)
    clearInterval(ticker)
    view.remove()
    document.body.classList.remove('login-open')
    document.removeEventListener('keydown', onKey)
    if (before && before.focus) before.focus()
  }
  const onKey = (e) => { if (e.key === 'Escape') close() }
  document.addEventListener('keydown', onKey)

  const cancel = () => el('button', { type: 'button', class: 'quiet-button', data: { action: 'cancel-login' }, on: { click: close } }, app.t('login.cancel'))
  const title = () => el('h1', { id: 'login-title' }, app.t('login.link-title'))
  const figure = () => el('img', { class: 'login-figure', src: '/app/design/art/figure-diamond.svg', alt: '', width: '54', height: '72' })

  const expired = () => {
    clearInterval(ticker)
    replace(card, figure(), title(),
      el('p', { class: 'login-lead' }, app.t('login.expired')),
      el('button', { type: 'button', class: 'button', data: { action: 'new-code' }, on: { click: start } }, app.t('login.new-code')),
      cancel())
    card.querySelector('[data-action="new-code"]').focus()
  }

  async function poll (interval) {
    if (closed) return
    const r = await get('/api/v1/auth/poll').catch(() => ({ status: 0 }))
    if (closed) return
    if (r.status === 200) {
      close()
      onLinked()
      return
    }
    if (r.status === 410 || r.status === 400) {
      expired()
      return
    }
    const wait = r.status === 429 ? Math.max(interval, (r.retryAfter || 1) * 1000) : interval
    timer = setTimeout(() => poll(interval), wait)
  }

  async function start () {
    clearTimeout(timer)
    clearInterval(ticker)
    replace(card, figure(), title(), el('p', { class: 'login-lead', role: 'status' }, app.t('login.asking')), cancel())
    const r = await post('/api/v1/auth/device').catch(() => ({ status: 0 }))
    if (closed) return
    if (r.status !== 200) {
      replace(card, figure(), title(), el('p', { class: 'login-lead' }, app.t(r.status === 429 ? 'login.busy' : 'login.failed')), cancel())
      return
    }
    const code = r.json.code
    const lifetime = Math.max(1, r.json.expiresIn ?? 300) * 1000
    const ends = Date.now() + lifetime
    const command = `/web link ${code}`
    const copied = el('span', { class: 'copied', role: 'status' })
    const bar = el('div', { class: 'login-bar', 'aria-hidden': 'true' }, Array.from({ length: SEGMENTS }, () => el('b', {})))
    const left = el('span', { data: { loginLeft: true } })
    const letters = [...code].map((ch) => ch === '-'
      ? el('span', { class: 'code-dash', 'aria-hidden': 'true' }, '-')
      : el('span', { class: 'code-cell' }, ch))
    replace(card,
      figure(),
      title(),
      el('p', { class: 'login-lead' }, app.t('login.in-game', { name: app.config.name })),
      el('div', { class: 'login-command' },
        el('code', {}, '/web link ', el('strong', { data: { loginCode: true } }, code)),
        el('button', {
          type: 'button',
          class: 'copy',
          'aria-label': app.t('login.copy'),
          on: {
            click: async () => {
              try {
                await navigator.clipboard.writeText(command)
                copied.textContent = app.t('login.copied')
              } catch {}
            }
          }
        }, px('copy')),
        copied),
      el('div', { class: 'code-cells', role: 'group', 'aria-label': app.t('login.code-label', { code: [...code].filter((c) => c !== '-').join(' ') }) }, letters),
      bar,
      el('p', { class: 'login-status', role: 'status' }, app.t('login.waiting')),
      el('p', { class: 'login-expires' }, app.t('login.expires-in', { time: '' }), left),
      el('p', { class: 'login-note' }, app.t('login.stay', { days: String(app.config.sessionDays ?? 30) })),
      cancel())
    const segments = [...bar.children]
    const draw = () => {
      const remaining = ends - Date.now()
      left.textContent = clockText(remaining)
      const lit = Math.ceil((remaining / lifetime) * SEGMENTS)
      segments.forEach((s, i) => s.classList.toggle('on', i < lit))
      if (remaining <= 0) expired()
    }
    draw()
    ticker = setInterval(draw, 1000)
    card.querySelector('.copy').focus()
    const interval = Math.max(1000, (r.json.interval ?? 2) * 1000)
    timer = setTimeout(() => poll(interval), interval)
  }

  start()
  return { close }
}
