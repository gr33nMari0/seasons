// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.Movement;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Players;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ProgressBar;
import com.petepeterepeat.seasons.core.port.ResetPlan;
import com.petepeterepeat.seasons.core.port.Skin;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

final class PaperPlayers implements Players {
  private final Server server;
  private final PaperText text;
  private final NamespacedKey celebration;
  private final Set<UUID> seasonsMoving = ConcurrentHashMap.newKeySet();

  /**
   * @param celebration the tag on launch fireworks, which {@link PaperEvents} keeps from hurting
   *     anyone
   */
  PaperPlayers(Server server, PaperText text, NamespacedKey celebration) {
    this.server = server;
    this.text = text;
    this.celebration = celebration;
  }

  @Override
  public Collection<PlayerRef> online() {
    return server.getOnlinePlayers().stream().map(PaperPlayers::ref).toList();
  }

  @Override
  public Optional<PlayerRef> find(PlayerId id) {
    return player(id).map(PaperPlayers::ref);
  }

  @Override
  public Optional<PlayerRef> findByName(String name) {
    return Optional.ofNullable(server.getPlayerExact(name))
        .or(() ->
            server.getOnlinePlayers().stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .map(p -> (Player) p)
                .findFirst())
        .map(PaperPlayers::ref);
  }

  @Override
  public void sendMessage(PlayerId id, Message message) {
    player(id).ifPresent(p -> p.sendMessage(text.render(message)));
  }

  @Override
  public void openBook(
      PlayerId id, Message title, Message author, java.util.List<java.util.List<Message>> pages) {
    player(id)
        .ifPresent(player -> {
          java.util.List<net.kyori.adventure.text.Component> rendered = new java.util.ArrayList<>();
          for (java.util.List<Message> page : pages) {
            rendered.add(
                net.kyori.adventure.text.Component.join(
                    net.kyori.adventure.text.JoinConfiguration.newlines(),
                    page.stream().map(text::render).toList()));
          }
          player.openBook(
              net.kyori.adventure.inventory.Book.book(
                  text.render(title), text.render(author), rendered));
        });
  }

  @Override
  public void broadcast(Message message) {
    server.broadcast(text.render(message));
  }

  @Override
  public ProgressBar showProgressBar(Message title, float progress) {
    BossBar bar =
        BossBar.bossBar(
            text.render(title), clamp(progress), BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    server.showBossBar(bar);
    return new ProgressBar() {
      @Override
      public void update(Message next, float nextProgress) {
        bar.name(text.render(next));
        bar.progress(clamp(nextProgress));
        // Showing again reaches players who joined since; those already viewing are unaffected.
        server.showBossBar(bar);
      }

      @Override
      public void hide() {
        server.hideBossBar(bar);
      }
    };
  }

  @Override
  public Position position(PlayerId id) {
    return PaperPositions.toPosition(Objects.requireNonNull(online(id).getLocation()));
  }

  @Override
  public Experience experience(PlayerId id) {
    Player player = online(id);
    return new Experience(player.getLevel(), player.getExp(), player.getTotalExperience());
  }

  @Override
  public void setExperience(PlayerId id, Experience experience) {
    Player player = online(id);
    player.setLevel(experience.level());
    player.setExp(Math.clamp(experience.progress(), 0f, 1f));
    player.setTotalExperience(experience.total());
  }

  @Override
  public Optional<Skin> skin(PlayerId id) {
    return online(id).getPlayerProfile().getProperties().stream()
        .filter(property -> property.getName().equals("textures"))
        .findFirst()
        .map(property ->
            new Skin(property.getValue(), Optional.ofNullable(property.getSignature())));
  }

  @Override
  public void saveData(PlayerId id) {
    online(id).saveData();
  }

  @Override
  public void reset(PlayerId id, ResetPlan plan) {
    Player player = online(id);
    player.setFireTicks(0);
    player.setFreezeTicks(0);
    player.setFallDistance(0);
    AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
    player.setHealth(maxHealth == null ? 20 : maxHealth.getValue());
    player.setFoodLevel(20);
    player.setSaturation(5f);
    player.setExhaustion(0f);
    if (plan.xp()) {
      player.setLevel(0);
      player.setExp(0f);
      player.setTotalExperience(0);
    }
    if (plan.effects()) {
      player.clearActivePotionEffects();
    }
    if (plan.respawnPoint()) {
      player.setRespawnLocation(null);
    }
  }

  @Override
  public void setSurvival(PlayerId id) {
    online(id).setGameMode(GameMode.SURVIVAL);
  }

  @Override
  public com.petepeterepeat.seasons.core.port.GameMode gameMode(PlayerId id) {
    return com.petepeterepeat.seasons.core.port.GameMode.valueOf(online(id).getGameMode().name());
  }

  @Override
  public void setGameMode(PlayerId id, com.petepeterepeat.seasons.core.port.GameMode mode) {
    online(id).setGameMode(GameMode.valueOf(mode.name()));
  }

  @Override
  public int heldSlot(PlayerId id) {
    return online(id).getInventory().getHeldItemSlot();
  }

  @Override
  public Optional<UUID> targetEntity(PlayerId id, int range) {
    return player(id).map(p -> p.getTargetEntity(range)).map(Entity::getUniqueId);
  }

  @Override
  public Optional<Position> targetBlock(PlayerId id, int range) {
    return player(id)
        .map(p -> p.getTargetBlockExact(range))
        .map(block -> PaperPositions.toPosition(block.getLocation()));
  }

  @Override
  public Optional<String> knownName(PlayerId id) {
    var offline = server.getOfflinePlayer(id.uuid());
    return offline.hasPlayedBefore() || offline.isOnline()
        ? Optional.ofNullable(offline.getName())
        : Optional.empty();
  }

  @Override
  public CompletableFuture<Boolean> teleportSafely(PlayerId id, Position destination) {
    Optional<Player> found = player(id);
    Optional<World> world = PaperPositions.world(destination.dimension());
    if (found.isEmpty() || world.isEmpty()) {
      return CompletableFuture.completedFuture(false);
    }
    Player player = found.get();
    player.leaveVehicle();
    player.setGliding(false);
    player.setVelocity(new Vector());
    player.setFallDistance(0);
    player.setFireTicks(0);
    player.closeInventory();
    Location to = PaperPositions.toLocation(destination);
    UUID uuid = player.getUniqueId();
    seasonsMoving.add(uuid);
    return world
        .get()
        .getChunkAtAsync(to.getBlockX() >> 4, to.getBlockZ() >> 4, true)
        .thenCompose(chunk ->
            player.isOnline()
                ? player.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN)
                : CompletableFuture.completedFuture(false))
        .whenComplete((moved, failure) -> seasonsMoving.remove(uuid))
        .thenApply(moved -> {
          if (moved) {
            player.setFallDistance(0);
          }
          return moved;
        });
  }

