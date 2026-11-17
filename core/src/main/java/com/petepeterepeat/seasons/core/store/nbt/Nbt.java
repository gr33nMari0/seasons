// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.store.nbt;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Plain NBT, with no Minecraft code: the format of vanilla item data, archives and vaults. Values
 * are {@link Byte}, {@link Short}, {@link Integer}, {@link Long}, {@link Float}, {@link Double},
 * {@code byte[]}, {@link String}, {@link NbtList}, a compound as {@code Map<String, Object>},
 * {@code int[]} and {@code long[]}.
 */
public final class Nbt {
  private static final int END = 0;
  private static final int BYTE = 1;
  private static final int SHORT = 2;
  private static final int INT = 3;
  private static final int LONG = 4;
  private static final int FLOAT = 5;
  private static final int DOUBLE = 6;
  private static final int BYTE_ARRAY = 7;
  private static final int STRING = 8;
  private static final int LIST = 9;
  private static final int COMPOUND = 10;
  private static final int INT_ARRAY = 11;
  private static final int LONG_ARRAY = 12;
  private static final int MAX_DEPTH = 512;

  private Nbt() {}

  /**
   * A list tag, which keeps its element type so an empty list survives a round trip.
   *
   * @param elementType the NBT type id of every element
   */
  public record NbtList(int elementType, List<Object> values) {
    public NbtList {
      values = List.copyOf(values);
    }

    /** A list of compounds. */
    public static NbtList ofCompounds(List<Map<String, Object>> compounds) {
      return new NbtList(COMPOUND, new ArrayList<>(compounds));
    }

    /** The elements as compounds; empty if they are something else. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> compounds() {
      return elementType == COMPOUND
          ? values.stream().map(v -> (Map<String, Object>) v).toList()
          : List.of();
    }
  }

  /** Reads a root compound, gzipped or not. */
  public static Map<String, Object> read(byte[] bytes) throws IOException {
    InputStream in = new BufferedInputStream(new ByteArrayInputStream(bytes));
    if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
      in = new GZIPInputStream(in);
    }
    try (DataInputStream data = new DataInputStream(in)) {
      int type = data.readUnsignedByte();
      if (type != COMPOUND) {
        throw new IOException("The root tag is type " + type + ", not a compound");
      }
      data.readUTF();
      Map<String, Object> root = compound(data, 0);
      if (data.read() != -1) {
        throw new IOException("Trailing bytes after the root compound");
      }
      return root;
    }
  }

  /** Writes a root compound, gzipped as vanilla's own files and item data are. */
  public static byte[] writeCompressed(Map<String, Object> root) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      write(root, gzip, false);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /**
   * The compound uncompressed with every compound's keys sorted, so equal data always gives equal
   * bytes. For content hashes.
   */
  public static byte[] canonical(Map<String, Object> root) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      write(root, bytes, true);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static void write(Map<String, Object> root, OutputStream out, boolean sorted)
      throws IOException {
    DataOutputStream data = new DataOutputStream(out);
    data.writeByte(COMPOUND);
    data.writeUTF("");
    writeCompound(data, root, sorted);
    data.flush();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> compound(DataInputStream in, int depth) throws IOException {
    if (depth > MAX_DEPTH) {
      throw new IOException("NBT nested too deeply");
    }
    Map<String, Object> compound = new LinkedHashMap<>();
    for (int type = in.readUnsignedByte(); type != END; type = in.readUnsignedByte()) {
      compound.put(in.readUTF(), payload(in, type, depth + 1));
    }
    return compound;
  }

  private static Object payload(DataInputStream in, int type, int depth) throws IOException {
    return switch (type) {
      case BYTE -> in.readByte();
      case SHORT -> in.readShort();
      case INT -> in.readInt();
      case LONG -> in.readLong();
      case FLOAT -> in.readFloat();
      case DOUBLE -> in.readDouble();
      case BYTE_ARRAY -> {
        byte[] array = new byte[length(in)];
        in.readFully(array);
        yield array;
      }
      case STRING -> in.readUTF();
      case LIST -> {
        int elementType = in.readUnsignedByte();
        int size = length(in);
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
          list.add(payload(in, elementType, depth + 1));
        }
        yield new NbtList(elementType, list);
      }
      case COMPOUND -> compound(in, depth);
      case INT_ARRAY -> {
        int[] array = new int[length(in)];
        for (int i = 0; i < array.length; i++) {
          array[i] = in.readInt();
        }
        yield array;
      }
      case LONG_ARRAY -> {
        long[] array = new long[length(in)];
        for (int i = 0; i < array.length; i++) {
          array[i] = in.readLong();
        }
        yield array;
      }
      default -> throw new IOException("Unknown NBT tag type " + type);
    };
  }

  private static int length(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0) {
      throw new IOException("Negative NBT length " + length);
    }
    return length;
  }

  private static void writeCompound(
      DataOutputStream out, Map<String, Object> compound, boolean sorted) throws IOException {
    Map<String, Object> entries = sorted ? new TreeMap<>(compound) : compound;
    for (Map.Entry<String, Object> entry : entries.entrySet()) {
      out.writeByte(type(entry.getValue()));
      out.writeUTF(entry.getKey());
      writePayload(out, entry.getValue(), sorted);
    }
    out.writeByte(END);
  }

  @SuppressWarnings("unchecked")
  private static void writePayload(DataOutputStream out, Object value, boolean sorted)
      throws IOException {
    switch (value) {
      case Byte b -> out.writeByte(b);
      case Short s -> out.writeShort(s);
      case Integer i -> out.writeInt(i);
      case Long l -> out.writeLong(l);
      case Float f -> out.writeFloat(f);
      case Double d -> out.writeDouble(d);
      case byte[] array -> {
        out.writeInt(array.length);
        out.write(array);
      }
      case String s -> out.writeUTF(s);
      case NbtList list -> {
        out.writeByte(list.values().isEmpty() ? END : list.elementType());
        out.writeInt(list.values().size());
        for (Object element : list.values()) {
          if (type(element) != list.elementType()) {
            throw new IllegalArgumentException(
                "A list of type " + list.elementType() + " holds " + element);
          }
          writePayload(out, element, sorted);
        }
      }
      case Map<?, ?> map -> writeCompound(out, (Map<String, Object>) map, sorted);
      case int[] array -> {
        out.writeInt(array.length);
        for (int i : array) {
          out.writeInt(i);
        }
      }
      case long[] array -> {
        out.writeInt(array.length);
        for (long l : array) {
          out.writeLong(l);
        }
      }
      default -> throw new IllegalArgumentException("Not an NBT value: " + value);
    }
  }

  private static int type(Object value) {
    return switch (value) {
      case Byte b -> BYTE;
      case Short s -> SHORT;
      case Integer i -> INT;
      case Long l -> LONG;
      case Float f -> FLOAT;
      case Double d -> DOUBLE;
      case byte[] array -> BYTE_ARRAY;
      case String s -> STRING;
      case NbtList list -> LIST;
      case Map<?, ?> map -> COMPOUND;
      case int[] array -> INT_ARRAY;
      case long[] array -> LONG_ARRAY;
      default -> throw new IllegalArgumentException("Not an NBT value: " + value);
    };
  }

  /** The NBT type id of compounds, for {@link NbtList}. */
  public static int compoundType() {
    return COMPOUND;
  }

  /** The NBT type id of strings, for {@link NbtList}. */
  public static int stringType() {
    return STRING;
  }
}
