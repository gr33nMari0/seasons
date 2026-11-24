// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The dashboard's API, on this same site, with the session cookie the browser keeps (HttpOnly: the
// page never sees it).

/** Resolves with { status, json } for a GET; json is null for an answer that isn't JSON. */
export async function get (path) {
  const res = await fetch(path, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
  let json = null
  try {
    json = await res.json()
  } catch {}
  return { status: res.status, json, retryAfter: Number(res.headers.get('Retry-After')) || 0 }
}

/** Resolves with { status, json } for a POST with no body. */
export async function post (path) {
  const res = await fetch(path, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' } })
  let json = null
  try {
    json = await res.json()
  } catch {}
  return { status: res.status, json, retryAfter: Number(res.headers.get('Retry-After')) || 0 }
}

/** Resolves with { status, text } for a GET of a text file, such as a bundled guide. */
export async function getText (path) {
  const res = await fetch(path, { credentials: 'same-origin', headers: { Accept: 'text/markdown, text/plain' } })
  return { status: res.status, text: res.ok ? await res.text() : '' }
}
