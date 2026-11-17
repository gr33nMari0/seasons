// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store.nbt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NbtTest {
  @Test
  void roundTripsEveryTypeArchivesUse() throws Exception {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("byte", (byte) 1);
    root.put("int", 7);
    root.put("long", 1L << 40);
    root.put("float", 0.5f);
    root.put("double", 2.25);
    root.put("string", "Season 2 × sword");
    root.put("compound", Map.of("id", "minecraft:dirt"));
    root.put("list", Nbt.NbtList.ofCompounds(List.of(Map.of("slot", 3))));

    Map<String, Object> read = Nbt.read(Nbt.writeCompressed(root));

    assertThat(read).containsAllEntriesOf(Map.of("byte", (byte) 1, "int", 7, "long", 1L << 40));
    assertThat(read.get("string")).isEqualTo("Season 2 × sword");
    assertThat(((Nbt.NbtList) read.get("list")).compounds()).containsExactly(Map.of("slot", 3));
  }

  @Test
  void theCanonicalFormIgnoresKeyOrder() {
    Map<String, Object> one = new LinkedHashMap<>();
    one.put("a", 1);
    one.put("b", "x");
    Map<String, Object> two = new LinkedHashMap<>();
    two.put("b", "x");
    two.put("a", 1);

    assertThat(Nbt.canonical(one)).isEqualTo(Nbt.canonical(two));
  }
}
