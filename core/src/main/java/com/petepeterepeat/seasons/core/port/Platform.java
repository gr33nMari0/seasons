// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

/** Every port one adapter provides, handed to the core at startup. */
public record Platform(
    String name,
    Capabilities capabilities,
    Players players,
    Inventories inventories,
    Advancements advancements,
    World world,
    Entities entities,
    Displays displays,
    Menus menus,
    Commands commands,
    Permissions permissions,
    Events events,
    Scheduler scheduler,
    DragonFight dragonFight,
    Statistics statistics,
    Storage storage,
    Imports imports) {}
