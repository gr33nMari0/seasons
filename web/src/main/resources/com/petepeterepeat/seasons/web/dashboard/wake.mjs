// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Keeps a second screen awake with the Screen Wake Lock API: asked for when the page opens and
// again whenever it comes back into view (the browser lets go of it while hidden), and after a
// refusal once more at the next tap, since Safari may want one. `onState` hears "on", "off",
// "refused" or "unsupported"; without the API the page carries on as it is.

export function keepAwake ({ navigator = globalThis.navigator, document = globalThis.document, onState = () => {} } = {}) {
  const api = navigator && navigator.wakeLock
  if (!api || typeof api.request !== 'function') {
    onState('unsupported')
    return { release () {} }
  }
  let sentinel = null
  let asking = false
  let stopped = false

  const ask = async () => {
    if (stopped || asking || (sentinel && !sentinel.released)) return
    asking = true
    try {
      sentinel = await api.request('screen')
      onState('on')
      sentinel.addEventListener('release', () => {
        if (!stopped) onState('off')
      })
    } catch {
      sentinel = null
      onState('refused')
      document.addEventListener('pointerdown', ask, { once: true })
    } finally {
      asking = false
    }
  }

  const visible = () => {
    if (document.visibilityState === 'visible') ask()
  }
  document.addEventListener('visibilitychange', visible)
  ask()
  return {
    release () {
      stopped = true
      document.removeEventListener('visibilitychange', visible)
      if (sentinel && !sentinel.released) sentinel.release()
    }
  }
}
