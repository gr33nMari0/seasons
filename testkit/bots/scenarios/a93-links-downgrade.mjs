// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A93. Going back, and the old commands. hall.yml's frozen 1.2 links are carried through a hub edit
// and never changed by link edits, so the previous Seasons still reads them; an edit made to them
// while running the previous jar is reported by /hof hall check and taken by upgrade import
// --replace, which /hof undo puts back; forget-old clears them. The 1.2 forms of add, area, to and
// move reply word for word as before, and a link on a stone tile made that way fires underfoot. A
// holder of only seasons.admin.hof runs link commands (M8 plan, Upgrading existing setups).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HALL_FILE, corners, HUB } from '../lib/hub.mjs'
import { FOLLOWED, linkInFile, setBlock } from '../lib/links.mjs'
import { permit } from '../lib/zones.mjs'
import { hallToVisit, middle, standAt, waitNear } from '../lib/visits.mjs'

const SPOT = { x: 150, y: FLOOR, z: 160 }
const TILE = { x: 14, y: FLOOR - 1, z: 24 }
const OLD = `links:
  old:
    pos: [16, -60, 29]
    to:
      season: 3
`

export default {
  name: 'a93-links-downgrade',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      await hallToVisit(ctx, admin, { sealed: null })
      ctx.writeFile(HALL_FILE, ctx.readFile(HALL_FILE).trimEnd() + '\n' + OLD)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const visitor = await ctx.join('Visitor')
      await ctx.command(admin, '/forceload add 140 150 160 170', /^(Marked|No chunks were marked)/)
      assert(linkInFile(ctx, 'old'), 'the 1.2 link was copied')

      // Link edits never touch hall.yml; a hub edit carries the frozen links through.
      const before = ctx.readFile(HALL_FILE)
      await ctx.command(admin, '/hof link to old season 2', /^Link old now leads to Season 2's spawn\.$/)
      assert(ctx.readFile(HALL_FILE) === before, 'hall.yml is untouched by a link edit')
      await ctx.command(admin, `/hof hall hub ${corners(HUB)}`, /^The Hall's hub now spans /)
      assert(/season: 3/.test(ctx.readFile(HALL_FILE)), `hall.yml keeps its frozen link: ${ctx.readFile(HALL_FILE)}`)

      // The previous jar changed the frozen link: reported, then taken on request, and undone.
      ctx.writeFile(HALL_FILE, ctx.readFile(HALL_FILE).replace('season: 3', 'season: 1'))
      await ctx.command(admin, '/season reload', /^Reloaded /)
      await ctx.command(admin, '/hof hall check', /^hall\.yml's links from before links\.yml differ from links\.yml: /)
      await ctx.command(admin, '/hof link upgrade import --replace', /^Copied 1 link from hall\.yml into links\.yml\.$/)
      assert(linkInFile(ctx, 'old').to.season === 1, 'the edit was taken')
      await ctx.command(admin, '/hof undo', /^Put links\.yml back as it was before your last edit\.$/)
      assert(linkInFile(ctx, 'old').to.season === 2, 'and undone')
      await ctx.command(admin, '/hof link upgrade forget-old', /^hall\.yml no longer keeps its links from before links\.yml\.$/)
      assert(!/links:/.test(ctx.readFile(HALL_FILE)), 'hall.yml has no links now')

      // The 1.2 forms, word for word.
      await setBlock(ctx, admin, TILE, 'minecraft:stone')
      await ctx.command(admin, `/hof link add tile hall ${TILE.x} ${TILE.y} ${TILE.z}`, /^Link tile at 14, -61, 24 leads to the Hall spawn\.$/)
      await ctx.command(admin, `/hof link to tile ${SPOT.x} ${SPOT.y} ${SPOT.z} 90`, /^Link tile now leads to 150, -60, 160\.$/)
      await ctx.command(admin, `/hof link add door hall area 9 ${FLOOR} 27 9 ${FLOOR + 2} 29`, /^Link door, an area from 9, -60, 27 to 9, -58, 29, leads to the Hall spawn\.$/)
      await ctx.command(admin, `/hof link move tile ${TILE.x} ${TILE.y} ${TILE.z}`, /^Link tile is now at 14, -61, 24\.$/)
      assert(linkInFile(ctx, 'tile').triggers[0].both, 'a stone tile made the 1.2 way fires as 1.2 did')
      await standAt(ctx, admin, 'Visitor', { x: 16, y: FLOOR, z: 24 })
      await ctx.advance(admin, 6_000)
      const trip = ctx.waitForMessages(visitor, FOLLOWED)
      await standAt(ctx, admin, 'Visitor', { x: TILE.x, y: FLOOR, z: TILE.z })
      await trip
      await waitNear(ctx, admin, 'Visitor', middle(SPOT))

      // seasons.admin.hof alone is enough for the link commands.
      await permit(ctx, admin, 'Visitor', 'seasons.admin.hof')
      await ctx.command(visitor, '/hof link list', /^Link tile at 14, -61, 24: to 150, -60, 160$/)
      await ctx.command(visitor, '/hof link add v to hall', /^Link v leads to the Hall spawn, from no trigger/)
    }
  ]
}
