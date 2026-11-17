# Paper API used by Seasons

Every Paper (and Bukkit, Adventure, Brigadier) API the Seasons plugins call, so each drop's API changelog can be checked against this list in a few minutes. Public API only: no server internals, no reflection, no paperweight-userdev. Update this file in the same commit as the code.

Compiled against `io.papermc.paper:paper-api` 26.3 (see `gradle/libs.versions.toml`).

## Seasons (`adapters/paper`)

| API | Used in | For |
| --- | --- | --- |
| `JavaPlugin#onEnable`, `#onDisable`, `#getPluginMeta().getVersion()`, `#getDataPath()`, `#getSLF4JLogger()` | `SeasonsPlugin`, `PaperStorage` | Lifecycle, version, config folder, logging |
| `paper-plugin.yml` (`dependencies.server`, `permissions`) | resources | Packaging, optional BlueMap, permission defaults |
| `ServicesManager#register`, `#unregisterAll` | `SeasonsPlugin` | Publishing `SeasonsApi` |
| `PluginManager#registerEvents` | `SeasonsPlugin` | Listeners |
| `LifecycleEventManager#registerEventHandler(LifecycleEvents.COMMANDS)`, `ReloadableRegistrarEvent#registrar()`, `Commands#register`, `Commands.literal` | `PaperCommands` | Brigadier commands |
| `CommandSourceStack#getSender`; Brigadier `LiteralArgumentBuilder#requires`, `#executes`, `#then`, `Command.SINGLE_SUCCESS` | `PaperCommands` | Command tree, permission checks per node |
| `Permissible#hasPermission` | `PaperCommands`, `PaperPermissions` | Permission checks |
| `Server#getOnlinePlayers`, `#getPlayer(UUID)`, `#getEntity(UUID)`, `#getWorlds`, `#advancementIterator`, `#createInventory(InventoryHolder, int, Component)` | ports | Lookups |
| `Server#getGlobalRegionScheduler` (`execute`, `runDelayed`), `#getRegionScheduler().execute`, `Entity#getScheduler().run` | `PaperScheduler` | Folia-compatible scheduling |
| `Audience#sendMessage(Component)`; Adventure `MiniMessage#deserialize`, `TagResolver`, `Placeholder.unparsed` | `PaperText`, ports | Messages with literal placeholders |
| `PlayerInventory#getStorageContents`, `#getArmorContents`, `#getItemInOffHand`, `#setStorageContents`, `#setArmorContents`, `#setItemInOffHand`, `#clear`; `HumanEntity#getEnderChest`, `#setItemOnCursor`, `#getOpenInventory().getTopInventory()` | `PaperInventories` | Capturing and restoring gear |
| `ItemStack#serializeAsBytes`, `ItemStack.deserializeBytes`, `ItemStack#isEmpty`, `ItemStack.empty` | `PaperItemCodec` | Neutral item data with its DataVersion |
| `Advancement#getDisplay`, `#getKey`, `Player#getAdvancementProgress`, `AdvancementProgress#getAwardedCriteria` | `PaperAdvancements` | Visible advancements and progress |
| `NamespacedKey.fromString`, `NamespacedKey.minecraft`, `Server#getWorld(NamespacedKey)`, `World#getKey`, `#getSpawnLocation`, `#getChunkAtAsync(int, int, boolean)` | `PaperPositions`, `PaperWorld` | Dimensions, spawn, chunk loading |
| `Entity#teleportAsync(Location, TeleportCause)`, `#getLocation`, `#remove`, `#getPersistentDataContainer`; `Tameable#getOwnerUniqueId` | `PaperEntities`, `PaperDisplays`, `PaperEvents` | Moving entities, tamed owners |
| `World#spawn(Location, Class, Consumer)`, `TextDisplay#text`, `Entity#setPersistent`, `PersistentDataContainer#set`, `#has` | `PaperDisplays` | Tagged text displays |
| `InventoryHolder`, `Inventory#getHolder(boolean)`, `InventoryClickEvent`, `InventoryDragEvent`, `InventoryMoveItemEvent`, `Player#updateInventory`, `HumanEntity#openInventory` | `PaperMenus` | View-only menus |
| `BlockBreakEvent`, `BlockPlaceEvent`, `Block#getLocation`, `Material#getKey`, `Cancellable#setCancelled` | `PaperEvents` | Intents and decisions |
| `World#getEnderDragonBattle`, `DragonBattle#setPreviouslyKilled`, `#getEndPortalLocation` | `PaperDragonFight` | The dragon fight |
| `Statistic#values`, `#getType`, `#getKey`, `Player#getStatistic(Statistic)` | `PaperStatistics` | General-tab statistics |
| `World#getWorldFolder` | `PaperStorage` | World data folder |

## Seasons Web (`web`)

| API | Used in | For |
| --- | --- | --- |
| `ServicesManager#getRegistration`, `RegisteredServiceProvider#getProvider` | `SeasonsWebPlugin` | Finding `SeasonsApi` |
| `PluginManager#disablePlugin` | `SeasonsWebPlugin` | Disabling itself without Seasons |

## Test plugin (`testkit`, never shipped)

| API | For |
| --- | --- |
| `ServerLoadEvent`, `PlayerJoinEvent`, `Player#setOp`, `Server#getPort`, `Server#shutdown`, `PluginManager#isPluginEnabled` | Driving the scenario run |
| `ItemStack.of`, `#addUnsafeEnchantment`, `#editMeta`, `ItemMeta#customName`, `BlockStateMeta#getBlockState`, `#setBlockState`, `ShulkerBox#getInventory` | Item samples for the NBT neutrality test |
