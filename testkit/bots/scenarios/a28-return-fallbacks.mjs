// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A28. If lava was poured on the saved spot while the player was away, /hof leave puts them at
// their bed; if the bed is unsafe too, the nearest safe block within 8 blocks; failing that, the
// season spawn.
import { assert } from '../lib/context.mjs'
import { FLOOR } from '../lib/hall.mjs'
import { HOME, bed, hallToVisit, hof, inGallery, near, standAt, waitInHall } from '../lib/visits.mjs'

/** The respawn bed, well away from HOME. */
const BED = { x: HOME.x + 20, y: FLOOR, z: HOME.z }

export default {
  name: 'a28-return-fallbacks',
  async run (ctx) {
    const admin = await ctx.admin()
    const visitor = await ctx.join('Visitor')
    await hallToVisit(ctx, admin)
    // The world spawn, pinned: players join anywhere within the spawn radius around it, so where
    // a bot joined says nothing about where the spawn is.
    await ctx.command(admin, `/setworldspawn 0 ${FLOOR} 0`, /^Set the world spawn point to /)
    const spawn = { x: 0.5, y: FLOOR, z: 0.5 }
    await ctx.command(admin, '/effect give Visitor minecraft:fire_resistance 600 0 true', /^Applied effect /)
    await bed(ctx, admin, { x: HOME.x + 1, y: FLOOR, z: HOME.z })
    await bed(ctx, admin, BED)
    await ctx.command(admin, `/spawnpoint Visitor ${BED.x} ${BED.y} ${BED.z}`, /^Set spawn point to /)

    // Lava on the saved spot: their bed.
    await standAt(ctx, admin, 'Visitor', HOME)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitInHall(ctx, admin, 'Visitor')
    await ctx.command(admin, `/setblock ${HOME.x} ${HOME.y} ${HOME.z} minecraft:lava`, /^Changed the block/)
    await hof(ctx, visitor, '/hof leave', /^Your spot wasn't safe \(lava\), so you're at your bed\.$/)
    await ctx.eventually(async () => assert(near(await ctx.position(admin, 'Visitor'), { x: BED.x + 0.5, y: FLOOR, z: BED.z + 0.5 }, 3), 'at their bed'))

    // Lava on the saved spot and at the bed: the nearest safe block within 8.
    // Glass round the saved spot keeps its lava from flowing, so the nearest safe block stays put.
    const spot = { x: HOME.x, y: FLOOR, z: HOME.z + 30 }
    await bed(ctx, admin, { x: spot.x, y: FLOOR, z: spot.z + 3 })
    await standAt(ctx, admin, 'Visitor', spot)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitInHall(ctx, admin, 'Visitor')
    await ctx.command(admin, `/fill ${spot.x - 1} ${FLOOR} ${spot.z - 1} ${spot.x + 1} ${FLOOR} ${spot.z + 1} minecraft:glass`, /^Successfully filled /)
    await ctx.command(admin, `/setblock ${spot.x} ${spot.y} ${spot.z} minecraft:lava`, /^Changed the block/)
    await ctx.command(admin, `/fill ${BED.x - 2} ${FLOOR} ${BED.z - 2} ${BED.x + 2} ${FLOOR} ${BED.z + 2} minecraft:lava replace minecraft:air`, /^Successfully filled /)
    await hof(ctx, visitor, '/hof leave', /^Your spot wasn't safe \(lava\), so you're at the nearest safe spot\.$/)
    await ctx.eventually(async () => {
      const at = await ctx.position(admin, 'Visitor')
      assert(near(at, { x: spot.x + 0.5, y: FLOOR, z: spot.z + 0.5 }, 8.8) && !inGallery(at), `within 8 blocks of the saved spot: ${JSON.stringify(at)}`)
      assert(!await ctx.holds(admin, `block ${Math.floor(at.x)} ${Math.floor(at.y)} ${Math.floor(at.z)} minecraft:lava`), 'not standing in lava')
    })

    // Nothing safe within 8 blocks either: the season spawn (Season 2 has none, so the world's).
    const last = { x: HOME.x - 30, y: FLOOR, z: HOME.z }
    await bed(ctx, admin, { x: last.x + 1, y: FLOOR, z: last.z })
    await standAt(ctx, admin, 'Visitor', last)
    await hof(ctx, visitor, '/hof', /^Welcome to the Hall of Fame\./)
    await waitInHall(ctx, admin, 'Visitor')
    await ctx.command(admin, `/fill ${last.x - 9} ${FLOOR} ${last.z - 9} ${last.x + 9} ${FLOOR} ${last.z + 9} minecraft:lava`, /^Successfully filled /)
    await hof(ctx, visitor, '/hof leave', /^Your spot wasn't safe \(lava\), so you're at the season spawn\.$/)
    await ctx.eventually(async () => assert(near(await ctx.position(admin, 'Visitor'), spawn, 2), 'at the spawn'))
  }
}
