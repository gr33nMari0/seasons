// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The {@code seasons:archived} tag on throwaway copies, looked for inside items too (security
 * review M3): a copy of a shulker box or bundle has its contents tagged as well, and an archived
 * copy hidden inside an untagged shulker box or bundle is found and taken out.
 */
final class ArchivedTag {
  /** How deep items inside items are looked at: a bundle in a shulker box, and so on. */
  private static final int DEPTH = 3;

  private final NamespacedKey key;

  ArchivedTag(NamespacedKey key) {
    this.key = key;
  }

  /** Tags a copy, and every item inside it. Returns the same stack. */
  ItemStack tag(ItemStack copy) {
    tag(copy, DEPTH);
    return copy;
  }

  private void tag(ItemStack stack, int depth) {
    if (stack.isEmpty()) {
      return;
    }
    stack.editPersistentDataContainer(data -> data.set(key, PersistentDataType.BOOLEAN, true));
    if (depth > 0) {
      editNested(stack, nested -> tag(nested, depth - 1));
    }
  }

  /** Whether the stack itself carries the tag. */
  boolean isTagged(@Nullable ItemStack stack) {
    return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(key);
  }

  /** Whether the stack, or anything inside it, carries the tag. */
  boolean isArchived(@Nullable ItemStack stack) {
    return isArchived(stack, DEPTH);
  }

  private boolean isArchived(@Nullable ItemStack stack, int depth) {
    if (isTagged(stack)) {
      return true;
    }
    if (stack == null || stack.isEmpty() || depth == 0) {
      return false;
    }
    for (ItemStack nested : nested(stack)) {
      if (isArchived(nested, depth - 1)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Takes every tagged item out of what is inside an untagged stack, keeping the rest.
   *
   * @return what was taken, such as "3 × minecraft:stone"
   */
  List<String> stripNested(ItemStack stack) {
    List<String> removed = new ArrayList<>();
    strip(stack, DEPTH, removed);
    return removed;
  }

  private void strip(ItemStack stack, int depth, List<String> removed) {
    if (depth == 0 || stack.isEmpty()) {
      return;
    }
    if (stack.getItemMeta() instanceof BundleMeta) {
      stack.editMeta(
          BundleMeta.class,
          meta -> {
            List<ItemStack> kept = new ArrayList<>();
            for (ItemStack inside : meta.getItems()) {
              if (isTagged(inside)) {
                removed.add(PaperInventories.describe(inside));
              } else {
                strip(inside, depth - 1, removed);
                kept.add(inside);
              }
            }
            meta.setItems(kept);
          });
    } else if (stack.getItemMeta() instanceof BlockStateMeta blockMeta
        && blockMeta.hasBlockState()
        && blockMeta.getBlockState() instanceof Container) {
      stack.editMeta(
          BlockStateMeta.class,
          meta -> {
            Container container = (Container) meta.getBlockState();
            Inventory inventory = container.getSnapshotInventory();
            @Nullable ItemStack[] contents = inventory.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
              ItemStack inside = contents[slot];
              if (inside == null || inside.isEmpty()) {
                continue;
              }
              if (isTagged(inside)) {
                removed.add(PaperInventories.describe(inside));
                inventory.setItem(slot, null);
              } else {
                strip(inside, depth - 1, removed);
                inventory.setItem(slot, inside);
              }
            }
            meta.setBlockState(container);
          });
    }
  }

  /** The items inside a bundle or a container item such as a shulker box. */
  private static List<ItemStack> nested(ItemStack stack) {
    if (stack.getItemMeta() instanceof BundleMeta bundle) {
      return bundle.getItems();
    }
    if (stack.getItemMeta() instanceof BlockStateMeta blockMeta
        && blockMeta.hasBlockState()
        && blockMeta.getBlockState() instanceof Container container) {
      List<ItemStack> inside = new ArrayList<>();
      for (ItemStack item : container.getSnapshotInventory().getContents()) {
        if (item != null && !item.isEmpty()) {
          inside.add(item);
        }
      }
      return inside;
    }
    return List.of();
  }

  /** Changes each item inside a bundle or container item, and writes them back. */
  private static void editNested(ItemStack stack, java.util.function.Consumer<ItemStack> edit) {
    if (stack.getItemMeta() instanceof BundleMeta) {
      stack.editMeta(
          BundleMeta.class,
          meta -> {
            List<ItemStack> items = new ArrayList<>(meta.getItems());
            items.forEach(edit);
            meta.setItems(items);
          });
    } else if (stack.getItemMeta() instanceof BlockStateMeta blockMeta
        && blockMeta.hasBlockState()
        && blockMeta.getBlockState() instanceof Container) {
      stack.editMeta(
          BlockStateMeta.class,
          meta -> {
            Container container = (Container) meta.getBlockState();
            Inventory inventory = container.getSnapshotInventory();
            @Nullable ItemStack[] contents = inventory.getContents();
            for (int slot = 0; slot < contents.length; slot++) {
              ItemStack inside = contents[slot];
              if (inside != null && !inside.isEmpty()) {
                edit.accept(inside);
                inventory.setItem(slot, inside);
              }
            }
            meta.setBlockState(container);
          });
    }
  }
}
