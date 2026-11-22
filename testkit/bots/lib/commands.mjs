// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Using Seasons' commands in scenarios: tab completion as the client asks for it, help lines and
// the console stand-in (questions 264 to 268).
import { assert } from './context.mjs'

/** What the server suggests for the last word of `text`, as plain strings. */
export async function suggestions (bot, text) {
  const matches = await bot.tabComplete(text, true, false)
  return matches.map((m) => (typeof m === 'string' ? m : m.match))
}

/** Checks the server suggests every one of `expected` for `text`, and returns all it suggested. */
export async function suggests (bot, text, expected) {
  const found = await suggestions(bot, text)
  for (const want of expected) {
    assert(found.includes(want), `"${text}" suggests ${want}: ${JSON.stringify(found)}`)
  }
  return found
}

/** Runs a command as the console (/scenario console) and resolves with the lines matching `expect`. */
export function asConsole (ctx, admin, command, expect) {
  const patterns = (Array.isArray(expect) ? expect : [expect]).map((p) => new RegExp(`^Console: ${p.source.replace(/^\^/, '')}`))
  return ctx.command(admin, `/scenario console ${command}`, Array.isArray(expect) ? patterns : patterns[0])
}
