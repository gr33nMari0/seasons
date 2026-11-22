// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** The archived tag inside bundles (security review M3); shulker boxes are covered end to end. */
class ArchivedTagTest {
  private final ArchivedTag tag = new ArchivedTag(new NamespacedKey("seasons", "archived"));

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void aTaggedCopyInsideAnUntaggedBundleIsFoundAndTakenOut() {
    ItemStack bundle = ItemStack.of(Material.BUNDLE);
    ItemStack copy = tag.tag(ItemStack.of(Material.DIAMOND));
    bundle.editMeta(
        BundleMeta.class,
        meta -> {
          meta.addItem(copy);
          meta.addItem(ItemStack.of(Material.STICK));
        });
    assertThat(tag.isTagged(bundle)).isFalse();
    assertThat(tag.isArchived(bundle)).isTrue();

    assertThat(tag.stripNested(bundle)).containsExactly("1 × minecraft:diamond");

    assertThat(tag.isArchived(bundle)).isFalse();
    assertThat(((BundleMeta) bundle.getItemMeta()).getItems())
        .singleElement()
        .extracting(ItemStack::getType)
        .isEqualTo(Material.STICK);
  }

  @Test
  void aCopyOfABundleIsTaggedInside() {
    ItemStack bundle = ItemStack.of(Material.BUNDLE);
    bundle.editMeta(BundleMeta.class, meta -> meta.addItem(ItemStack.of(Material.DIAMOND)));

    tag.tag(bundle);

    assertThat(tag.isTagged(bundle)).isTrue();
    assertThat(((BundleMeta) bundle.getItemMeta()).getItems())
        .allMatch(inside -> tag.isTagged(inside));
  }
}
