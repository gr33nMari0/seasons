// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Overworld and Nether coordinates: one Nether block is eight in the Overworld. Floor division, as
// the game rounds, so -100 in the Overworld is -13 in the Nether, not -12.

/** The Nether's x or z for an Overworld one. */
export function toNether (n) {
  return Math.floor(n / 8)
}

/** The Overworld's x or z for a Nether one. */
export function toOverworld (n) {
  return n * 8
}

/** A whole number typed into a box, or null for anything else. */
export function parseCoordinate (text) {
  const t = String(text).trim()
  return /^[-+]?\d{1,8}$/.test(t) ? Number(t) : null
}
