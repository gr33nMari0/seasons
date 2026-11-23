// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The connect page's checks (spec, Connect flow), pure so Node's test runner checks them: which
// addresses it accepts, and what counts as a Seasons instance and its dashboard.

/** Why an address or an answer was turned down, in words. */
export const REASONS = {
  empty: 'Type your server\'s dashboard address, such as seasons.example.com.',
  'not-https': 'Seasons dashboards are only reached over https. Type the address without http://.',
  'user-info': 'An address with a name and password in it isn\'t a dashboard address.',
  'bad-address': 'That doesn\'t look like a web address. Type something like seasons.example.com.',
  'bad-port': 'That port isn\'t a number between 1 and 65535.',
  unreachable: 'Nothing answered as a Seasons dashboard at <origin>. Check the address with your server\'s owner.',
  timeout: '<origin> took too long to answer. Try again in a moment.',
  'not-seasons': '<origin> answered, but it isn\'t a Seasons dashboard.'
}

const LABEL = /^(?!-)[a-z0-9-]{1,63}(?<!-)$/
const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/

/**
 * An address as typed: a host, host:port or an https:// URL, with any path dropped. Resolves to
 * { ok: true, origin } such as "https://seasons.example.com", or { ok: false, reason }.
 */
export function parseAddress (text) {
  let t = String(text ?? '').trim().toLowerCase()
  if (t === '') return { ok: false, reason: 'empty' }
  const scheme = /^([a-z][a-z0-9+.-]*):\/\//.exec(t)
  if (scheme) {
    if (scheme[1] !== 'https') return { ok: false, reason: 'not-https' }
    t = t.slice(scheme[0].length)
  } else if (/^[a-z][a-z0-9+-]*:(?!\d|$)/.test(t)) {
    return { ok: false, reason: 'not-https' }
  }
  const authority = t.split(/[/?#]/, 1)[0]
  if (authority.includes('@')) return { ok: false, reason: 'user-info' }
  const m = /^([^:]+)(?::(\d*))?$/.exec(authority)
  if (!m) return { ok: false, reason: 'bad-address' }
  const host = m[1].replace(/\.$/, '')
  const hostOk = host === 'localhost' || IPV4.test(host) ||
    (host.includes('.') && host.split('.').every((l) => LABEL.test(l)) && !/^\d+$/.test(host.split('.').pop()))
  if (!hostOk) return { ok: false, reason: 'bad-address' }
  let port = ''
  if (m[2] !== undefined) {
    const n = Number(m[2])
    if (m[2] === '' || !Number.isInteger(n) || n < 1 || n > 65535) return { ok: false, reason: 'bad-port' }
    if (n !== 443) port = `:${n}`
  }
  return { ok: true, origin: `https://${host}${port}` }
}

/**
 * What /.well-known/seasons.json said, checked: { ok: true, name, version, features, dashboard } or
 * { ok: false, reason: 'not-seasons' }. The dashboard is the one it names only on the same origin
 * as the address typed; never a third site.
 */
export function checkInstance (json, origin) {
  if (!json || typeof json !== 'object' || json.service !== 'seasons' || json.api !== 'v1' || typeof json.name !== 'string') {
    return { ok: false, reason: 'not-seasons' }
  }
  let dashboard = `${origin}/`
  if (typeof json.dashboard === 'string') {
    try {
      const u = new URL(json.dashboard)
      if (u.protocol === 'https:' && u.origin === origin) dashboard = `${u.origin}/`
    } catch {}
  }
  return {
    ok: true,
    name: json.name.trim() || origin.replace(/^https:\/\//, ''),
    version: typeof json.version === 'string' ? json.version : '',
    features: Array.isArray(json.features) ? json.features.filter((f) => typeof f === 'string') : [],
    dashboard
  }
}

/** A reason in words, with the address filled in. */
export function explain (reason, origin = '') {
  return (REASONS[reason] ?? REASONS['bad-address']).replace('<origin>', origin)
}
