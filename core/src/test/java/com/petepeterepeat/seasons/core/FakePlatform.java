// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.event.IntentHandler;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Advancements;
import com.petepeterepeat.seasons.core.port.BlockInfo;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.DragonFight;
import com.petepeterepeat.seasons.core.port.Entities;
import com.petepeterepeat.seasons.core.port.Events;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.Figure;
import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.HallGuard;
import com.petepeterepeat.seasons.core.port.Inventories;
import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.Menus;
import com.petepeterepeat.seasons.core.port.Movement;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PagedView;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ProgressBar;
import com.petepeterepeat.seasons.core.port.ResetPlan;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import com.petepeterepeat.seasons.core.port.Scheduler;
import com.petepeterepeat.seasons.core.port.Sessions;
import com.petepeterepeat.seasons.core.port.Skin;
import com.petepeterepeat.seasons.core.port.Statistics;
import com.petepeterepeat.seasons.core.port.Storage;
import com.petepeterepeat.seasons.core.port.TripwireReport;
import com.petepeterepeat.seasons.core.port.World;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * A platform for core tests: players who record what they're told, a scheduler that runs global
 * work at once and repeating work when the test ticks it, and a clock the test moves.
 */
public final class FakePlatform {
  public final List<PlayerRef> online = new ArrayList<>();
  public final List<Message> broadcasts = new ArrayList<>();
  public final List<Sent> sent = new ArrayList<>();
  public final List<Bar> bars = new ArrayList<>();
  public final List<Runnable> repeating = new ArrayList<>();
  public final List<CommandNode> commands = new ArrayList<>();
  public final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T12:00:00Z"));
  public IntentHandler handler = intent -> com.petepeterepeat.seasons.core.event.Decision.allow();
  public @Nullable Sessions sessions;
  public @Nullable TripwireReport tripwire;
  public com.petepeterepeat.seasons.core.port.@Nullable Milestones milestones;
  public final Map<PlayerId, FakePlayer> players = new HashMap<>();
  public final List<PagedView> opened = new ArrayList<>();

  /** Withdraw-only menus opened, newest last. */
  public final List<Withdraw> withdraws = new ArrayList<>();

  public record Withdraw(PlayerId viewer, PagedView view, int startPage, Menus.Withdrawal taken) {}

  public final List<String> objectives = new ArrayList<>();
  public Position worldSpawn = Position.of("minecraft:overworld", 0, 64, 0);

  /** Blocks set by a test; elsewhere stone below y 64 and air from it up. */
  public final Map<List<Integer>, BlockInfo> blocks = new HashMap<>();

  /** Where the monsters are. */
  public final List<Position> monsters = new ArrayList<>();

  /** What container blocks hold, slot by slot, by block. */
  public final Map<List<Integer>, List<Optional<NeutralItem>>> containers = new HashMap<>();

  /** View-only copies opened: who, the title, and what they showed. */
  public final List<Copy> copies = new ArrayList<>();

  public record Copy(PlayerId viewer, Message title, List<Optional<NeutralItem>> items) {}

  /** Seasons' displays in the world, by id. */
  public final Map<java.util.UUID, Drawn> displays = new java.util.LinkedHashMap<>();

  /**
   * One display.
   *
   * @param equipment a figure's; empty for others
   */
  public record Drawn(
      String tag, Position at, List<Optional<NeutralItem>> equipment, String kind) {}

  public @Nullable HallGuard guard;
  public com.petepeterepeat.seasons.core.port.@Nullable ZoneGuard zoneGuard;

  /** Fireworks set off at positions, such as Homecoming's at the spawn. */
  public final List<Position> fireworks = new ArrayList<>();

  /** Who holds the bypass nodes. */
  public final Set<PlayerId> bypassing = new java.util.HashSet<>();

  public static List<Integer> key(Position at) {
    return List.of(at.blockX(), at.blockY(), at.blockZ());
  }

  public static final BlockInfo STONE = new BlockInfo("minecraft:stone", true, false, false);
  public static final BlockInfo LAVA = new BlockInfo("minecraft:lava", false, true, false);
  public static final BlockInfo BED = new BlockInfo("minecraft:straw_bed", true, false, true);
  public static final BlockInfo MAGMA = new BlockInfo("minecraft:magma_block", true, false, false);
  public static final BlockInfo SLAB = new BlockInfo("minecraft:oak_slab", true, false, false);

