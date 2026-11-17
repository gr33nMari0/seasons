// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
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
}
