// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.HallGuard;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The Hall of Fame's deny-by-default rules. Inside a gallery nothing changes unless an owner is
 * decorating their own cell (or a player holds {@code seasons.hof.bypass}); visitors may look, open
 * view-only copies of containers, sit on cushions and walk through doors. Changes nobody in
 * particular makes, such as explosions, fire, pistons and flowing water, are refused through {@link
 * HallGuard}. Refusals and bypasses are audited. Server thread only.
 */
public final class HallRules implements HallGuard {
  /**
   * Blocks nobody places in the Hall: they move items out of containers, or hold a player's own.
   */
  private static final Set<String> NEVER =
      Set.of("hopper", "dropper", "dispenser", "crafter", "ender_chest");

  /** Entities an owner may place, use and break in their cell. */
  private static final Set<String> DECORATION =
      Set.of("item_frame", "glow_item_frame", "painting", "armor_stand", "cushion");

  private static final String CUSHION = "cushion";
  private static final String HOPPER_MINECART = "hopper_minecart";
  private static final String LAVA_BUCKET = "lava_bucket";
  private static final String DECORATED_POT = "decorated_pot";
  private static final String HALL = "hall";

  /** Container ids name every colour of a container: {@code shulker_box} covers them all. */
  private static final List<String> COLOURS =
      List.of(
          "white",
          "orange",
          "magenta",
          "light_blue",
          "yellow",
          "lime",
          "pink",
          "gray",
          "light_gray",
          "cyan",
          "purple",
          "blue",
          "brown",
          "green",
          "red",
          "black");

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService halls;
  private final HallAudit audit;
  private final Reconciler reconciler;
  private @Nullable ScheduledTask flusher;

  public HallRules(
      Platform platform,
      ConfigHolder config,
      HallService halls,
      HallAudit audit,
      Reconciler reconciler) {
    this.platform = platform;
    this.config = config;
    this.halls = halls;
    this.audit = audit;
    this.reconciler = reconciler;
  }

  public void start() {
    flusher = platform.scheduler().runGlobalRepeating(HallAudit.WINDOW, audit::flush);
  }

  public void stop() {
    if (flusher != null) {
      flusher.cancel();
      flusher = null;
    }
    audit.flushAll();
  }

  /** The rules' say on a player's intent, if it touches the Hall. */
  public Optional<Decision> decide(Intent intent) {
    return switch (intent) {
      case Intent.PlaceBlock place -> place(place.player(), place.block(), place.blockType());
      case Intent.BreakBlock broken -> breakBlock(broken.player(), broken.block());
      case Intent.ChangeBlock change ->
          change(change.player(), change.block(), "change", change.how(), "hof.no-build");
      case Intent.Bucket bucket -> bucket(bucket);
      case Intent.UseBlock use -> useBlock(use);
      case Intent.OpenContainer open -> open(open.player(), open.block());
      case Intent.UseEntity use -> useEntity(use.player(), use.at(), use.type());
      case Intent.PlaceEntity place -> placeEntity(place.player(), place.at(), place.type());
      case Intent.HurtEntity hurt -> entity(hurt.player(), hurt.at(), "hit", hurt.type());
      default -> Optional.empty();
    };
  }

  private Optional<Decision> place(PlayerId player, Position block, String type) {
    if (!halls.inHall(block)) {
      return Optional.empty();
    }
    if (bypasses(player)) {
      return bypassed(player, "place", type, block);
    }
    if (NEVER.contains(path(type))) {
      return refused(player, "place", type, block, "hof.rules.no-hopper");
    }
    if (decorating(player, block).isEmpty()) {
      return refused(player, "place", type, block, "hof.no-build");
    }
    if (platform.inventories().container(block).isPresent() && !cellContainer(type)) {
      return refused(player, "place", type, block, "hof.rules.no-container");
    }
    return Optional.of(Decision.allow());
  }

  private Optional<Decision> breakBlock(PlayerId player, Position block) {
    if (!halls.inHall(block)) {
      return Optional.empty();
    }
    String type = platform.world().block(block).type();
    if (bypasses(player)) {
      return bypassed(player, "break", type, block);
    }
    if (decorating(player, block).isEmpty()) {
      return refused(player, "break", type, block, "hof.no-build");
    }
    boolean holdsSomething =
        platform
            .inventories()
            .container(block)
            .map(items -> items.stream().anyMatch(Optional::isPresent))
            .orElse(false);
    if (holdsSomething) {
      return Optional.of(Decision.deny(message("hof.rules.empty-first")));
    }
    return Optional.of(Decision.allow());
  }