  /** Every Seasons teleport fails, as when another plugin cancels it. */
  public boolean teleportsFail;

  public void setBlock(int x, int y, int z, BlockInfo block) {
    blocks.put(List.of(x, y, z), block);
  }

  public BlockInfo blockAt(Position at) {
    BlockInfo set = blocks.get(List.of(at.blockX(), at.blockY(), at.blockZ()));
    if (set != null) {
      return set;
    }
    return at.blockY() < 64 ? STONE : BlockInfo.AIR;
  }

  public boolean dragonKilled = true;

  /** What one fake player carries and has done. */
  public static final class FakePlayer {
    public InventorySnapshot inventory = FakeItems.EMPTY;
    public List<NeutralItem> cursor = new ArrayList<>();
    public Position position = Position.of("minecraft:overworld", 10, 64, 10);
    public Experience experience = new Experience(5, 0.5f, 100);
    public Map<String, Map<String, Instant>> advancements = new HashMap<>();
    public Map<String, Long> statistics = new HashMap<>();
    public boolean survival = false;
    public GameMode gameMode = GameMode.SURVIVAL;
    public int saves;
    public int resets;
    public final List<Message> titles = new ArrayList<>();
    public final List<Message> actionBars = new ArrayList<>();
    public int stopped;
    public java.util.Set<String> carried = new java.util.HashSet<>();
    public Map<String, Long> counts = new HashMap<>();
    public Movement movement = Movement.STILL;
    public @Nullable Position respawn;

    /** The books opened for them: each book's pages of lines. */
    public final List<List<List<Message>>> books = new ArrayList<>();
  }

