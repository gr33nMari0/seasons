// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rule 1 of the multi-platform design: item data the Paper adapter writes is vanilla NBT with its
 * {@code DataVersion}, readable with no Minecraft code at all. The scenario servers write the
 * samples; {@code seasons.nbtSamples} lists their directories.
 */
@Tag("scenario")
class NbtNeutralityTest {
  static Stream<Path> samples() throws IOException {
    String dirs = System.getProperty("seasons.nbtSamples", "");
    List<Path> files =
        Stream.of(dirs.split(File.pathSeparator))
            .filter(dir -> !dir.isBlank())
            .map(Path::of)
            .flatMap(NbtNeutralityTest::list)
            .toList();
    assertThat(files).as("item samples written by the scenario servers in " + dirs).isNotEmpty();
    return files.stream();
  }

  /** Archive and vault files the scenario servers wrote, copied beside the item samples. */
  static Stream<Path> archives() {
    String dirs = System.getProperty("seasons.nbtSamples", "");
    List<Path> files =
        Stream.of(dirs.split(File.pathSeparator))
            .filter(dir -> !dir.isBlank())
            .map(dir -> Path.of(dir, "archives"))
            .filter(Files::isDirectory)
            .flatMap(NbtNeutralityTest::list)
            .toList();
    assertThat(files).as("archives written by the scenario servers in " + dirs).isNotEmpty();
    return files.stream();
  }

  private static Stream<Path> list(Path dir) {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(f -> f.toString().endsWith(".dat")).sorted().toList().stream();
    } catch (IOException e) {
      throw new IllegalStateException("Can't list " + dir, e);
    }
  }

  @ParameterizedTest
  @MethodSource("samples")
  void opensWithAPlainNbtReaderAndHoldsADataVersion(Path file) throws IOException {
    Map<String, Object> root = PlainNbtReader.read(Files.readAllBytes(file));

    assertThat(root.get("DataVersion")).isInstanceOf(Integer.class);
    assertThat((Integer) root.get("DataVersion")).isPositive();
    assertThat(root.get("id")).asString().startsWith("minecraft:");
  }

  /**
   * A10: archives and vault pages open the same way, and every item in them is vanilla NBT. Some
   * players, like the admin bot, are archived with nothing; {@link #theArchivesHoldItems} checks
   * that the run archived items at all.
   */
  @ParameterizedTest
  @MethodSource("archives")
  void archivesOpenWithAPlainNbtReader(Path file) throws IOException {
    Map<String, Object> root = PlainNbtReader.read(Files.readAllBytes(file));

    assertThat(root.get("DataVersion")).isInstanceOf(Integer.class);
    assertThat((Integer) root.get("DataVersion")).isPositive();
    List<Map<?, ?>> items = new ArrayList<>();
    collectItems(root, items);
    assertThat(items)
        .allSatisfy(item -> assertThat(item.get("id")).asString().startsWith("minecraft:"));
  }

  @Test
  void theArchivesHoldItems() throws IOException {
    List<Map<?, ?>> items = new ArrayList<>();
    for (Path file : archives().toList()) {
      collectItems(PlainNbtReader.read(Files.readAllBytes(file)), items);
    }
    assertThat(items).as("items in any archive").isNotEmpty();
  }

  /** Every compound stored under an "item" key, however deep. */
  private static void collectItems(Object node, List<Map<?, ?>> items) {
    if (node instanceof Map<?, ?> map) {
      map.forEach((key, value) -> {
        if ("item".equals(key) && value instanceof Map<?, ?> item) {
          items.add(item);
        } else {
          collectItems(value, items);
        }
      });
    } else if (node instanceof List<?> list) {
      list.forEach(element -> collectItems(element, items));
    }
  }
}
