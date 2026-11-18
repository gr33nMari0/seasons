# Paper API used by Seasons

Every Paper (and Bukkit, Adventure, Brigadier) API the Seasons plugins call, so each drop's API changelog can be checked against this list in a few minutes. Public API only: no server internals, no reflection, no paperweight-userdev. Update this file in the same commit as the code.

Compiled against `io.papermc.paper:paper-api` 26.3 (see `gradle/libs.versions.toml`).

## Seasons (`adapters/paper`)

| API | Used in | For |
| --- | --- | --- |
| `JavaPlugin#onEnable`, `#onDisable`, `#getPluginMeta().getVersion()`, `#getDataPath()`, `#getSLF4JLogger()` | `SeasonsPlugin`, `PaperStorage` | Lifecycle, version, config folder, logging |
| `paper-plugin.yml` (`dependencies.server`, `permissions`) | resources | Packaging, optional BlueMap, permission defaults |
| `ServicesManager#register`, `#unregisterAll`, `ServicePriority` | `SeasonsPlugin` | Publishing `SeasonsApi`, and `ScenarioHooks` on scenario servers only (ADR 0003) |
| `NamespacedKey(Plugin, String)` | `SeasonsPlugin` | The `seasons:archived` key |
| `PluginManager#registerEvents`, `@EventHandler`, `EventPriority` | `SeasonsPlugin`, listeners | Listeners |
| `LifecycleEventManager#registerEventHandler(LifecycleEvents.COMMANDS)`, `ReloadableRegistrarEvent#registrar()`, `Commands#register`, `Commands.literal`, `Commands.argument` | `PaperCommands` | Brigadier commands |
| `CommandSourceStack#getSender`; Brigadier `ArgumentBuilder#requires`, `#executes`, `#then`, `#build`, `LiteralCommandNode`, `CommandContext#getArgument`, `IntegerArgumentType.integer`, `StringArgumentType.word`, `StringArgumentType.greedyString`, `Command.SINGLE_SUCCESS` | `PaperCommands` | Command tree, arguments, permission checks per node |
| `Permissible#hasPermission` | `PaperCommands`, `PaperPermissions` | Permission checks |
| `Server#getOnlinePlayers`, `#getPlayer(UUID)`, `#getEntity(UUID)`, `#getWorlds`, `#advancementIterator`, `#createInventory(InventoryHolder, int, Component)` | ports | Lookups |
| `Server#getGlobalRegionScheduler` (`execute`, `runDelayed`, `runAtFixedRate`), `ScheduledTask#cancel`, `#getRegionScheduler().execute`, `Entity#getScheduler().run` | `PaperScheduler` | Folia-compatible scheduling, the once-a-second season tick |
| `Audience#sendMessage(Component)`; Adventure `MiniMessage#deserialize`, `TagResolver`, `Placeholder.unparsed` | `PaperText`, ports | Messages with literal placeholders |
| `Server#broadcast(Component)`; Adventure `BossBar.bossBar`, `BossBar#name`, `#progress`, `Audience#showBossBar`, `#hideBossBar` (on `Server`) | `PaperPlayers` | Reminders and announcements, the end countdown bar |
| `PlayerInventory#getStorageContents`, `#getArmorContents`, `#getItemInOffHand`, `#setStorageContents`, `#setArmorContents`, `#setItemInOffHand`, `#clear`; `HumanEntity#getEnderChest`, `#getItemOnCursor`, `#setItemOnCursor`, `#getOpenInventory().getTopInventory()`, `#closeInventory`; `CraftingInventory#getMatrix`, `#setMatrix`; `Inventory#addItem`, `#getContents`, `#setContents`, `#setItem`, `#getSize`, `#clear`, `#getHolder(boolean)`; `ItemStack#clone` | `PaperInventories` | Capturing, clearing and restoring gear; returning the cursor and crafting grid to the inventory; the tripwire purge |
| `ItemStack#getPersistentDataContainer`, `#editPersistentDataContainer`, `PersistentDataContainerView#has`, `PersistentDataType.BOOLEAN`, `ItemStack#getAmount`, `#getType`, `ItemStack.of` | `PaperInventories`, `PaperMenus` | The `seasons:archived` tag on archive-view copies; the tripwire; the DataVersion probe |
| `Player#saveData`, `#getLevel`, `#setLevel`, `#getExp`, `#setExp`, `#getTotalExperience`, `#setTotalExperience`, `#setFireTicks`, `#setFreezeTicks`, `#setFallDistance`, `#setHealth`, `#getAttribute(Attribute.MAX_HEALTH)`, `#setFoodLevel`, `#setSaturation`, `#setExhaustion`, `#clearActivePotionEffects`, `#setRespawnLocation`, `#setGameMode`, `#getPlayerProfile().getProperties()`, `ProfileProperty#getName`, `#getValue`, `#getSignature` | `PaperPlayers` | Archiving: saving the player file, resets, XP, skins |
| `Player#leaveVehicle`, `#setGliding`, `#closeInventory`, `#isOnline`, `World#getChunkAtAsync`, `Entity#teleportAsync(Location, TeleportCause.PLUGIN)` | `PaperPlayers` | Safe teleports |
| `Server#getPlayerExact`; Adventure `Title.title`, `Audience#showTitle`, `Sound.sound`, `Key.key`, `Audience#playSound` | `PaperPlayers` | Finding players by name; the launch title |
| `World#spawn(Location, Class<Firework>, Consumer)`, `Firework#getFireworkMeta`, `#setFireworkMeta`, `FireworkMeta#addEffect`, `#setPower`, `FireworkEffect.builder()` (`with`, `withColor`, `withFade`, `withTrail`, `build`), `FireworkEffect.Type`, `Color`, `Entity#getPersistentDataContainer`, `PersistentDataType.BOOLEAN` | `PaperPlayers` | Launch fireworks, tagged `seasons:celebration` |
| `EntityDamageByEntityEvent#getDamager` | `PaperEvents` | Launch fireworks never hurt anyone |
| `ScoreboardManager#getMainScoreboard`, `Scoreboard#getObjective`, `#registerNewObjective(String, Criteria, Component)`, `Criteria.DEATH_COUNT`, `Objective#setDisplaySlot`, `DisplaySlot` | `PaperPlayers` | The season's deaths objective |
| `World#setSpawnLocation(Location)` | `PaperWorld` | The new season's world spawn |
| `Server#getAdvancement`, `AdvancementProgress#isDone`, `#getDateAwarded`, `#awardCriteria`, `#revokeCriteria`, `Player#getDiscoveredRecipes`, `#undiscoverRecipes`, `PlayerAdvancementDoneEvent#message` | `PaperAdvancements` | Advancement snapshots, revoking, quiet restoring, recipe resets |
| `ItemStack#serializeAsBytes`, `ItemStack.deserializeBytes`, `ItemStack#isEmpty`, `ItemStack.empty` | `PaperItemCodec` | Neutral item data with its DataVersion |
| `Advancement#getDisplay`, `#getKey`, `AdvancementDisplay#isHidden`, `Player#getAdvancementProgress`, `AdvancementProgress#getAwardedCriteria` | `PaperAdvancements` | Visible advancements (no hidden ones) and progress |
| `NamespacedKey.fromString`, `NamespacedKey.minecraft`, `Server#getWorld(NamespacedKey)`, `World#getKey`, `#getSpawnLocation`, `#getChunkAtAsync(int, int, boolean)` | `PaperPositions`, `PaperWorld` | Dimensions, spawn, chunk loading |
| `Entity#teleportAsync(Location, TeleportCause)`, `#getLocation`, `#remove`, `#getPersistentDataContainer`; `Tameable#getOwnerUniqueId` | `PaperEntities`, `PaperDisplays`, `PaperEvents` | Moving entities, tamed owners |
| `World#spawn(Location, Class, Consumer)`, `TextDisplay#text`, `Entity#setPersistent`, `PersistentDataContainer#set`, `#has` | `PaperDisplays` | Tagged text displays |
| `InventoryHolder`, `Inventory#getHolder(boolean)`, `InventoryClickEvent` (`#getRawSlot`, `#getClick`), `ClickType`, `InventoryCreativeEvent`, `InventoryDragEvent`, `InventoryMoveItemEvent`, `Player#updateInventory`, `HumanEntity#openInventory`, `ItemStack#editMeta`, `ItemMeta#customName` | `PaperMenus` | View-only menus and the paged archive view |
| `BlockBreakEvent`, `BlockPlaceEvent#getBlockPlaced`, `Block#getLocation`, `#getType`, `Material#getKey`, `Cancellable#setCancelled` | `PaperEvents` | Intents and decisions |
| `InventoryClickEvent`, `InventoryDragEvent`, `PlayerDropItemEvent`, `EntityPickupItemEvent`, `PlayerSwapHandItemsEvent`, `PlayerItemConsumeEvent`, `PlayerInteractEvent#setUseInteractedBlock`, `#setUseItemInHand`, `PlayerInteractEntityEvent`, `EntityDamageEvent` | `PaperEvents` | Intents: the archive hold and arrival immunity |
| `PlayerJoinEvent`, `PlayerQuitEvent`, `OfflinePlayer#hasPlayedBefore`, `Entity#getScheduler().run` | `PaperEvents` | Login catch-up and play time |
| `ItemSpawnEvent`, `Item#getItemStack`, `Entity#remove`, `InventoryCloseEvent` | `PaperEvents` | The tripwire |
| `World#getEnderDragonBattle`, `DragonBattle#setPreviouslyKilled`, `#getEndPortalLocation` | `PaperDragonFight` | The dragon fight |
| `Statistic#values`, `#getType`, `#getKey`, `Player#getStatistic(Statistic)` | `PaperStatistics` | General-tab statistics |
| `World#getWorldFolder` | `PaperStorage` | World data folder |
| `Mannequin` (`#setProfile`, `#setImmovable`, `#getEquipment`), `ResolvableProfile#resolvableProfile()` (builder `#name`, `#uuid`, `#addProperty`), `ProfileProperty`, `EntityEquipment#setItem`, `LivingEntity#setAI`, `#setCollidable`, `Entity#setInvulnerable`, `#setGravity`, `#setSilent`, `#customName`, `#setCustomNameVisible`, `#setPersistent`, `ItemDisplay#setItemStack`, `Display#setBillboard`, `TextDisplay#text`, `Component#compact`, `World#getNearbyEntities(BoundingBox)`, `ItemStack#editPersistentDataContainer` | `PaperDisplays` | Figures, plaques and trophies in cells, each tagged `seasons:display`; the items they show are tagged copies |
| `PlayerInteractEvent#getAction`, `#getClickedBlock`, `#getHand`, `PlayerInteractEntityEvent#getRightClicked`, `#getHand`, `#isCancelled` | `PaperEvents` | Archive anchors and figures open the archive view; every interaction with a display is cancelled |
| `Player#getGameMode`, `#setGameMode`, `PlayerInventory#getHeldItemSlot`, `Player#getTargetBlockExact`, `Server#getOfflinePlayer(UUID)`, `OfflinePlayer#hasPlayedBefore`, `#isOnline`, `#getName` | `PaperPlayers` | Game modes in the Hall, the figure's main hand, anchors by looking, names for import owners |
| `World#getChunkAtAsync`, `Chunk#addPluginChunkTicket`, `#removePluginChunkTicket`, `#isEntitiesLoaded`, `#getTileEntities`, `#getEntities`, `GlobalRegionScheduler#execute`, `#runDelayed`, `Chest`, `Barrel`, `ShulkerBox`, `Container#getInventory`, `DoubleChestInventory#getLeftSide`, `#getRightSide`, `Block#getState`, `BlockState#getLocation`, `Location#toBlockLocation`, `BoundingBox#contains`, `Sign#getSide`, `SignSide#lines`, `PlainTextComponentSerializer`, `ArmorStand` (`#hasArms`/`#setArms`, `#isSmall`/`#setSmall`, `#hasBasePlate`/`#setBasePlate`, `#isVisible`/`#setVisible`, `#isMarker`/`#setMarker`, `#hasGravity`/`#setGravity`, `#get…Pose`/`#set…Pose` for head, body, arms and legs, `#getEquipment`), `EulerAngle`, `World#spawn(Location, Class, Consumer)` for `ArmorStand`, `ItemFrame` and `GlowItemFrame`, `Entity#getUniqueId`, `ItemFrame#getItem`, `#setItem(ItemStack, boolean)`, `#getRotation`, `#setRotation`, `#isFixed`, `#setFixed`, `#isVisible`, `#setVisible`, `Hanging#getFacing`, `#setFacingDirection`, `GlowItemFrame`, `ItemStack#getData(DataComponentTypes.PROFILE)`, `ResolvableProfile#uuid`, `#name`, `#properties`, `Inventory#clear`, `#setContents`, `#isEmpty`, `#getSize`, `Server#getEntity`, `World#save` | `PaperImports` | `/hof import` and its undo |
| `PlayerAdvancementDoneEvent#getAdvancement`, `#getPlayer`, `AdvancementDisplay#title`, `PlainTextComponentSerializer#serialize` | `PaperAdvancements` | Season firsts for advancements, by their title |
| `PlayerChangedWorldEvent#getPlayer`, `World#getKey` | `PaperEvents` | Season firsts for the Nether and the End |
| `PlayerInventory#getContents`, `HumanEntity#getItemOnCursor`, `ItemStack#getType`, `#isEmpty`, `Material#getKey` | `PaperInventories` | The items a player carries, for season firsts |
| `Statistic#getKey` (mapped to the game's ids), `Player#getStatistic(Statistic, Material)` with `Statistic.MINE_BLOCK` and `Statistic.USE_ITEM`, `Material#values`, `#name`, `#isBlock`, `#isItem` | `PaperStatistics` | Block counts for the season card |
| `Player#openBook(Book)`; Adventure `Book.book`, `Component.join`, `JoinConfiguration.newlines`, MiniMessage's `<lang>` tag | `PaperPlayers` | The season card as a book, with statistics named in the client's language |
| `Commands#register(LiteralCommandNode, String, Collection)` | `PaperCommands` | `/halloffame` as an alias of `/hof` |
| `World#isChunkLoaded`, `#getBlockAt`, `Block#getType`, `#isSolid`, `#isPassable`, `Tag.BEDS#isTagged`, `Material.STRAW_BED` | `PaperWorld` | Entry checks and return safety: beds, hazards, room to stand |
| `World#getNearbyEntities(Location, double, double, double, Predicate)`, `Enemy`, `PigZombie#isAngry`, `Entity#isDead` | `PaperEntities` | Monsters near a bed or a saved spot |
| `Entity#isInsideVehicle`, `LivingEntity#isGliding`, `#isSwimming`, `Entity#isInWater`, `#getFireTicks`, `Player#getRespawnLocation`, `Location#clone`, `#add`, `#getBlock` | `PaperPlayers` | How a visitor is moving; their bed for return safety and the own-bed option |
| `PlayerMoveEvent#hasChangedBlock`, `#getFrom`, `#getTo`, `PlayerTeleportEvent#getCause` (`ENDER_PEARL`, `CONSUMABLE_EFFECT`), `EntityMountEvent#getMount`, `Cushion`, `PlayerInteractEvent#getItem`, `Action#isRightClick`, `Player#isSneaking`, `PlayerItemConsumeEvent#getItem` | `PaperEvents` | Sealing the Hall: leaving its bounds, pearls and chorus fruit, riding, cushions; sneak-clicking a bed |
| `CustomArgumentType`, `ArgumentTypes#key`; Brigadier `StringReader` (`#getCursor`, `#canRead`, `#peek`, `#skip`, `#getString`) | `PaperCommands` | Cell arguments such as `season-2:c1`, which Brigadier's word refuses |

## Seasons Web (`web`)

| API | Used in | For |
| --- | --- | --- |
| `ServicesManager#getRegistration`, `RegisteredServiceProvider#getProvider` | `SeasonsWebPlugin` | Finding `SeasonsApi` |
| `PluginManager#disablePlugin` | `SeasonsWebPlugin` | Disabling itself without Seasons |

## Test plugin (`testkit`, never shipped)

| API | For |
| --- | --- |
| `ServerLoadEvent`, `PlayerJoinEvent`, `Player#setOp`, `Server#getPort`, `Server#shutdown`, `PluginManager#isPluginEnabled` | Driving the scenario run |
| `Server#getWorlds`, `World#getWorldFolder` | Finding Seasons' data folder in the overworld |
| `LifecycleEvents.COMMANDS`, `Commands#register(String, String, BasicCommand)`, `BasicCommand#execute`, `#permission`, `CommandSourceStack#getSender`, `ServicesManager#getRegistration`, `Server#getPlayerExact`, `#getOfflinePlayerIfCached`, `GlobalRegionScheduler#execute` | `/scenario` and the scenario hooks (ADR 0003) |
| `Material#matchMaterial`, `Player#getWorld`, `World#getChunkAt`, `Chunk#addPluginChunkTicket`, `World#getBlockAt`, `Block#getState`, `#getRelative`, `#setType`, `new BlockPlaceEvent(...)`, `PluginManager#callEvent` | `/scenario place`: a bot's block placement, which the protocol translation drops (A15) |
| `new BlockBreakEvent(...)`, `#setDropItems`, `new PlayerInteractEvent(...)`, `#useInteractedBlock`, `PlayerInventory#getItemInMainHand`, `Action`, `BlockFace`, `EquipmentSlot` | `/scenario break` and `/scenario use`: a bot's block breaks and block clicks, which the translation drops too (ADR 0003) |
| `new PlayerMoveEvent(...)`, `Player#teleport(Location, TeleportCause.UNKNOWN)`, `#launchProjectile(EnderPearl.class, Vector)`, `#getEyeLocation`, `Action.RIGHT_CLICK_AIR`, `new PlayerItemConsumeEvent(...)`, `HumanEntity#dropItem(boolean)`, `Registry.ENTITY_TYPE`, `World#getNearbyEntities(Location, double, double, double, Predicate)`, `Entity#addPassenger`, `PlayerInventory#setChestplate`, `LivingEntity#setGliding`, `Player#setSneaking`, `#performCommand` | `/scenario move`, `pearl`, `use-item`, `consume`, `drop`, `sit` and `glide`: what bots can't do through the translation (A26, A27, A44) |
| `ItemStack.of`, `#addUnsafeEnchantment`, `#editMeta`, `ItemMeta#customName`, `BlockStateMeta#getBlockState`, `#setBlockState`, `ShulkerBox#getInventory` | Item samples for the NBT neutrality test |
