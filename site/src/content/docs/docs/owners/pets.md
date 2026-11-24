---
title: Pets
description: Registering tamed pets to travel to the next season's spawn, the limits, and what happens at launch.
art: house
audience: everyone
sidebar:
  order: 11
---

Players can bring tamed animals into the new season. Everything else alive stays in the old world.

- **Who can travel:** tamed animals the player owns: wolves, cats, parrots, horses, donkeys, mules, llamas and any other tameable mob. Wild mobs, villagers and wardens can never be registered.
- **Registering:** look at your pet within 5 blocks and run `/pet add`. `/pet list` shows your pets; `/pet remove <name>` takes one off. Up to `pets.maxPerPlayer` (5 by default). Unnamed pets get a name from their type, such as "Wolf 2". Registration closes when the season ends.
- **No smuggling:** a donkey, mule or llama with a chest can't be registered, and one that has a chest when it's time to travel stays behind (its owner is told to remove the chest). Saddles and horse armour travel; nothing else carried does.
- **Travel:** when the owner reaches the new spawn, at launch or at their first login after it, each registered pet is moved next to them, wherever it was.
- A pet that dies, or can't be found at travel time, is reported to its owner and dropped. Registrations clear after travel, so each season starts with a fresh list. Pets are never archived.

Turn pets off with `pets.enabled: false`.
