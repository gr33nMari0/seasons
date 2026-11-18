// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.Figure;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.Position;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

/**
 * Seasons' own entities: text and item displays, and mannequin figures. Each carries its tag under
 * {@code seasons:display}, so redraws find it and {@link PaperEvents} cancels every interaction
 * with it. Items they show are throwaway copies tagged {@code seasons:archived}.
 */
final class PaperDisplays implements Displays {
  private static final List<EquipmentSlot> FIGURE_SLOTS =
      List.of(
          EquipmentSlot.HEAD,
          EquipmentSlot.CHEST,
          EquipmentSlot.LEGS,
          EquipmentSlot.FEET,
          EquipmentSlot.HAND,
          EquipmentSlot.OFF_HAND);

  /** A name a profile accepts: at most 16 characters. */
  private static final int PROFILE_NAME = 16;

  private final Server server;
  private final PaperText text;
  private final PaperItemCodec codec;
  private final NamespacedKey tagKey;
  private final NamespacedKey archivedKey;

  PaperDisplays(
      Server server,
      PaperText text,
      PaperItemCodec codec,
      NamespacedKey tagKey,
      NamespacedKey archivedKey) {
    this.server = server;
    this.text = text;
    this.codec = codec;
    this.tagKey = tagKey;
    this.archivedKey = archivedKey;
  }

  @Override
  public UUID spawnText(Position position, Message message, String tag) {
    Location location = PaperPositions.toLocation(position);
    TextDisplay display =
        location
            .getWorld()
            .spawn(
                location,
                TextDisplay.class,
                spawned -> {
                  // Compact, so /data and clients see one text component, not a split one.
                  spawned.text(text.render(message).compact());
                  spawned.setBillboard(Display.Billboard.CENTER);
                  tag(spawned, tag);
                });
    return display.getUniqueId();
  }

  @Override
  public UUID spawnFigure(Figure figure) {
    Location location = PaperPositions.toLocation(figure.at());
    Mannequin mannequin =
        location
            .getWorld()
            .spawn(
                location,
                Mannequin.class,
                spawned -> {
                  figure.skin().ifPresent(skin -> spawned.setProfile(profile(figure, skin)));
                  spawned.customName(text.render(figure.name()).compact());
                  spawned.setCustomNameVisible(true);
                  spawned.setImmovable(true);
                  spawned.setInvulnerable(true);
                  spawned.setGravity(false);
                  spawned.setSilent(true);
                  spawned.setAI(false);
                  spawned.setCollidable(false);
                  EntityEquipment equipment = spawned.getEquipment();
                  for (int i = 0; i < FIGURE_SLOTS.size() && i < figure.equipment().size(); i++) {
                    EquipmentSlot slot = FIGURE_SLOTS.get(i);
                    equipment.setItem(slot, copy(figure.equipment().get(i)));
                  }
                  tag(spawned, figure.tag());
                });
    return mannequin.getUniqueId();
  }

  private static ResolvableProfile profile(
      Figure figure, com.petepeterepeat.seasons.core.port.Skin skin) {
    String name = figure.tag().replaceAll("[^A-Za-z0-9_]", "_");
    return ResolvableProfile.resolvableProfile()
        .name(name.substring(0, Math.min(PROFILE_NAME, name.length())))
        .uuid(
            UUID.nameUUIDFromBytes(figure.tag().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .addProperty(new ProfileProperty("textures", skin.value(), skin.signature().orElse(null)))
        .build();
  }

  @Override
  public UUID spawnItem(Position position, NeutralItem item, String tag) {
    Location location = PaperPositions.toLocation(position);
    ItemDisplay display =
        location
            .getWorld()
            .spawn(
                location,
                ItemDisplay.class,
                spawned -> {
                  spawned.setItemStack(copy(Optional.of(item)));
                  spawned.setBillboard(Display.Billboard.VERTICAL);
                  tag(spawned, tag);
                });
    return display.getUniqueId();
  }

  @Override
  public void remove(UUID id) {
    Optional.ofNullable(server.getEntity(id))
        .filter(entity -> entity.getPersistentDataContainer().has(tagKey))
        .ifPresent(Entity::remove);
  }

  @Override
  public int removeTagged(Position min, Position max, String tagPrefix) {
    Optional<World> world = PaperPositions.world(min.dimension());
    if (world.isEmpty()) {
      return 0;
    }
    int removed = 0;
    for (Entity entity : world.get().getNearbyEntities(box(min, max))) {
      String tag = entity.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
      if (tag != null && tag.startsWith(tagPrefix)) {
        entity.remove();
        removed++;
      }
    }
    return removed;
  }

  @Override
  public List<Shown> tagged(Position min, Position max) {
    Optional<World> world = PaperPositions.world(min.dimension());
    if (world.isEmpty()) {
      return List.of();
    }
    List<Shown> shown = new ArrayList<>();
    for (Entity entity : world.get().getNearbyEntities(box(min, max))) {
      tagOf(entity)
          .ifPresent(tag ->
              shown.add(
                  new Shown(
                      entity.getUniqueId(), tag, PaperPositions.toPosition(entity.getLocation()))));
    }
    return shown;
  }

  @Override
  public boolean wears(UUID figure, List<Optional<NeutralItem>> equipment) {
    if (!(server.getEntity(figure) instanceof LivingEntity living)) {
      return false;
    }
    EntityEquipment worn = living.getEquipment();
    if (worn == null) {
      return false;
    }
    for (int i = 0; i < FIGURE_SLOTS.size(); i++) {
      ItemStack expected = copy(i < equipment.size() ? equipment.get(i) : Optional.empty());
      ItemStack actual = worn.getItem(FIGURE_SLOTS.get(i));
      if (expected.isEmpty() != actual.isEmpty()
          || (!expected.isEmpty()
              && (!expected.isSimilar(actual) || expected.getAmount() != actual.getAmount()))) {
        return false;
      }
    }
    return true;
  }

  /** The tag a display was spawned with, if it is one of Seasons' own. */
  Optional<String> tagOf(Entity entity) {
    return Optional.ofNullable(
        entity.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING));
  }

  /** The box of whole blocks between two corners. */
  private static BoundingBox box(Position min, Position max) {
    return new BoundingBox(
        min.blockX(),
        min.blockY(),
        min.blockZ(),
        max.blockX() + 1,
        max.blockY() + 1,
        max.blockZ() + 1);
  }

  private void tag(Entity entity, String tag) {
    entity.setPersistent(true);
    entity.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, tag);
  }

  /** A throwaway copy of an archived item, tagged so the tripwire knows it anywhere else. */
  private ItemStack copy(Optional<NeutralItem> item) {
    ItemStack copy = codec.decode(item);
    if (!copy.isEmpty()) {
      copy.editPersistentDataContainer(
          data -> data.set(archivedKey, PersistentDataType.BOOLEAN, true));
    }
    return copy;
  }
}
