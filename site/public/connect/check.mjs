// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The connect page's checks (spec, Connect flow; questions 314 and 336), pure so Node's test runner
// checks them: which addresses it accepts, what counts as a Seasons instance and its dashboard, and
// how a dashboard is found from the address players join the game with: the _seasons TXT record,
// the Minecraft SRV record, and the conventional seasons.<domain>.

/** Why an address or an answer was turned down, in words. */
export const REASONS = {
  empty: 'Type the address you play on, such as play.example.com.',
  'not-https': 'Seasons dashboards are only reached over https. Type the address without http://.',
  'user-info': 'An address with a name and password in it isn\'t a dashboard address.',
  'bad-address': 'That doesn\'t look like a web address. Type something like seasons.example.com.',
  'bad-port': 'That port isn\'t a number between 1 and 65535.',
  unreachable: 'Nothing answered as a Seasons dashboard at <origin>. Check the address with your server\'s owner.',
  timeout: '<origin> took too long to answer. Try again in a moment.',
  'not-seasons': '<origin> answered, but it isn\'t a Seasons dashboard.',
  'no-seasons': '<origin> is online, but it doesn\'t publish a Seasons dashboard. It may not run Seasons Web, or its owner hasn\'t added the discovery record yet.',
  offline: 'We couldn\'t reach <origin>. Check the address, or the server may be offline.',
  'not-found': 'We couldn\'t find a Seasons dashboard for <origin>, and couldn\'t check whether the server is online. Check the address with your server\'s owner.',
  'found-down': 'This server has a dashboard at <origin>, but it isn\'t answering. The server may be offline or restarting.',
  unconfirmed: 'We found a Seasons dashboard at <origin>, but it doesn\'t say it\'s for the address you typed.'
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
    gameAddresses: Array.isArray(json.gameAddresses) ? json.gameAddresses.filter((g) => typeof g === 'string').map((g) => g.toLowerCase()) : null,
    dashboard
  }
}

/** A reason in words, with the address filled in. */
export function explain (reason, origin = '') {
  return (REASONS[reason] ?? REASONS['bad-address']).replace('<origin>', origin)
}

/**
 * An address as typed, read as a game address: { ok: true, host, port, typed, direct } where typed
 * is how a dashboard lists it (lower case, no default port 25565) and direct is the https origin to
 * try in case the address is the dashboard's own; or { ok: false, reason }.
 */
export function parseServer (text) {
  const address = parseAddress(text)
  if (!address.ok) return address
  const raw = String(text).trim().toLowerCase()
  const explicit = /^https:\/\//.test(raw)
  const url = new URL(address.origin)
  const host = url.hostname
  const typedPort = /^(?:https:\/\/)?[^/?#]*:(\d+)/.exec(raw)?.[1] ?? ''
  const port = typedPort === '25565' ? '' : typedPort
  const direct = explicit || !port ? address.origin : `https://${host}`
  return { ok: true, host, port, typed: port ? `${host}:${port}` : host, direct, explicit }
}

/** Whether a host is an IP address or this machine, which public DNS knows nothing about. */
export const isIp = (host) => IPV4.test(host) || host.includes(':') || host === 'localhost' || host.endsWith('.localhost')

/** The registrable domain of a host, roughly: the last two labels, or three under a short second level such as co.uk. */
export function registrable (host) {
  const labels = host.split('.')
  if (labels.length <= 2) return host
  const second = labels[labels.length - 2]
  const tld = labels[labels.length - 1]
  const n = tld.length === 2 && second.length <= 3 && labels.length >= 3 ? 3 : 2
  return labels.slice(-n).join('.')
}

/** The conventional dashboard origins for a game host, tried when it publishes no record. */
export function conventions (host, direct) {
  const domain = registrable(host)
  const out = [`https://seasons.${domain}`, `https://${domain}`]
  return [...new Set(out)].filter((o) => o !== direct && o !== `https://${host}`)
}

/** The TXT strings in a DNS-over-HTTPS JSON answer, each with its quoted pieces joined. */
export function txtRecords (json) {
  if (!json || !Array.isArray(json.Answer)) return []
  return json.Answer
    .filter((a) => a && a.type === 16 && typeof a.data === 'string')
    .map((a) => {
      const pieces = [...a.data.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1])
      return pieces.length ? pieces.join('') : a.data
    })
}

/** The target of the first SRV record in a DNS-over-HTTPS JSON answer, without its trailing dot. */
export function srvTarget (json) {
  if (!json || !Array.isArray(json.Answer)) return null
  const srv = json.Answer.find((a) => a && a.type === 33 && typeof a.data === 'string')
  const target = srv ? srv.data.trim().split(/\s+/)[3] : null
  return target ? target.replace(/\.$/, '').toLowerCase() : null
}

/** The dashboard origin a Seasons TXT record names, if it is one: v=seasons1; url=https://… */
export function dashboardFromRecords (records) {
  for (const r of records) {
    const t = String(r).trim()
    if (!t.startsWith('v=seasons1')) continue
    const m = /(?:^|;)\s*url=([^;\s]+)/.exec(t)
    if (!m) continue
    try {
      const u = new URL(m[1])
      if (u.protocol === 'https:' && !u.username && !u.password) return u.origin
    } catch {}
  }
  return null
}

/**
 * Whether a dashboard confirms it is for the address typed: 'yes' when its gameAddresses lists it
 * (or the SRV target players reach), 'no' when it lists others, 'unknown' when it lists none, as
 * dashboards before 1.4 don't.
 */
export function confirms (instance, addresses) {
  if (!Array.isArray(instance.gameAddresses)) return 'unknown'
  return addresses.some((a) => instance.gameAddresses.includes(a)) ? 'yes' : 'no'
}

/** What the public status service says about a game server: online, offline or unknown. */
export function serverStatus (json) {
  if (!json || typeof json.online !== 'boolean') return 'unknown'
  return json.online ? 'online' : 'offline'
}