  /** Any other change at a position: allowed only to a decorating owner in their own cell. */
  private Optional<Decision> change(
      PlayerId player, Position at, String verb, String what, String messageKey) {
    if (!halls.inHall(at)) {
      return Optional.empty();
    }
    if (bypasses(player)) {
      return bypassed(player, verb, what, at);
    }
    if (decorating(player, at).isEmpty()) {
      return refused(player, verb, what, at, messageKey);
    }
    return Optional.of(Decision.allow());
  }

  private Optional<Decision> bucket(Intent.Bucket bucket) {
    if (halls.inHall(bucket.block())
        && !bypasses(bucket.player())
        && path(bucket.bucket()).equals(LAVA_BUCKET)) {
      return refused(
          bucket.player(), "empty", bucket.bucket(), bucket.block(), "hof.rules.no-lava");
    }
    return change(bucket.player(), bucket.block(), "empty", bucket.bucket(), "hof.no-build");
  }

  /**
   * Right-clicking a block in the Hall: an owner decorating their cell uses anything; anyone opens
   * containers (as copies) and doors; everything else does nothing.
   */
  private Optional<Decision> useBlock(Intent.UseBlock use) {
    Position block = use.block();
    if (!halls.inHall(block) || bypasses(use.player())) {
      return Optional.empty();
    }
    if (decorating(use.player(), block).isPresent()) {
      return Optional.of(Decision.allow());
    }
    String type = platform.world().block(block).type();
    if (path(type).equals(DECORATED_POT)) {
      // A pot has no menu: right-clicking it would put the held item in.
      if (use.mainHand()) {
        openCopy(use.player(), block);
      }
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    if (cellContainer(type) || walkThrough(type)) {
      return Optional.of(Decision.allow());
    }
    if (!use.mainHand() || (!use.interactable() && use.emptyHand())) {
      return Optional.of(new Decision.Deny(Optional.empty()));
    }
    return refused(
        use.player(), "use", type, block, use.interactable() ? "hof.rules.no-use" : "hof.no-build");
  }

  /** Opening a block's inventory: the real one for its decorating owner, else a copy. */
  private Optional<Decision> open(PlayerId player, Position block) {
    if (!halls.inHall(block) || bypasses(player) || decorating(player, block).isPresent()) {
      return Optional.empty();
    }
    String type = platform.world().block(block).type();
    if (!cellContainer(type)) {
      return refused(player, "open", type, block, "hof.rules.no-use");
    }
    openCopy(player, block);
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  /** Opens a view-only copy of what a container holds, once the open being refused is done. */
  private void openCopy(PlayerId player, Position block) {
    List<Optional<NeutralItem>> items = platform.inventories().container(block).orElse(List.of());
    Message title = copyTitle(block);
    platform
        .scheduler()
        .runForPlayer(player, () -> platform.menus().openViewOnly(player, title, items));
  }

  private Message copyTitle(Position block) {
    Optional<HallService.CellRef> cell = halls.cellAt(block);
    if (cell.isPresent()) {
      int season = cell.get().gallery().season();
      Optional<String> owner =
          cell.get().cell().owner(season).flatMap(id -> platform.players().knownName(id));
      if (owner.isPresent()) {
        return message("hof.view-only", "name", owner.get(), "season", Integer.toString(season));
      }
    }
    return message("hof.view-only-hall");
  }

  private Optional<Decision> useEntity(PlayerId player, Position at, String type) {
    if (path(type).equals(CUSHION)) {
      // Anyone may sit on a cushion; sitting is decided as riding.
      return Optional.empty();
    }
    return entity(player, at, "use", type);
  }

  /** Using or hitting an entity: only decoration, only by its decorating owner. */
  private Optional<Decision> entity(PlayerId player, Position at, String verb, String type) {
    if (!halls.inHall(at)) {
      return Optional.empty();
    }
    if (bypasses(player)) {
      return bypassed(player, verb, type, at);
    }
    if (decorating(player, at).isPresent() && DECORATION.contains(path(type))) {
      return Optional.of(Decision.allow());
    }
    return refused(player, verb, type, at, "hof.rules.no-touch");
  }

  private Optional<Decision> placeEntity(PlayerId player, Position at, String type) {
    if (!halls.inHall(at)) {
      return Optional.empty();
    }
    if (bypasses(player)) {
      return bypassed(player, "place", type, at);
    }
    if (path(type).equals(HOPPER_MINECART)) {
      return refused(player, "place", type, at, "hof.rules.no-hopper");
    }
    if (decorating(player, at).isPresent() && DECORATION.contains(path(type))) {
      return Optional.of(Decision.allow());
    }
    return refused(player, "place", type, at, "hof.no-build");
  }

  // The world's side

  @Override
  public boolean protects(Position block) {
    for (Gallery gallery : halls.galleries()) {
      if (gallery.contains(block)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean allows(List<Position> blocks) {
    HallService.@Nullable CellRef only = null;
    boolean inHall = false;
    for (Position block : blocks) {
      if (!protects(block)) {
        continue;
      }
      inHall = true;
      Optional<HallService.CellRef> cell = halls.cellAt(block);
      if (cell.isEmpty() || (only != null && !only.key().equals(cell.get().key()))) {
        return false;
      }
      only = cell.get();
    }
    if (!inHall) {
      return true;
    }
    HallService.CellRef cell = only;
    // Every block has to be in that cell, not some outside the Hall.
    if (cell == null || blocks.stream().anyMatch(block -> !cell.cell().contains(block))) {
      return false;
    }
    Optional<PlayerId> owner = cell.cell().owner(cell.gallery().season());
    return owner.isPresent() && halls.canDecorate(owner.get(), cell);
  }

  @Override
  public void entitiesLoaded(String dimension, int chunkX, int chunkZ) {
    reconciler.chunkLoaded(dimension, chunkX, chunkZ);
  }

  // Helpers

  private Optional<HallService.CellRef> decorating(PlayerId player, Position at) {
    return halls.cellAt(at).filter(ref -> halls.canDecorate(player, ref));
  }

  private boolean bypasses(PlayerId player) {
    return platform.permissions().has(player, HallService.BYPASS);
  }

  /** Whether a block is a container cells may hold, by {@code cells.containers}. */
  private boolean cellContainer(String type) {
    String path = path(type);
    for (String colour : COLOURS) {
      if (path.startsWith(colour + "_")) {
        String plain = path.substring(colour.length() + 1);
        if (listed(plain)) {
          return true;
        }
      }
    }
    return listed(path);
  }

  private boolean listed(String path) {
    return config.get().config().cells().containers().stream()
        .anyMatch(listed -> path(listed).equals(path));
  }

  /** Doors, trapdoors and fence gates a hand can open: walking into a cell may need them. */
  private static boolean walkThrough(String type) {
    String path = path(type);
    if (path.startsWith("iron_")) {
      return false;
    }
    return path.endsWith("_door") || path.endsWith("_trapdoor") || path.endsWith("_fence_gate");
  }

  private Optional<Decision> refused(
      PlayerId player, String verb, String what, Position at, String messageKey) {
    audit.record(name(player), HallAudit.BLOCKED, target(at), verb + " " + what, where(at));
    return Optional.of(Decision.deny(message(messageKey)));
  }

  private Optional<Decision> bypassed(PlayerId player, String verb, String what, Position at) {
    audit.record(name(player), HallAudit.BYPASS, target(at), verb + " " + what, where(at));
    return Optional.of(Decision.allow());
  }

  private String name(PlayerId player) {
    return platform.players().knownName(player).orElse(player.toString());
  }

  private String target(Position at) {
    return halls.cellAt(at).map(HallService.CellRef::key).orElse(HALL);
  }

  private static String where(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ();
  }

  /** A namespaced id without its namespace: {@code minecraft:chest} is {@code chest}. */
  private static String path(String id) {
    int colon = id.indexOf(':');
    return colon < 0 ? id : id.substring(colon + 1);
  }

  private Message message(String key, String... placeholders) {
    return config.get().messages().get(key, placeholders);
  }
}
