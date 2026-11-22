// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.Displays.Outline;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The Hall's outlines on Paper (questions 276): each box twelve thin glowing block displays along
 * its edges and a text display over it, spawned hidden ({@code setVisibleByDefault(false)}) and
 * shown to one admin, never saved ({@code setPersistent(false)}) and marked {@code
 * seasons:outline}, so none outlives the server or a chunk.
 */
final class PaperOutlines implements Listener {
  private static final float THICKNESS = 0.04f;

  private static final Map<String, Color> COLOURS =
      Map.of(
          "hub", Color.WHITE,
          "gallery", Color.AQUA,
          "cell", Color.YELLOW,
          "focus", Color.LIME,
          "gate", Color.RED,
          "entry", Color.PURPLE,
          "exit", Color.ORANGE,
          "link", Color.ORANGE,
          "selection", Color.FUCHSIA,
          "preview", Color.SILVER);

  private final Plugin plugin;
  private final NamespacedKey key;

  /** What each admin is shown: each box with its entities. */
  private final Map<UUID, Map<Outline, List<Entity>>> shown = new HashMap<>();

  PaperOutlines(Plugin plugin) {
    this.plugin = plugin;
    this.key = new NamespacedKey(plugin, "outline");
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
  }

  void outline(PlayerId viewer, List<Outline> boxes) {
    Player player = plugin.getServer().getPlayer(viewer.uuid());
    Map<Outline, List<Entity>> before = shown.computeIfAbsent(viewer.uuid(), k -> new HashMap<>());
    Map<Outline, List<Entity>> after = new HashMap<>();
    for (Outline box : boxes) {
      List<Entity> existing = before.remove(box);
      if (existing != null && existing.stream().allMatch(Entity::isValid)) {
        after.put(box, existing);
      } else {
        if (existing != null) {
          existing.forEach(Entity::remove);
        }
        if (player != null) {
          after.put(box, spawn(player, box));
        }
      }
    }
    before.values().forEach(entities -> entities.forEach(Entity::remove));
    if (after.isEmpty()) {
      shown.remove(viewer.uuid());
    } else {
      shown.put(viewer.uuid(), after);
    }
  }

  private List<Entity> spawn(Player viewer, Outline box) {
    World world = plugin.getServer().getWorld(NamespacedKey.fromString(box.min().dimension()));
    if (world == null) {
      return List.of();
    }
    double x0 = box.min().blockX();
    double y0 = box.min().blockY();
    double z0 = box.min().blockZ();
    float sx = box.max().blockX() + 1 - (float) x0;
    float sy = box.max().blockY() + 1 - (float) y0;
    float sz = box.max().blockZ() + 1 - (float) z0;
    Color colour = COLOURS.getOrDefault(box.kind(), Color.WHITE);
    List<Entity> entities = new ArrayList<>();
    // Four edges along each axis, from each corner of the face across it.
    for (float[] corner : new float[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
      entities.add(
          edge(world, viewer, x0, y0 + corner[0] * sy, z0 + corner[1] * sz, sx, 0, 0, colour));
      entities.add(
          edge(world, viewer, x0 + corner[0] * sx, y0, z0 + corner[1] * sz, 0, sy, 0, colour));
      entities.add(
          edge(world, viewer, x0 + corner[0] * sx, y0 + corner[1] * sy, z0, 0, 0, sz, colour));
    }
    box.label()
        .ifPresent(label -> {
          Location at = new Location(world, x0 + sx / 2, y0 + sy + 0.4, z0 + sz / 2);
          TextDisplay text =
              world.spawn(
                  at,
                  TextDisplay.class,
                  display -> {
                    prepare(display);
                    display.text(Component.text(label));
                    display.setBillboard(Display.Billboard.CENTER);
                  });
          viewer.showEntity(plugin, text);
          entities.add(text);
        });
    return entities;
  }

  /** One edge: a thin block display stretched from a point along one axis. */
  private BlockDisplay edge(
      World world,
      Player viewer,
      double x,
      double y,
      double z,
      float dx,
      float dy,
      float dz,
      Color colour) {
    float half = THICKNESS / 2;
    Location at = new Location(world, x - half, y - half, z - half);
    BlockDisplay edge =
        world.spawn(
            at,
            BlockDisplay.class,
            display -> {
              prepare(display);
              display.setBlock(Material.WHITE_CONCRETE.createBlockData());
              display.setTransformation(
                  new Transformation(
                      new Vector3f(),
                      new AxisAngle4f(),
                      new Vector3f(dx + THICKNESS, dy + THICKNESS, dz + THICKNESS),
                      new AxisAngle4f()));
              display.setGlowing(true);
              display.setGlowColorOverride(colour);
              display.setBrightness(new Display.Brightness(15, 15));
            });
    viewer.showEntity(plugin, edge);
    return edge;
  }

  private void prepare(Entity entity) {
    entity.setVisibleByDefault(false);
    entity.setPersistent(false);
    entity.setSilent(true);
    entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
  }

  /** Takes away a leaving admin's outlines. */
  @EventHandler
  void onQuit(PlayerQuitEvent event) {
    Map<Outline, List<Entity>> gone = shown.remove(event.getPlayer().getUniqueId());
    if (gone != null) {
      gone.values().forEach(entities -> entities.forEach(Entity::remove));
    }
  }

  /** An outline entity is never saved; should one ever load, it goes. */
  @EventHandler
  void onEntitiesLoad(EntitiesLoadEvent event) {
    for (Entity entity : event.getEntities()) {
      if (entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
        entity.remove();
      }
    }
  }

  /** Every outline, when Seasons stops. */
  void clear() {
    shown.values().forEach(boxes -> boxes.values().forEach(es -> es.forEach(Entity::remove)));
    shown.clear();
  }
}