  /** Whether Seasons is teleporting a player right now. */
  boolean isSeasonsTeleport(Player player) {
    return seasonsMoving.contains(player.getUniqueId());
  }

  @Override
  public Movement movement(PlayerId id) {
    return player(id)
        .map(player ->
            new Movement(
                player.isInsideVehicle(),
                player.isGliding(),
                player.isSwimming() || player.isInWater(),
                player.getFireTicks() > 0))
        .orElse(Movement.STILL);
  }

  @Override
  public Optional<Position> respawnPoint(PlayerId id) {
    return player(id)
        .flatMap(player -> Optional.ofNullable(player.getRespawnLocation()))
        .map(PaperPositions::toPosition);
  }

  @Override
  public Optional<Position> bed(PlayerId id) {
    // The respawn location is where they would stand up, next to the bed: find the bed by it.
    return player(id)
        .flatMap(player -> Optional.ofNullable(player.getRespawnLocation()))
        .flatMap(PaperPlayers::bedNear)
        .map(PaperPositions::toPosition);
  }

  private static Optional<Location> bedNear(Location at) {
    for (int dx = -2; dx <= 2; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        for (int dz = -2; dz <= 2; dz++) {
          Location block = at.clone().add(dx, dy, dz);
          if (PaperWorld.isBed(block.getBlock().getType())) {
            return Optional.of(block.toBlockLocation());
          }
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public void alert(PlayerId id, Message title, Message actionBar) {
    player(id)
        .ifPresent(player -> {
          player.showTitle(Title.title(text.render(title), Component.empty()));
          player.sendActionBar(text.render(actionBar));
        });
  }

  @Override
  public void stopMoving(PlayerId id) {
    player(id)
        .ifPresent(player -> {
          player.leaveVehicle();
          player.setGliding(false);
          player.setVelocity(new Vector());
        });
  }

  @Override
  public void fireworks(Position at) {
    PaperPositions.world(at.dimension())
        .ifPresent(world -> launchFirework(world, PaperPositions.toLocation(at)));
  }

  @Override
  public void celebrate(PlayerId id, Message title, Message subtitle) {
    player(id)
        .ifPresent(player -> {
          player.showTitle(Title.title(text.render(title), text.render(subtitle)));
          player.playSound(
              Sound.sound(Key.key("ui.toast.challenge_complete"), Sound.Source.MASTER, 1f, 1f));
          launchFirework(player.getWorld(), Objects.requireNonNull(player.getLocation()));
        });
  }

  /** A celebration firework, tagged so it hurts nobody. */
  private void launchFirework(World world, Location at) {
    world.spawn(
        at,
        Firework.class,
        firework -> {
          FireworkMeta meta = firework.getFireworkMeta();
          meta.addEffect(
              FireworkEffect.builder()
                  .with(FireworkEffect.Type.BALL_LARGE)
                  .withColor(Color.ORANGE, Color.YELLOW)
                  .withFade(Color.WHITE)
                  .withTrail()
                  .build());
          meta.setPower(1);
          firework.setFireworkMeta(meta);
          firework.getPersistentDataContainer().set(celebration, PersistentDataType.BOOLEAN, true);
        });
  }

  /** Whether an entity is one of the launch fireworks. */
  boolean isCelebration(Entity entity) {
    return entity instanceof Firework && entity.getPersistentDataContainer().has(celebration);
  }

  @Override
  public void deathsObjective(String name, Message displayName, String slot) {
    Scoreboard scoreboard = server.getScoreboardManager().getMainScoreboard();
    Objective objective = scoreboard.getObjective(name);
    if (objective == null) {
      objective =
          scoreboard.registerNewObjective(name, Criteria.DEATH_COUNT, text.render(displayName));
    }
    DisplaySlot display =
        switch (slot) {
          case "sidebar" -> DisplaySlot.SIDEBAR;
          case "below_name" -> DisplaySlot.BELOW_NAME;
          case "none" -> null;
          default -> DisplaySlot.PLAYER_LIST;
        };
    if (display != null) {
      objective.setDisplaySlot(display);
    }
  }

  private Player online(PlayerId id) {
    return player(id).orElseThrow(() -> new IllegalStateException(id + " is offline"));
  }

  private static float clamp(float progress) {
    return Math.clamp(progress, BossBar.MIN_PROGRESS, BossBar.MAX_PROGRESS);
  }

  Optional<Player> player(PlayerId id) {
    return Optional.ofNullable(server.getPlayer(id.uuid()));
  }

  static PlayerRef ref(Player player) {
    return new PlayerRef(new PlayerId(player.getUniqueId()), player.getName());
  }
}
