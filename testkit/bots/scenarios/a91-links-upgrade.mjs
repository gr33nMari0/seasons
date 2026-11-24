// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// A91. A hall.yml with links as 1.1 and 1.2 wrote it (a sign and a button to season spawns, a
// plate, a stone floor tile, a doorway area, a spot with a yaw, a link leading nowhere) is copied
// into links.yml at the next start: same ids, places and destinations, everyone may take them;
// the sign and button fire only on a click, the plate and the tile underfoot as before, the area
// as before; hall.yml is left exactly as it was; the startup log and the admin's join message say
// so; /hof link upgrade report lists each, and done stops the reminder; a visitor in the Hall across
// the restart goes back where they were (M8 plan, Upgrading existing setups).
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HALL_FILE, LINKS_FILE } from '../lib/hub.mjs'
import { FOLLOWED, linkInFile, setBlock } from '../lib/links.mjs'
import { HOME, hallToVisit, hof, middle, move, standAt, waitNear } from '../lib/visits.mjs'
import { click } from '../lib/wand.mjs'

const SPOT = { x: 150, y: FLOOR, z: 160 }
const SIGN = { x: 10, y: FLOOR, z: 21 }
const BUTTON = { x: 10, y: FLOOR, z: 23 }
const PLATE = { x: 14, y: FLOOR, z: 21 }
const TILE = { x: 14, y: FLOOR - 1, z: 24 }
const LINKS = `links:
  sign:
    pos: [10, -60, 21]
    to:
      pos: [150, -60, 160]
  button:
    pos: [10, -60, 23]
    to:
      pos: [150, -60, 160]
  plate:
    pos: [14, -60, 21]
    to:
      pos: [150, -60, 160]
  tile:
    pos: [14, -61, 24]
    to:
      pos: [150, -60, 160]
  door:
    area:
      min: [9, -60, 27]
      max: [9, -58, 29]
    to:
      pos: [150, -60, 160]
  spot:
    pos: [16, -60, 29]
    to:
      pos: [150, -60, 160]
      yaw: 90
  nowhere:
    pos: [17, -60, 29]
`
const EXPECTED = 'a91-hall.expected'

export default {
  name: 'a91-links-upgrade',
  sessions: [
    async (ctx) => {
      const admin = await ctx.admin()
      const visitor = await ctx.join('Visitor')
      await hallToVisit(ctx, admin, { sealed: null })
      await ctx.command(admin, '/season config set hof.entry.requireBed false', /^Set hof\.entry\.requireBed to false/)
      await ctx.command(admin, '/forceload add 140 150 160 170', /^(Marked|No chunks were marked)/)
      await setBlock(ctx, admin, SIGN, 'minecraft:oak_sign')
      await setBlock(ctx, admin, BUTTON, 'minecraft:stone_button[face=floor]')
      await setBlock(ctx, admin, PLATE, 'minecraft:stone_pressure_plate')
      await setBlock(ctx, admin, TILE, 'minecraft:polished_andesite')
      // hall.yml as 1.2 wrote it, with its links, and no links.yml yet.
      const hall = ctx.readFile(HALL_FILE).trimEnd() + '\n' + LINKS
      ctx.writeFile(HALL_FILE, hall)
      ctx.writeFile(EXPECTED, hall)
      // A visitor in the Hall across the restart.
      await standAt(ctx, admin, 'Visitor', HOME)
      await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    },
    async (ctx) => {
      const admin = await ctx.admin()
      const visitor = await ctx.join('Visitor')
      assert(/Links upgraded: 7 copied to links\.yml; 2 are now click-only/.test(ctx.readFile('logs/latest.log')), 'the startup log says how many')
      assert(ctx.readFile(HALL_FILE) === ctx.readFile(EXPECTED), 'hall.yml is exactly as it was')
      assert(ctx.readFile(LINKS_FILE).includes('upgraded'), 'links.yml notes the upgrade')
      for (const id of ['sign', 'button', 'plate', 'tile', 'door', 'spot', 'nowhere']) {
        const copied = linkInFile(ctx, id)
        assert(copied && copied.access === 'everyone', `${id} is copied, open to everyone: ${JSON.stringify(copied)}`)
      }
      assert(linkInFile(ctx, 'sign').triggers[0].use, 'the sign is click-only')
      assert(linkInFile(ctx, 'button').triggers[0].use, 'the button is click-only')
      assert(linkInFile(ctx, 'plate').triggers[0].both, 'the plate fires as before')
      assert(linkInFile(ctx, 'tile').triggers[0].both, 'the tile fires as before')
      assert(linkInFile(ctx, 'door').triggers[0].area, 'the area stays an area')
      assert(linkInFile(ctx, 'spot').to.yaw === 90, 'the spot keeps its yaw')
      assert(!linkInFile(ctx, 'nowhere').to, 'nowhere still leads nowhere')

      // The visitor goes back where they were.
      await hof(ctx, visitor, '/hof leave', /^Back where you left\.$/)
      await waitNear(ctx, admin, 'Visitor', middle(HOME))

      // The admin is told, and the report lists each with a way back.
      await ctx.command(admin, '/hof link upgrade report', [
        /^Link sign at 10, -60, 21: was clicked or stepped on, now clicked\.$/,
        /^Link button at 10, -60, 23: was clicked or stepped on, now clicked\.$/,
        /^Link plate at 14, -60, 21: clicked or stepped on, as before\.$/,
        /^Link door at 9, -60, 27: an area, as before\.$/
      ])
      await ctx.command(admin, '/hof link trigger kind button 1 both', /^Link button's trigger 1 is now the block at 10, -60, 23, clicked or stepped on\.$/)
      await ctx.command(admin, '/hof link upgrade done', /^The link upgrade is reviewed; admins aren't reminded any more\.$/)

      // Standing on the sign does nothing; the plate, the tile and the area work as before.
      await standAt(ctx, admin, 'Visitor', SIGN)
      await ctx.sync(admin, [visitor], 30)
      assert((await ctx.position(admin, 'Visitor')).x < 20, 'the sign took nobody')
      let trip = ctx.waitForMessages(visitor, FOLLOWED)
      await click(ctx, admin, 'Visitor', SIGN)
      await trip
      await waitNear(ctx, admin, 'Visitor', middle(SPOT))
      await ctx.advance(admin, 6_000)
      trip = ctx.waitForMessages(visitor, FOLLOWED)
      await standAt(ctx, admin, 'Visitor', PLATE)
      await trip
      await ctx.advance(admin, 6_000)
      trip = ctx.waitForMessages(visitor, FOLLOWED)
      await standAt(ctx, admin, 'Visitor', { x: TILE.x, y: FLOOR, z: TILE.z })
      await trip
      await ctx.advance(admin, 6_000)
      await standAt(ctx, admin, 'Visitor', { x: 11, y: FLOOR, z: 28 })
      trip = ctx.waitForMessages(visitor, FOLLOWED)
      await move(ctx, admin, 'Visitor', { x: 8.5, y: FLOOR, z: 28.5 }, 'walk')
      await trip
    }
  ]
}
