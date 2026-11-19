// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// @sessions 2
// The armour-stand figure (questions 170) and a figure's pose (171): a gallery with
// renderer: armour-stand draws an armour stand with arms, in the player's head, every slot locked,
// with the helmet on the cell's trophy anchor; a visitor takes nothing from it. Switched back to
// mannequins with c1 posed crouching, /hof reconcile draws a crouching mannequin in its place, as
// players see it, and after a restart it still crouches with nothing to repair.
import { assert } from '../lib/context.mjs'
import { FLOOR, at, buildGallery, selectorData, visit } from '../lib/hall.mjs'
import { rightClick, seen } from '../lib/protection.mjs'

const FILE = 'world/dimensions/minecraft/overworld/seasons/galleries/season-2.yml'

/** The entity metadata index of an entity's pose, and the protocol's value for crouching. */
const POSE = 6
const CROUCHING = 5

/** Resolves once the bot sees the mannequin at c1's figure anchor crouching. */
async function crouching (ctx, bot, figure) {
  await ctx.eventually(async () => {
    const pose = seen(bot, 'mannequin', figure).metadata[POSE]
    assert(pose === CROUCHING, `${bot.username} sees the mannequin crouching: pose ${pose}`)
  })
}

export default {
  name: 'm1-armour-stand',
  sessions: [session1, session2]
}

async function session1 (ctx) {
  const admin = await ctx.admin()
  await ctx.join('Owner')
  const visitor = await ctx.join('Visitor')
  await ctx.command(admin, '/season init 2', /^Recorded Season 2 /)
  const [c1] = await buildGallery(ctx, admin, { cells: 3 })
  const trophy = { x: c1.x0 + 1, y: FLOOR, z: c1.z0 + 6 }
  await ctx.command(admin, `/hof cell anchor ${c1.key} trophy ${trophy.x} ${trophy.y} ${trophy.z}`, /^Added a trophy anchor/)
  ctx.writeFile(FILE, ctx.readFile(FILE).replace(/^cells:/m, 'renderer: armour-stand\ncells:'))
  await ctx.command(admin, '/season reload', /^Reloaded /)
  await ctx.command(admin, `/hof cell assign ${c1.key} Owner`, /^Assigned /)
  await ctx.command(admin, '/item replace entity Owner armor.head with minecraft:iron_helmet', /^Replaced /)
  await ctx.command(admin, '/item replace entity Owner armor.chest with minecraft:diamond_chestplate', /^Replaced /)

  await ctx.command(admin, '/season end --now', /^Ending Season 2 now/)
  await ctx.waitForMessages(admin, /^Season 2 has ended\./, 20_000)

  const stand = at('minecraft:armor_stand', c1.figure)
  await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${stand}`), 'an armour stand stands at the figure anchor'))
  assert(await selectorData(ctx, admin, stand, 'equipment.head.id') === '"minecraft:player_head"', 'it wears the player head')
  assert(await selectorData(ctx, admin, stand, 'equipment.chest.id') === '"minecraft:diamond_chestplate"', 'and the chestplate')
  assert(await selectorData(ctx, admin, stand, 'ShowArms') === '1b', 'with arms')
  assert(await selectorData(ctx, admin, stand, 'Invulnerable') === '1b', 'invulnerable')
  assert(!await ctx.holds(admin, `entity ${at('minecraft:mannequin', c1.figure)}`), 'no mannequin')
  const helmet = await selectorData(ctx, admin, at('minecraft:item_display', trophy), 'item.id')
  assert(helmet === '"minecraft:iron_helmet"', `the helmet is on the trophy anchor: ${helmet}`)

  // A visitor right-clicking the stand takes nothing.
  await visit(ctx, admin, 'Visitor', c1)
  await rightClick(ctx, visitor, seen(visitor, 'armor_stand', c1.figure))
  assert(await ctx.itemCount(admin, 'Visitor') === 0, 'the visitor took nothing')
  assert(await selectorData(ctx, admin, stand, 'equipment.chest.id') === '"minecraft:diamond_chestplate"', 'the stand keeps its gear')

  // Mannequins again, c1 crouching: reconciliation draws the right figure.
  ctx.writeFile(FILE, ctx.readFile(FILE).replace(/^renderer: armour-stand\n/m, '').replace(/^( +)c1:$/m, '$1c1:\n$1$1pose: crouching'))
  await ctx.command(admin, '/season reload', /^Reloaded /)
  await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: /)
  const mannequin = at('minecraft:mannequin', c1.figure)
  await ctx.eventually(async () => assert(await ctx.holds(admin, `entity ${mannequin}`), 'a mannequin is drawn in its place'))
  assert(!await ctx.holds(admin, `entity ${stand}`), 'the armour stand is gone')
  await crouching(ctx, visitor, c1.figure)
  await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: 0 repairs\.$/)
}

async function session2 (ctx) {
  const admin = await ctx.admin()
  const visitor = await ctx.join('Visitor')
  const c1 = { figure: { x: 22, y: FLOOR, z: 28 } }
  await crouching(ctx, visitor, c1.figure)
  await ctx.command(admin, '/hof reconcile season-2', /^Reconciled season-2: 0 repairs\.$/)
}
