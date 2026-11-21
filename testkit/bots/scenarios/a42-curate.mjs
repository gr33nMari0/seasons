// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// A42. Every curation action needs a reason, writes an audit entry and tells the owner. A curator
// opens an archive with /hof curate, clicks an item, picks an action and types the reason in chat,
// where nobody else sees it; cancelling leaves the item where it was. Return puts it on the
// owner's Returned page, Take in the curator's inventory without the archived tag, Museum on a
// free plinth with a plaque, and Destroy in the recycle bin with an id for /hof curate undo. Each
// is audited with the item, owner, season, slot, destination, curator and reason; the owner, who
// is online, is told at once with the reason; the admin webhook gets the whole entry, and the
// public channel a line without the reason.
import { assert } from '../lib/context.mjs'
import { GEAR_ITEMS, archiveWithGear, auditText, choose, curate, openCurate, pick, reason } from '../lib/curate.mjs'
import { PLINTHS, plaqueText, shownItem, writeMuseum } from '../lib/museum.mjs'
import { carried } from '../lib/vault.mjs'

export default {
  name: 'a42-curate',
  async run (ctx) {
    const admin = await ctx.admin()
    const owner = await ctx.join('Owner')
    const watcher = await ctx.join('Watcher')
    const discord = await ctx.webhook()
    const alerts = await ctx.webhook({ path: 'admin' })
    ctx.writeFile('plugins/Seasons/secrets.yml', `schema-version: 1\ndiscordWebhook: "${discord.url}"\nadminWebhook: "${alerts.url}"\n`)
    await ctx.command(admin, '/season reload', /^Reloaded /)
    await archiveWithGear(ctx, admin, ['Owner'])
    await writeMuseum(ctx, admin)
    await ctx.command(admin, '/clear SeasonsAdmin', /./)
    const seen = ctx.quiet(watcher, /Wrong season|Needed for repairs|For the museum|Broken/)

    // Cancelling leaves the item in the archive.
    await openCurate(ctx, admin, 'Owner')
    await pick(ctx, admin, 'diamond_sword')
    await choose(ctx, admin, 'take')
    await reason(ctx, admin, 'cancel', /^Cancelled: 1 × minecraft:diamond_sword stays in the archive\.$/)
    assert((await ctx.archive(admin, 'Owner', 2)).items === GEAR_ITEMS, 'nothing left the archive')
    assert(await carried(ctx, admin, 'SeasonsAdmin', 'diamond_sword') === 0, 'the curator took nothing')

    // Return to owner: the Returned page, and the owner told with the reason.
    const told = ctx.waitForMessages(owner, /^A curator returned 1 × minecraft:diamond_sword from your Season 2 archive to your vault's Returned page: Wrong season$/)
    await curate(ctx, admin, 'Owner', 'diamond_sword', 'return', 'Wrong season', /^Returned 1 × minecraft:diamond_sword to Owner's vault\.$/)
    await told
    let record = await ctx.archive(admin, 'Owner', 2)
    assert(record.items === GEAR_ITEMS - 1 && record.returned === 1, `the sword moved to the Returned page: ${JSON.stringify(record)}`)
    await alerts.waitFor(/^Curation: SeasonsAdmin returned 1 × minecraft:diamond_sword from Owner's Season 2 archive \(storage\.0\) to Owner's Returned page\. Reason: Wrong season$/)
    await discord.waitFor(/^1 × minecraft:diamond_sword from Owner's Season 2 archive was returned to them\.$/)

    // Take: the real item, without the archived tag.
    const toldTake = ctx.waitForMessages(owner, /^A curator took 64 × minecraft:dirt from your Season 2 archive: Needed for repairs$/)
    await curate(ctx, admin, 'Owner', 'dirt', 'take', 'Needed for repairs', /^Took 64 × minecraft:dirt\.$/)
    assert(await carried(ctx, admin, 'SeasonsAdmin', 'dirt') === 64, 'the curator has the dirt')
    assert(!/seasons:archived/.test(await ctx.entityData(admin, 'SeasonsAdmin', 'Inventory')), 'it carries no archived tag')
    await toldTake
    await alerts.waitFor(/^Curation: SeasonsAdmin took 64 × minecraft:dirt from Owner's Season 2 archive \(storage\.1\) to SeasonsAdmin's inventory\. Reason: Needed for repairs$/)

    // Museum: straight onto a free plinth, with a plaque crediting the owner.
    const toldMuseum = ctx.waitForMessages(owner, /^A curator put 32 × minecraft:torch from your Season 2 archive in the museum: For the museum$/)
    await curate(ctx, admin, 'Owner', 'torch', 'museum', 'For the museum', /^Put 32 × minecraft:torch in museum:core\.$/)
    await ctx.eventually(async () => assert(await shownItem(ctx, admin, PLINTHS.core) === 'minecraft:torch', 'the plinth shows the torches'))
    assert(/Season 2 · Owner/.test(await plaqueText(ctx, admin, PLINTHS.core)), 'the plaque credits the owner')
    assert(await carried(ctx, admin, 'SeasonsAdmin', 'torch') === 0, 'the torches never passed through the curator')
    await toldMuseum

    // Destroy: into the recycle bin, with an id to undo it by.
    const toldDestroy = ctx.waitForMessages(owner, /^A curator removed 1 × minecraft:iron_helmet from your Season 2 archive: Broken$/)
    const destroyed = await curate(ctx, admin, 'Owner', 'iron_helmet', 'destroy', 'Broken', /^Destroyed 1 × minecraft:iron_helmet\. \/hof curate undo \S+ brings it back within 30 days\.$/)
    const id = /undo (\S+) brings/.exec(destroyed)[1]
    await toldDestroy
    await alerts.waitFor(new RegExp(`^Curation: SeasonsAdmin destroyed 1 × minecraft:iron_helmet from Owner's Season 2 archive \\(armour\\.3\\) to the recycle bin as ${id}\\. Reason: Broken$`))
    record = await ctx.archive(admin, 'Owner', 2)
    assert(record.items === GEAR_ITEMS - 1 - 64 - 32 - 1, `four stacks left the archive: ${JSON.stringify(record)}`)

    // Audited: action, owner, curator, slot, destination and reason.
    const audit = await auditText(ctx, admin, 'Owner')
    for (const line of [
      /SeasonsAdmin curate return Owner \(ok\): Season 2, storage\.0, 1 × minecraft:diamond_sword to Owner's Returned page; reason: Wrong season; item: \S+/,
      /SeasonsAdmin curate take Owner \(ok\): Season 2, storage\.1, 64 × minecraft:dirt to SeasonsAdmin's inventory; reason: Needed for repairs/,
      /SeasonsAdmin curate museum Owner \(ok\): Season 2, storage\.13, 32 × minecraft:torch to museum:core; reason: For the museum/,
      new RegExp(`SeasonsAdmin curate destroy Owner \\(ok\\): Season 2, armour\\.3, 1 × minecraft:iron_helmet to the recycle bin as ${id}; reason: Broken`)
    ]) {
      assert(line.test(audit), `the audit has ${line}: ${audit}`)
    }

    // Reasons stay off the public channel and out of everyone else's chat.
    await ctx.sync(admin, [watcher, owner])
    seen.verify()
    const posted = discord.posts.map((p) => p.content).join('\n')
    assert(!/Wrong season|Needed for repairs|For the museum|Broken/.test(posted), `no reason on the public channel: ${posted}`)
    assert(discord.posts.every((p) => p.allowed_mentions?.parse?.length === 0), 'no post can ping anyone')
  }
}