  /** Adds an online player, with an empty inventory. */
  public PlayerRef join(String name) {
    PlayerRef ref =
        new PlayerRef(
            new PlayerId(
                java.util.UUID.nameUUIDFromBytes(
                    name.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
            name);
    online.add(ref);
    players.computeIfAbsent(ref.id(), id -> new FakePlayer());
    return ref;
  }

  /** Takes a player offline; what they carry is kept, as their player file would. */
  public void leave(PlayerRef player) {
    online.remove(player);
  }

  public FakePlayer player(PlayerRef ref) {
    return players.get(ref.id());
  }

  /** A message sent to one player. */
  public record Sent(PlayerId player, Message message) {}

  /** A boss bar as shown. */
  public static final class Bar implements ProgressBar {
    public Message title;
    public float progress;
    public boolean hidden;

    Bar(Message title, float progress) {
      this.title = title;
      this.progress = progress;
    }

    @Override
    public void update(Message title, float progress) {
      this.title = title;
      this.progress = progress;
    }

    @Override
    public void hide() {
      hidden = true;
    }
  }

  /** A clock tests move by hand. */
  public static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    public void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Visible bars, not hidden. */
  public List<Bar> visibleBars() {
    return bars.stream().filter(bar -> !bar.hidden).toList();
  }

  /** Runs every repeating task once, as one tick of the server would after its period. */
  public void tick() {
    List.copyOf(repeating).forEach(Runnable::run);
  }

  /** Moves the clock a second at a time, ticking each second. */
  public void runFor(Duration duration) {
    for (long i = 0; i < duration.toSeconds(); i++) {
      clock.advance(Duration.ofSeconds(1));
      tick();
    }
  }

  /** The Season 1 import's view of the world; null unless a test sets it. */
  public com.petepeterepeat.seasons.core.port.@org.jspecify.annotations.Nullable Imports imports;

  public Platform platform(Capabilities capabilities, Path dir) {
    Players players =
        new Players() {
          @Override
          public void openBook(
              PlayerId id, Message title, Message author, List<List<Message>> pages) {
            state(id).books.add(pages);
          }

          @Override
          public Collection<PlayerRef> online() {
            return List.copyOf(online);
          }

          @Override
          public Optional<PlayerRef> find(PlayerId id) {
            return online.stream().filter(p -> p.id().equals(id)).findFirst();
          }

          @Override
          public Optional<PlayerRef> findByName(String name) {
            return online.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
          }

          @Override
          public void sendMessage(PlayerId id, Message message) {
            sent.add(new Sent(id, message));
          }

          @Override
          public void broadcast(Message message) {
            broadcasts.add(message);
          }

          @Override
          public ProgressBar showProgressBar(Message title, float progress) {
            Bar bar = new Bar(title, progress);
            bars.add(bar);
            return bar;
          }

          @Override
          public Position position(PlayerId id) {
            return state(id).position;
          }

          @Override
          public Experience experience(PlayerId id) {
            return state(id).experience;
          }

          @Override
          public void setExperience(PlayerId id, Experience experience) {
            state(id).experience = experience;
          }

          @Override
          public Optional<Skin> skin(PlayerId id) {
            return Optional.of(new Skin("textures", Optional.of("signed")));
          }

          @Override
          public void saveData(PlayerId id) {
            state(id).saves++;
          }

          @Override
          public void reset(PlayerId id, ResetPlan plan) {
            FakePlayer player = state(id);
            player.resets++;
            if (plan.xp()) {
              player.experience = Experience.NONE;
            }
          }

          @Override
          public void setSurvival(PlayerId id) {
            state(id).survival = true;
          }

          @Override
          public GameMode gameMode(PlayerId id) {
            return state(id).gameMode;
          }

          @Override
          public void setGameMode(PlayerId id, GameMode mode) {
            state(id).gameMode = mode;
          }

          @Override
          public int heldSlot(PlayerId id) {
            return 0;
          }

          @Override
          public Optional<Position> targetBlock(PlayerId id, int range) {
            return Optional.empty();
          }

          @Override
          public Optional<String> knownName(PlayerId id) {
            return online.stream().filter(p -> p.id().equals(id)).map(PlayerRef::name).findFirst();
          }

          @Override
          public CompletableFuture<Boolean> teleportSafely(PlayerId id, Position destination) {
            if (find(id).isEmpty() || teleportsFail) {
              return CompletableFuture.completedFuture(false);
            }
            state(id).position = destination;
            return CompletableFuture.completedFuture(true);
          }

          @Override
          public Movement movement(PlayerId id) {
            return state(id).movement;
          }

          @Override
          public Optional<Position> respawnPoint(PlayerId id) {
            return Optional.ofNullable(state(id).respawn);
          }

          @Override
          public Optional<Position> bed(PlayerId id) {
            return Optional.ofNullable(state(id).respawn);
          }

          @Override
          public void alert(PlayerId id, Message title, Message actionBar) {
            state(id).titles.add(title);
            state(id).actionBars.add(actionBar);
          }

          @Override
          public void stopMoving(PlayerId id) {
            state(id).stopped++;
            state(id).movement = Movement.STILL;
          }

          @Override
          public void fireworks(Position at) {
            FakePlatform.this.fireworks.add(at);
          }

          @Override
          public void celebrate(PlayerId id, Message title, Message subtitle) {
            state(id).titles.add(title);
          }

          @Override
          public void deathsObjective(String name, Message displayName, String slot) {
            objectives.add(name);
          }
        };
    Inventories inventories =
        new Inventories() {
          @Override
          public java.util.Set<String> carriedItemIds(PlayerId player) {
            return java.util.Set.copyOf(state(player).carried);
          }

          @Override
          public InventorySnapshot snapshot(PlayerId player) {
            return state(player).inventory;
          }

          @Override
          public List<NeutralItem> settle(PlayerId player) {
            // As if the inventory were full: the cursor stays where it is.
            return List.copyOf(state(player).cursor);
          }

          @Override
          public List<NeutralItem> loose(PlayerId player) {
            return List.copyOf(state(player).cursor);
          }

          @Override
          public void clear(PlayerId player, boolean enderChest) {
            InventorySnapshot was = state(player).inventory;
            state(player).cursor.clear();
            state(player).inventory =
                new InventorySnapshot(
                    FakeItems.empty(was.storage().size()),
                    FakeItems.empty(was.armour().size()),
                    Optional.empty(),
                    enderChest ? FakeItems.empty(was.enderChest().size()) : was.enderChest());
          }

          @Override
          public boolean give(PlayerId player, NeutralItem item) {
            InventorySnapshot was = state(player).inventory;
            List<Optional<NeutralItem>> storage = new ArrayList<>(was.storage());
            int free = storage.indexOf(Optional.<NeutralItem>empty());
            if (free < 0) {
              return false;
            }
            storage.set(free, Optional.of(item));
            state(player).inventory =
                new InventorySnapshot(storage, was.armour(), was.offHand(), was.enderChest());
            return true;
          }

          @Override
          public void restore(PlayerId player, InventorySnapshot snapshot) {
            state(player).inventory = snapshot;
          }

          @Override
          public List<String> purgeArchived(PlayerId player) {
            return List.of();
          }

          @Override
          public Optional<List<Optional<NeutralItem>>> container(Position block) {
            return Optional.ofNullable(containers.get(key(block)));
          }

          @Override
          public int dataVersion() {
            return FakeItems.DATA_VERSION;
          }
        };
    Advancements advancements =
        new Advancements() {
          @Override
          public List<String> visible() {
            return List.of("minecraft:story/root", "minecraft:story/mine_stone");
          }

          @Override
          public Map<String, Set<String>> awarded(PlayerId player) {
            Map<String, Set<String>> awarded = new HashMap<>();
            state(player)
                .advancements
                .forEach((id, criteria) -> awarded.put(id, criteria.keySet()));
            return awarded;
          }

          @Override
          public AdvancementSnapshot snapshot(PlayerId player, boolean includeRecipes) {
            Map<String, Map<String, Instant>> awarded = new HashMap<>(state(player).advancements);
            int completed =
                (int) awarded.keySet().stream().filter(id -> visible().contains(id)).count();
            return new AdvancementSnapshot(awarded, completed, visible().size());
          }

          @Override
          public void revokeAll(PlayerId player, boolean includeRecipes) {
            state(player).advancements.clear();
          }

          @Override
          public void award(PlayerId player, Map<String, Map<String, Instant>> criteria) {
            state(player).advancements.putAll(criteria);
          }

          @Override
          public void forgetRecipes(PlayerId player) {}
        };
    World world =
        new World() {
          @Override
          public boolean dimensionExists(String dimension) {
            return dimension.startsWith("minecraft:");
          }

          @Override
          public Position spawn(String dimension) {
            return worldSpawn;
          }

          @Override
          public void setSpawn(Position position) {
            worldSpawn = position;
          }

          @Override
          public BlockInfo block(Position position) {
            return blockAt(position);
          }

          @Override
          public boolean isLoaded(Position position) {
            return true;
          }

          @Override
          public CompletableFuture<Void> loadChunk(Position position) {
            return CompletableFuture.completedFuture(null);
          }
        };
    Entities entities =
        new Entities() {
          @Override
          public Optional<Position> position(java.util.UUID entity) {
            return Optional.empty();
          }

          @Override
          public CompletableFuture<Boolean> teleport(java.util.UUID entity, Position destination) {
            return CompletableFuture.completedFuture(false);
          }

          @Override
          public Optional<PlayerId> owner(java.util.UUID entity) {
            return Optional.empty();
          }

          @Override
          public List<Position> hostiles(Position centre, double dx, double dy, double dz) {
            return monsters.stream()
                .filter(m ->
                    Math.abs(m.x() - centre.x()) <= dx
                        && Math.abs(m.y() - centre.y()) <= dy
                        && Math.abs(m.z() - centre.z()) <= dz)
                .toList();
          }
        };
    Menus menus =
        new Menus() {
          @Override
          public void openViewOnly(
              PlayerId viewer, Message title, List<Optional<NeutralItem>> items) {
            copies.add(new Copy(viewer, title, items));
          }

          @Override
          public void openPaged(PlayerId viewer, PagedView view, int startPage) {
            opened.add(view);
          }

          @Override
          public void openWithdraw(
              PlayerId viewer, PagedView view, int startPage, Menus.Withdrawal taken) {
            withdraws.add(new Withdraw(viewer, view, startPage, taken));
          }
        };
    Events events =
        new Events() {
          @Override
          public void handle(IntentHandler h) {
            handler = h;
          }

          @Override
          public void sessions(Sessions s) {
            sessions = s;
          }

          @Override
          public void tripwire(TripwireReport report) {
            tripwire = report;
          }

          @Override
          public void milestones(com.petepeterepeat.seasons.core.port.Milestones m) {
            milestones = m;
          }

          @Override
          public void hall(HallGuard g) {
            guard = g;
          }

          @Override
          public void zones(com.petepeterepeat.seasons.core.port.ZoneGuard g) {
            zoneGuard = g;
          }
        };
    Displays shownDisplays =
        new Displays() {
          @Override
          public java.util.UUID spawnText(Position position, Message text, String tag) {
            return add(new Drawn(tag, position, List.of(), "text"));
          }

          @Override
          public java.util.UUID spawnFigure(Figure figure) {
            return add(new Drawn(figure.tag(), figure.at(), figure.equipment(), "figure"));
          }

          @Override
          public java.util.UUID spawnItem(Position position, NeutralItem item, String tag) {
            return add(new Drawn(tag, position, List.of(), "item"));
          }

          private java.util.UUID add(Drawn shown) {
            java.util.UUID id = java.util.UUID.randomUUID();
            displays.put(id, shown);
            return id;
          }

          @Override
          public void remove(java.util.UUID display) {
            displays.remove(display);
          }

          @Override
          public List<Displays.Shown> tagged(Position min, Position max) {
            return displays.entrySet().stream()
                .filter(e -> within(e.getValue().at(), min, max))
                .map(e -> new Displays.Shown(e.getKey(), e.getValue().tag(), e.getValue().at()))
                .toList();
          }

          @Override
          public boolean wears(java.util.UUID figure, List<Optional<NeutralItem>> equipment) {
            Drawn shown = displays.get(figure);
            return shown != null && shown.equipment().equals(equipment);
          }

          @Override
          public int removeTagged(Position min, Position max, String tagPrefix) {
            List<java.util.UUID> gone =
                displays.entrySet().stream()
                    .filter(e ->
                        within(e.getValue().at(), min, max)
                            && e.getValue().tag().startsWith(tagPrefix))
                    .map(Map.Entry::getKey)
                    .toList();
            gone.forEach(displays::remove);
            return gone.size();
          }
        };
    DragonFight dragon =
        new DragonFight() {
          @Override
          public void setPreviouslyKilled(boolean previouslyKilled) {
            dragonKilled = previouslyKilled;
          }

          @Override
          public Optional<Position> exitPortal() {
            return Optional.empty();
          }
        };
    Statistics statistics =
        new Statistics() {
          @Override
          public Map<String, Long> general(PlayerId player) {
            return Map.copyOf(state(player).statistics);
          }

          @Override
          public Map<String, Long> counts(PlayerId player) {
            return Map.copyOf(state(player).counts);
          }
        };
    Scheduler scheduler =
        new Scheduler() {
          @Override
          public void runGlobal(Runnable task) {
            task.run();
          }

          @Override
          public void runGlobalLater(Duration delay, Runnable task) {
            task.run();
          }

          @Override
          public ScheduledTask runGlobalRepeating(Duration period, Runnable task) {
            repeating.add(task);
            return () -> repeating.remove(task);
          }

          @Override
          public void runForPlayer(PlayerId player, Runnable task) {
            task.run();
          }

          @Override
          public void runAt(Position position, Runnable task) {
            task.run();
          }

          @Override
          public void runAsync(Runnable task) {
            task.run();
          }
        };
    Storage storage =
        new Storage() {
          @Override
          public Path configDirectory() {
            return dir.resolve("plugins/Seasons");
          }

          @Override
          public Path worldDataDirectory() {
            return dir.resolve("world/seasons");
          }
        };
    return new Platform(
        "Test",
        capabilities,
        players,
        inventories,
        advancements,
        world,
        entities,
        shownDisplays,
        menus,
        (root, aliases) -> commands.add(root),
        // Everyone holds every node except the bypass nodes, which only those a test names get.
        (player, node) -> !node.endsWith(".bypass") || bypassing.contains(player),
        events,
        scheduler,
        dragon,
        statistics,
        storage,
        imports);
  }

  private static boolean within(Position at, Position min, Position max) {
    return at.blockX() >= min.blockX()
        && at.blockX() <= max.blockX()
        && at.blockY() >= min.blockY()
        && at.blockY() <= max.blockY()
        && at.blockZ() >= min.blockZ()
        && at.blockZ() <= max.blockZ();
  }

  private FakePlayer state(PlayerId id) {
    return players.computeIfAbsent(id, unknown -> new FakePlayer());
  }
}
