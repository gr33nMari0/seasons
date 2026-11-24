---
title: FAQ
description: Answers to the questions server owners and players ask most.
art: logo
audience: everyone
---

## For server owners

### Do players need to install anything?
No. Seasons is a server-side Paper plugin; players join with an unmodified game.

### Which versions does it support?
Paper 26.3, or a fork that runs Paper plugins such as Purpur, on Java 25. Spigot and Folia aren't supported. Each release names the versions it supports on the [download page](/download/).

### Will it delete parts of my world?
Never. The old world is locked by zones you draw, not wiped, and opens again at Homecoming. Seasons doesn't trim or regenerate chunks. (To freshen the End yourself, see [Regenerating the End](/docs/guides/end-regeneration/).)

### Can I install it partway through a season?
Yes. It sits idle until you record the season you're in with `/season init <season>`; the first `/season end` then archives that season.

### Can I keep my existing Hall of Fame?
Yes. Keep a hand-built gallery exactly as it is as a legacy gallery, or convert it with `/hof import`. See [Importing a hand-built Hall](/docs/guides/import-a-hall/).

### What if the server crashes mid-rollover?
Every item move is journalled. On the next start Seasons finishes or undoes each one, so every item exists exactly once. See [Item safety](/docs/owners/safety/).

### Does the season end by itself?
No. An admin runs `/season end` at a live event, so nobody is cut off mid-adventure. Set `schedule.autoEnd: true` if you want a hard stop.

### Does it run votes?
No. Decide in Discord or however your group likes, then set the date with `/season schedule end`.

### Is there a web dashboard?
Yes: [Seasons Web](/docs/owners/seasons-web/), an optional second plugin, serves a live dashboard from your own server.

### Does it work on Fabric or NeoForge?
Not yet. The core is platform-neutral, and adapters for Fabric and NeoForge are on the roadmap.

### Can I use it commercially?
Seasons is source-available under the PolyForm Noncommercial License 1.0.0: free for any noncommercial use, such as your community's server. Ask for a commercial licence.

## For players

### Do I lose my stuff at the end of a season?
It goes on display in your Hall of Fame cell forever, view-only. Your ender chest waits in your [vault](/docs/players/your-vault/) until Homecoming, and your bases wait in the old world.

### Can I get my old gear back?
Archives are view-only forever. A curator can return a single item to you, and every return is logged and announced.

### I was offline when the season ended. What happens?
You're archived the moment you next log in, then sent to your cell or the new spawn. Nobody is skipped.

### Can I bring my pets?
Yes, up to five: `/pet add` while looking at one. See [Pets](/docs/owners/pets/).

### Why can't I use /hof here?
`/hof` needs a bed within 3 blocks, no monsters near and no recent damage. The message says which check failed.
