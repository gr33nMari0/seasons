// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import com.petepeterepeat.seasons.core.port.InventorySnapshot;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The content hash the journal's {@code ARCHIVE_STARTED} entry carries: SHA-256 over the slots
 * archiving clears, in slot order, each item in canonical NBT. Crash recovery compares it with the
 * player's live inventory, so it covers only what survives a crash in the player's file: storage,
 * armour, off hand and, unless the policy keeps it, the ender chest. Never the cursor or crafting
 * grid, which the game doesn't save.
 */
public final class ArchiveHash {
  private ArchiveHash() {}

  public static String of(InventorySnapshot inventory, boolean includeEnderChest) {
    MessageDigest digest = sha256();
    section(digest, "storage", inventory.storage());
    section(digest, "armour", inventory.armour());
    section(digest, "offhand", List.of(inventory.offHand()));
    if (includeEnderChest) {
      section(digest, "ender", inventory.enderChest());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Whether every slot the hash covers is empty: what a cleared player looks like. */
  public static boolean isEmpty(InventorySnapshot inventory, boolean includeEnderChest) {
    return inventory.storage().stream().allMatch(Optional::isEmpty)
        && inventory.armour().stream().allMatch(Optional::isEmpty)
        && inventory.offHand().isEmpty()
        && (!includeEnderChest || inventory.enderChest().stream().allMatch(Optional::isEmpty));
  }

  private static void section(
      MessageDigest digest, String name, List<Optional<NeutralItem>> slots) {
    digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for (int slot = 0; slot < slots.size(); slot++) {
      Optional<NeutralItem> item = slots.get(slot);
      if (item.isEmpty()) {
        continue;
      }
      digest.update(Integer.toString(slot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update(canonical(item.get()));
    }
  }

  private static byte[] canonical(NeutralItem item) {
    try {
      Map<String, Object> compound = Nbt.read(item.nbt());
      return Nbt.canonical(compound);
    } catch (IOException e) {
      throw new UncheckedIOException("An item isn't valid NBT", e);
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
