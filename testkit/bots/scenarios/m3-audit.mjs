// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// /season audit [player] (questions 173): the last entries of the audit log, oldest first, and with
// a player only theirs; only config admins.
import { assert } from '../lib/context.mjs'

const LINE = /^\d{4}-\d\d-\d\d \d\d:\d\d \S+ \S+ /

export default {
  name: 'm3-audit',
  async run (ctx) {
    const admin = await ctx.admin()
    const pat = await ctx.join('Pat')
    await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
    await ctx.command(admin, '/season marker add hubs Spawn Hub', /^Added /)
    await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
    await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

    const all = await ctx.command(admin, '/season audit', [/^The last \d+ audit entries:$/, LINE, LINE, LINE])
    const text = all.join('\n')
    assert(/ SeasonsAdmin config markers\.yml /.test(text), `the marker edit is listed: ${text}`)
    assert(/ Seasons archive Pat /.test(text), `Pat's archive is listed: ${text}`)

    const pats = await ctx.command(admin, '/season audit Pat', [/^The last \d+ audit entries for Pat:$/, LINE])
    assert(pats.slice(1).every((line) => / Pat/.test(line)), `only Pat's entries: ${pats.join('\n')}`)
    await ctx.command(admin, '/season audit Nobody', /^No audit entries for Nobody\.$/)
    await ctx.command(pat, '/season audit', /^(Unknown or incomplete command|Incorrect argument for command)/)
  }
}
