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

  /**
   * Opens a written book for a player to read, without any item: nothing is given or left behind.
   *
   * @param pages each page's lines
   */
  void openBook(
      PlayerId id, Message title, Message author, java.util.List<java.util.List<Message>> pages);

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

  GameMode gameMode(PlayerId id);

  void setGameMode(PlayerId id, GameMode mode);

  /** The hotbar slot (0 to 8) in an online player's main hand. */
  int heldSlot(PlayerId id);

  /** The entity an online player is looking at, within {@code range} blocks. */
  Optional<java.util.UUID> targetEntity(PlayerId id, int range);

  /** The block an online player is looking at, within {@code range} blocks. */
  Optional<Position> targetBlock(PlayerId id, int range);

  /**
   * The name of a player the server has seen, online or not, even before Seasons was installed;
   * empty for someone who has never played here.
   */
  Optional<String> knownName(PlayerId id);

  /**
   * Teleports an online player safely: dismounts them, stops elytra flight, resets velocity, fall
   * distance and fire, closes menus and loads the destination chunk first, crossing dimensions if
   * needed. Completes with false if they couldn't be moved.
   */
  CompletableFuture<Boolean> teleportSafely(PlayerId id, Position destination);

  /** How an online player is moving: riding, gliding, swimming, burning. */
  Movement movement(PlayerId id);

  /** Where an online player would respawn: their bed, anchor or set spawn point, if usable. */
  Optional<Position> respawnPoint(PlayerId id);

  /** The bed block an online player's respawn point is at, if it is a bed. */
  Optional<Position> bed(PlayerId id);

  /** A title, with no subtitle, and a line on the action bar for one player. */
  void alert(PlayerId id, Message title, Message actionBar);

  /** Stops an online player where they are: off any mount, out of elytra flight, no velocity. */
  void stopMoving(PlayerId id);

  /**
   * Shows one online player a world border of their own, visual only, or with none puts back the
   * border of the world they are in. The world's own border never changes.
   */
  void showBorder(PlayerId id, Optional<BorderSquare> border);

  /** Celebration fireworks at a position, which hurt nobody. */
  void fireworks(Position at);

  /** A title, a sound and fireworks for one player, such as at launch. */
  void celebrate(PlayerId id, Message title, Message subtitle);

  /**
   * Creates a scoreboard objective counting deaths, if it doesn't exist, and shows it.
   *
   * @param slot where it shows: {@code list}, {@code sidebar}, {@code below_name} or {@code none}
   */
  void deathsObjective(String name, Message displayName, String slot);
}
