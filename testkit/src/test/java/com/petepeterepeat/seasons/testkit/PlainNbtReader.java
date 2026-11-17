// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.testkit;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A plain NBT reader with no Minecraft or platform code, to prove archived item data is neutral:
 * anything that speaks NBT can open it. Reads gzipped or raw named NBT into maps, lists, arrays and
 * boxed numbers.
 */
final class PlainNbtReader {
  private static final int TAG_COMPOUND = 10;

  private PlainNbtReader() {}

  /** Reads the root compound. */
  static Map<String, Object> read(byte[] bytes) throws IOException {
    InputStream in = new BufferedInputStream(new ByteArrayInputStream(bytes));
    if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
      in = new GZIPInputStream(in);
    }
    try (DataInputStream data = new DataInputStream(in)) {
      int type = data.readUnsignedByte();
      if (type != TAG_COMPOUND) {
        throw new IOException("Root tag is type " + type + ", not a compound");
      }
      data.readUTF();
      @SuppressWarnings("unchecked")
      Map<String, Object> root = (Map<String, Object>) payload(data, TAG_COMPOUND);
      if (data.read() != -1) {
        throw new IOException("Trailing bytes after the root compound");
      }
      return root;
    }
  }

  private static Object payload(DataInputStream in, int type) throws IOException {
    return switch (type) {
      case 1 -> in.readByte();
      case 2 -> in.readShort();
      case 3 -> in.readInt();
      case 4 -> in.readLong();
      case 5 -> in.readFloat();
      case 6 -> in.readDouble();
      case 7 -> {
        byte[] array = new byte[length(in)];
        in.readFully(array);
        yield array;
      }
      case 8 -> in.readUTF();
      case 9 -> {
        int elementType = in.readUnsignedByte();
        int size = length(in);
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
          list.add(payload(in, elementType));
        }
        yield list;
      }
      case TAG_COMPOUND -> {
        Map<String, Object> compound = new LinkedHashMap<>();
        for (int child = in.readUnsignedByte(); child != 0; child = in.readUnsignedByte()) {
          compound.put(in.readUTF(), payload(in, child));
        }
        yield compound;
      }
      case 11 -> {
        int[] array = new int[length(in)];
        for (int i = 0; i < array.length; i++) {
          array[i] = in.readInt();
        }
        yield array;
      }
      case 12 -> {
        long[] array = new long[length(in)];
        for (int i = 0; i < array.length; i++) {
          array[i] = in.readLong();
        }
        yield array;
      }
      default -> throw new IOException("Unknown tag type " + type);
    };
  }

  private static int length(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0) {
      throw new IOException("Negative length " + length);
    }
    return length;
  }
}
