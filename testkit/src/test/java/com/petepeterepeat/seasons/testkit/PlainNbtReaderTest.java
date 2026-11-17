// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class PlainNbtReaderTest {
  /** {DataVersion: 4790, id: "minecraft:dirt", count: 64b, tags: [1, 2]} as raw named NBT. */
  private static byte[] sample() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(10);
      out.writeUTF("");
      out.writeByte(3);
      out.writeUTF("DataVersion");
      out.writeInt(4790);
      out.writeByte(8);
      out.writeUTF("id");
      out.writeUTF("minecraft:dirt");
      out.writeByte(1);
      out.writeUTF("count");
      out.writeByte(64);
      out.writeByte(9);
      out.writeUTF("tags");
      out.writeByte(3);
      out.writeInt(2);
      out.writeInt(1);
      out.writeInt(2);
      out.writeByte(0);
    }
    return bytes.toByteArray();
  }

  @Test
  void readsRawNbt() throws IOException {
    Map<String, Object> root = PlainNbtReader.read(sample());

    assertThat(root)
        .containsEntry("DataVersion", 4790)
        .containsEntry("id", "minecraft:dirt")
        .containsEntry("count", (byte) 64)
        .containsEntry("tags", List.of(1, 2));
  }

  @Test
  void readsGzippedNbt() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(sample());
    }

    assertThat(PlainNbtReader.read(bytes.toByteArray())).containsEntry("DataVersion", 4790);
  }

  @Test
  void refusesARootThatIsNotACompound() {
    assertThatThrownBy(() -> PlainNbtReader.read(new byte[] {8, 0, 0, 0, 0}))
        .isInstanceOf(IOException.class);
  }
}
