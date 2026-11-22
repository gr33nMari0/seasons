// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.petepeterepeat.seasons.core.scenario.ScenarioHooks;
import com.petepeterepeat.seasons.core.scenario.TickMeter;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/**
 * {@code /scenario perf start|stop} for the performance scenario A43: marks each server tick for
 * Seasons' {@link TickMeter} and times every event listener Seasons registered. Seasons' own hooks
 * time its scheduled tasks and display calls (docs/adr/0003).
 *
 * <p>The first {@code start} puts a timing wrapper around each of Seasons' listeners, through the
 * public {@link HandlerList} API, and leaves it there for the rest of the server's life, which is
 * why A43 has a server of its own. Every handler list Seasons is in is rebuilt in its own order, so
 * each listener, Seasons' or another plugin's, still runs exactly where it ran before.
 */
final class ScenarioPerf implements Listener {
  private final Plugin plugin;
  private boolean wrapped;

  ScenarioPerf(Plugin plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.LOWEST)
  void onTickStart(ServerTickStartEvent event) {
    hooks().ifPresent(hooks -> hooks.meter().tickStarted());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  void onTickEnd(ServerTickEndEvent event) {
    hooks().ifPresent(hooks -> hooks.meter().tickEnded());
  }

  private java.util.Optional<ScenarioHooks> hooks() {
    var registration = plugin.getServer().getServicesManager().getRegistration(ScenarioHooks.class);
    return java.util.Optional.ofNullable(registration).map(r -> r.getProvider());
  }

  void run(CommandSender sender, ScenarioHooks hooks, String[] args) {
    switch (args.length > 1 ? args[1] : "") {
      case "start" -> {
        if (!wrapped) {
          int count = wrapListeners(hooks.meter());
          wrapped = true;
          plugin.getSLF4JLogger().info("Timing {} of Seasons' event listeners for A43", count);
        }
        hooks.meter().start();
        sender.sendMessage("Perf meter started");
      }
      case "stop" -> {
        TickMeter.Report report = hooks.meter().stop();
        plugin.getSLF4JLogger().info(report.line());
        plugin.getSLF4JLogger().info("Perf slowest: {}", String.join(", ", report.slowest()));
        sender.sendMessage(report.line());
        sender.sendMessage("Perf slowest: " + String.join(", ", report.slowest()));
      }
      case "warm" -> {
        warm(sender);
        sender.sendMessage("Perf warmed");
      }
      default -> sender.sendMessage("Usage: /scenario perf warm|start|stop");
    }
  }

  /**
   * Spawns and removes, through the Paper API, the kinds of display Seasons draws, dressed as it
   * dresses them: the first of each a server ever makes loads Paper's classes for it and warms the
   * game's item data fixer, a one-off cost of the game's, not Seasons' (docs/questions.md 289).
   */
  private static void warm(CommandSender sender) {
    if (!(sender instanceof org.bukkit.entity.Player player)) {
      throw new IllegalStateException("perf warm runs as a player");
    }
    org.bukkit.Location at = java.util.Objects.requireNonNull(player.getLocation()).add(3, 0, 0);
    org.bukkit.inventory.ItemStack helmet =
        org.bukkit.inventory.ItemStack.deserializeBytes(
            new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_HELMET)
                .serializeAsBytes());
    org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey("seasons_testkit", "warm");
    helmet.editPersistentDataContainer(
        data -> data.set(key, org.bukkit.persistence.PersistentDataType.BOOLEAN, true));
    List<org.bukkit.entity.Entity> spawned = new ArrayList<>();
    spawned.add(
        at.getWorld()
            .spawn(
                at,
                org.bukkit.entity.Mannequin.class,
                m -> {
                  m.customName(net.kyori.adventure.text.Component.text("Warm"));
                  m.setCustomNameVisible(true);
                  m.setImmovable(true);
                  m.setAI(false);
                  m.setGravity(false);
                  m.setInvulnerable(true);
                  m.setSilent(true);
                  m.setCollidable(false);
                  m.setPose(org.bukkit.entity.Pose.STANDING, true);
                  // Every slot a figure fills, empty ones included, as Seasons fills them.
                  for (org.bukkit.inventory.EquipmentSlot slot :
                      List.of(
                          org.bukkit.inventory.EquipmentSlot.HEAD,
                          org.bukkit.inventory.EquipmentSlot.CHEST,
                          org.bukkit.inventory.EquipmentSlot.LEGS,
                          org.bukkit.inventory.EquipmentSlot.FEET,
                          org.bukkit.inventory.EquipmentSlot.HAND,
                          org.bukkit.inventory.EquipmentSlot.OFF_HAND)) {
                    m.getEquipment()
                        .setItem(
                            slot,
                            slot == org.bukkit.inventory.EquipmentSlot.HEAD
                                ? helmet
                                : org.bukkit.inventory.ItemStack.empty());
                  }
                  m.getPersistentDataContainer()
                      .set(key, org.bukkit.persistence.PersistentDataType.STRING, "warm");
                }));
    spawned.add(
        at.getWorld()
            .spawn(
                at.clone().add(0, 2, 0),
                org.bukkit.entity.TextDisplay.class,
                d -> {
                  d.text(
                      net.kyori.adventure.text.Component.text("Warm")
                          .append(net.kyori.adventure.text.Component.newline())
                          .compact());
                  d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                  org.bukkit.util.Transformation now = d.getTransformation();
                  d.setTransformation(
                      new org.bukkit.util.Transformation(
                          now.getTranslation(),
                          now.getLeftRotation(),
                          new org.joml.Vector3f(1.5f, 1.5f, 1.5f),
                          now.getRightRotation()));
                  d.getPersistentDataContainer()
                      .set(key, org.bukkit.persistence.PersistentDataType.STRING, "warm");
                }));
    spawned.add(
        at.getWorld()
            .spawn(
                at.clone().add(0, 1, 0),
                org.bukkit.entity.ItemDisplay.class,
                d -> d.setItemStack(helmet)));
    spawned.forEach(org.bukkit.entity.Entity::remove);
  }

