// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.api.ArchiveView;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemTextTest {
  private static NeutralItem item(Map<String, Object> root) {
    return new NeutralItem(Nbt.writeCompressed(root));
  }

  @Test
  void aRenamedEnchantedSword() {
    Map<String, Object> components = new LinkedHashMap<>();
    components.put("minecraft:custom_name", "Edge");
    components.put("minecraft:enchantments", Map.of("minecraft:sharpness", 5));
    ArchiveView.ItemView view =
        ItemText.describe(
                "storage",
                item(
                    Map.of(
                        "id",
                        "minecraft:diamond_sword",
                        "count",
                        1,
                        "components",
                        components,
                        "DataVersion",
                        4500)))
            .orElseThrow();
    assertThat(view.id()).isEqualTo("minecraft:diamond_sword");
    assertThat(view.section()).isEqualTo("storage");
    assertThat(view.count()).isEqualTo(1);
    assertThat(view.name()).contains("Edge");
    assertThat(view.enchantments())
        .containsExactly(new ArchiveView.Enchantment("minecraft:sharpness", 5));
    assertThat(view.trim()).isEmpty();
  }

  @Test
  void trimmedArmourAndAnOlderNameAndLevels() {
    Map<String, Object> components = new LinkedHashMap<>();
    components.put(
        "minecraft:custom_name",
        Map.of(
            "text", "Shiny ", "extra", Nbt.NbtList.ofCompounds(List.of(Map.of("text", "Plate")))));
    components.put(
        "minecraft:enchantments",
        Map.of("levels", Map.of("minecraft:protection", 4), "show_in_tooltip", (byte) 1));
    components.put(
        "minecraft:trim", Map.of("pattern", "minecraft:coast", "material", "minecraft:gold"));
    ArchiveView.ItemView view =
        ItemText.describe(
                "armour",
                item(
                    Map.of(
                        "id",
                        "minecraft:diamond_chestplate",
                        "count",
                        1,
                        "components",
                        components)))
            .orElseThrow();
    assertThat(view.name()).contains("Shiny Plate");
    assertThat(view.enchantments())
        .containsExactly(new ArchiveView.Enchantment("minecraft:protection", 4));
    assertThat(view.trim()).contains(new ArchiveView.Trim("minecraft:coast", "minecraft:gold"));
  }

  @Test
  void aNameAsJsonIsPlainTextNeverFormatting() {
    assertThat(ItemText.text("{\"text\":\"<b>Hi</b>\",\"bold\":true}")).isEqualTo("<b>Hi</b>");
    assertThat(ItemText.text("\"Quoted\"")).isEqualTo("Quoted");
  }

  @Test
  void aBookKeepsItsStoredEnchantmentsAndAStackItsCount() {
    ArchiveView.ItemView view =
        ItemText.describe(
                "storage",
                item(
                    Map.of(
                        "id",
                        "minecraft:enchanted_book",
                        "count",
                        3,
                        "components",
                        Map.of("minecraft:stored_enchantments", Map.of("minecraft:mending", 1)))))
            .orElseThrow();
    assertThat(view.count()).isEqualTo(3);
    assertThat(view.name()).isEmpty();
    assertThat(view.enchantments())
        .containsExactly(new ArchiveView.Enchantment("minecraft:mending", 1));
  }

  @Test
  void unreadableDataIsLeftOut() {
    assertThat(ItemText.describe("storage", new NeutralItem(new byte[] {1, 2, 3}))).isEmpty();
    assertThat(ItemText.describe("storage", item(Map.of("count", 1)))).isEmpty();
  }
}
