// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// The server's clock in the browser: every answer carries the server's "now", so countdowns are
// right even when the device's own clock is wrong.

/** A clock that runs at the server's time, given the device's clock as `localNow`. */
export function serverClock (localNow = () => Date.now()) {
  let offset = 0
  return {
    /** Sets the clock from a server's "now", sent when the device's clock said `localAt`. */
    sync (serverIso, localAt = localNow()) {
      const server = Date.parse(serverIso)
      if (!Number.isNaN(server)) offset = server - localAt
    },
    /** The server's time now, in milliseconds since the epoch. */
    now () {
      return localNow() + offset
    },
    /** Milliseconds until a server time given as ISO 8601. */
    until (iso) {
      return Date.parse(iso) - (localNow() + offset)
    }
  }
}
