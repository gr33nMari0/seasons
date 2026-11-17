// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Online players, profiles and skins, game mode, health, XP, effects, messages and titles. */
public interface Players {
  Collection<PlayerRef> online();

  Optional<PlayerRef> find(PlayerId id);

  /** An online player by their current name, ignoring case. */
  Optional<PlayerRef> findByName(String name);

  /** Sends a message. Placeholders are always inserted as literal text. */
  void sendMessage(PlayerId id, Message message);

  /** Sends a message to every online player and the console. */
  void broadcast(Message message);

  /**
   * Shows a boss bar to every online player.
   *
   * @param progress from 0 (empty) to 1 (full)
   */
  ProgressBar showProgressBar(Message title, float progress);

  /** Where an online player stands. */
  Position position(PlayerId id);

  Experience experience(PlayerId id);

  /** Puts experience back, as {@code /season unarchive} does. */
  void setExperience(PlayerId id, Experience experience);

  /** The skin textures of an online player's game profile, if it has any. */
  Optional<Skin> skin(PlayerId id);

  /**
   * Writes an online player's file (inventory, ender chest, XP and the rest) to disk now, so what a
   * restart would load matches what Seasons has seen.
   */
  void saveData(PlayerId id);

  /** Resets an online player for a new season: fire out, full health and hunger, and the plan. */
  void reset(PlayerId id, ResetPlan plan);

  void setSurvival(PlayerId id);

  /**
   * Teleports an online player safely: dismounts them, stops elytra flight, resets fall distance
   * and fire, closes menus and loads the destination chunk first, crossing dimensions if needed.
   * Completes with false if they couldn't be moved.
   */
  CompletableFuture<Boolean> teleportSafely(PlayerId id, Position destination);

  /** A title, a sound and fireworks for one player, such as at launch. */
  void celebrate(PlayerId id, Message title, Message subtitle);

  /**
   * Creates a scoreboard objective counting deaths, if it doesn't exist, and shows it.
   *
   * @param slot where it shows: {@code list}, {@code sidebar}, {@code below_name} or {@code none}
   */
  void deathsObjective(String name, Message displayName, String slot);
}