  /** Wraps every listener Seasons registered in a timer; returns how many. */
  private int wrapListeners(TickMeter meter) {
    Plugin seasons = plugin.getServer().getPluginManager().getPlugin("Seasons");
    if (seasons == null) {
      throw new IllegalStateException("Seasons isn't installed");
    }
    int count = 0;
    for (HandlerList list : HandlerList.getHandlerLists()) {
      RegisteredListener[] registered = list.getRegisteredListeners();
      boolean touched = false;
      for (RegisteredListener listener : registered) {
        touched |= listener.getPlugin().equals(seasons);
      }
      if (!touched) {
        continue;
      }
      // Rebuilt whole, in the order it ran, so nobody's place within a priority moves.
      List<RegisteredListener> rebuilt = new ArrayList<>(registered.length);
      for (RegisteredListener listener : registered) {
        if (listener.getPlugin().equals(seasons)) {
          rebuilt.add(timed(listener, meter));
          count++;
        } else {
          rebuilt.add(listener);
        }
      }
      for (RegisteredListener listener : registered) {
        list.unregister(listener);
      }
      list.registerAll(rebuilt);
    }
    return count;
  }

  /** The same listener, timed as Seasons' work; the original still checks cancellation. */
  private static RegisteredListener timed(RegisteredListener original, TickMeter meter) {
    return new RegisteredListener(
        original.getListener(),
        (listener, event) -> call(original, event, meter),
        original.getPriority(),
        original.getPlugin(),
        false);
  }

  private static void call(RegisteredListener original, Event event, TickMeter meter)
      throws EventException {
    EventException[] failed = new EventException[1];
    meter.measure(
        TickMeter.Kind.LISTENER,
        original.getListener().getClass().getSimpleName() + " " + event.getEventName(),
        () -> {
          try {
            original.callEvent(event);
          } catch (EventException e) {
            failed[0] = e;
          }
        });
    if (failed[0] != null) {
      throw failed[0];
    }
  }
}
