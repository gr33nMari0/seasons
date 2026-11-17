// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {
  @TempDir Path dir;

  @Test
  void replacesTheFileAndLeavesNoTempFiles() throws Exception {
    Path file = dir.resolve("a/b/season.json");

    AtomicFiles.write(file, "one".getBytes(UTF_8));
    AtomicFiles.write(file, "two".getBytes(UTF_8));

    assertThat(file).hasContent("two");
    try (Stream<Path> files = Files.list(file.getParent())) {
      assertThat(files).containsExactly(file);
    }
  }

  @Test
  void keepsTheLastFiveCopies() throws Exception {
    Path file = dir.resolve("seasons/2.yml");
    Path backups = dir.resolve("backups/seasons");

    for (int i = 1; i <= 8; i++) {
      AtomicFiles.writeKeepingBackups(file, ("v" + i).getBytes(UTF_8), backups);
    }

    assertThat(file).hasContent("v8");
    try (Stream<Path> copies = Files.list(backups).sorted()) {
      assertThat(copies.map(AtomicFilesTest::read)).containsExactly("v3", "v4", "v5", "v6", "v7");
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
