// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The event feed's stream, kept open: when it drops (a restart, a network change, a proxy) it is
// opened again after 1 s, then 2, 4 and so on up to 30 s between tries, and `onOpen` is told each
// time it opens, so the page can catch up on what it missed.

export const TYPES = ['join', 'first', 'dragon', 'zone', 'phase', 'homecoming', 'archived']

/** The wait before try `n` (0 for the first retry): 1 s doubling, at most 30 s. */
export function backoff (n) {
  return Math.min(30_000, 1000 * 2 ** Math.max(0, n))
}

/**
 * Opens the stream at `url` and keeps it open until close().
 *
 * @param onEvent called with (type, data) for every event
 * @param onOpen called each time the stream opens, with whether it had dropped before
 */
export function keepOpen ({ url, onEvent, onOpen = () => {}, EventSourceImpl = globalThis.EventSource, timers = globalThis }) {
  let source = null
  let tries = 0
  let dropped = false
  let closed = false
  let timer = null

  const open = () => {
    if (closed) return
    source = new EventSourceImpl(url)
    source.addEventListener('open', () => {
      tries = 0
      onOpen(dropped)
    })
    for (const type of TYPES) {
      source.addEventListener(type, (e) => {
        let data
        try {
          data = JSON.parse(e.data)
        } catch {
          return
        }
        onEvent(type, data)
      })
    }
    source.addEventListener('error', () => {
      // The browser's own retry would give up after a refusal; this one doesn't.
      source.close()
      if (closed) return
      dropped = true
      timer = timers.setTimeout(open, backoff(tries++))
    })
  }
  open()
  return {
    close () {
      closed = true
      if (timer !== null) timers.clearTimeout(timer)
      if (source) source.close()
    }
  }
}
