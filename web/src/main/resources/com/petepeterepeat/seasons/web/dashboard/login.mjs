// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Logging in with a device code (spec, Login): the page asks for a code, shows it with the command
// to type in Minecraft, and polls until the player has typed it. The session comes back as an
// HttpOnly cookie the page never sees. An expired code offers a new one.
import { get, post } from './api.mjs'
import { el, replace } from './dom.mjs'

/**
 * Opens the login panel in `container` and resolves once it is closed, logged in or not.
 *
 * @param onLinked called once the dashboard has the session
 */
export function openLogin (app, container, onLinked) {
  let timer = null
  let closed = false
  const panel = el('div', { class: 'login', role: 'dialog', 'aria-label': app.t('login.title') })
  replace(container, panel)

  const close = () => {
    closed = true
    clearTimeout(timer)
    replace(container)
  }

  const cancel = el('button', { type: 'button', class: 'quiet-button', data: { action: 'cancel-login' }, on: { click: close } }, app.t('login.cancel'))

  const expired = () => replace(panel,
    el('p', {}, app.t('login.expired')),
    el('button', { type: 'button', data: { action: 'new-code' }, on: { click: start } }, app.t('login.new-code')),
    cancel)

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
    replace(panel, el('p', {}, app.t('login.asking')), cancel)
    const r = await post('/api/v1/auth/device').catch(() => ({ status: 0 }))
    if (closed) return
    if (r.status !== 200) {
      replace(panel, el('p', {}, app.t(r.status === 429 ? 'login.busy' : 'login.failed')), cancel)
      return
    }
    const code = r.json.code
    replace(panel,
      el('p', {}, app.t('login.steps')),
      el('p', { class: 'command' }, '/web link ', el('strong', { data: { loginCode: true } }, code)),
      el('p', { class: 'quiet' }, app.t('login.waiting')),
      cancel)
    const interval = Math.max(1000, (r.json.interval ?? 2) * 1000)
    timer = setTimeout(() => poll(interval), interval)
  }

  start()
  return { close }
}
