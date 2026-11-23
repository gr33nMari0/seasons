// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The connect page: reads the address typed, asks that server's /.well-known/seasons.json whether it
// is a Seasons dashboard, and goes there. The dashboard keeps the login (spec, Connect flow): this
// page stores nothing and sends nothing anywhere but the address typed.
import { checkInstance, explain, parseAddress } from './check.mjs'

const TIMEOUT_MS = 8000
const form = document.querySelector('form[data-connect]')
const input = form.querySelector('input[name="server"]')
const status = document.querySelector('[data-status]')
const button = form.querySelector('button[type="submit"]')

function say (text, kind) {
  status.textContent = text
  status.dataset.kind = kind
}

async function connect (text) {
  const address = parseAddress(text)
  if (!address.ok) {
    say(explain(address.reason), 'error')
    status.dataset.error = address.reason
    return
  }
  const { origin } = address
  button.disabled = true
  say(`Asking ${origin}…`, 'busy')
  const timer = new AbortController()
  const stop = setTimeout(() => timer.abort(), TIMEOUT_MS)
  let json
  try {
    const res = await fetch(`${origin}/.well-known/seasons.json`, {
      credentials: 'omit',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      signal: timer.signal,
      headers: { Accept: 'application/json' }
    })
    json = res.ok ? await res.json().catch(() => null) : null
  } catch (err) {
    const reason = err?.name === 'AbortError' ? 'timeout' : 'unreachable'
    say(explain(reason, origin), 'error')
    status.dataset.error = reason
    button.disabled = false
    return
  } finally {
    clearTimeout(stop)
  }
  const instance = checkInstance(json, origin)
  if (!instance.ok) {
    say(explain(instance.reason, origin), 'error')
    status.dataset.error = instance.reason
    button.disabled = false
    return
  }
  delete status.dataset.error
  say(`Found ${instance.name}${instance.version ? ` (Seasons Web ${instance.version})` : ''}. Opening its dashboard…`, 'ok')
  location.assign(instance.dashboard)
}

form.addEventListener('submit', (e) => {
  e.preventDefault()
  connect(input.value)
})

const asked = new URLSearchParams(location.search).get('server')
if (asked) {
  input.value = asked
  connect(asked)
}
document.body.dataset.ready = 'true'
