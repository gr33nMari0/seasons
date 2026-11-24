// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.Arrays;

/** Bytes that never change, such as a font or a preview image, safe to keep in a record. */
final class Blob {
  static final Blob EMPTY = new Blob(new byte[0]);

  private final byte[] bytes;

  private Blob(byte[] bytes) {
    this.bytes = bytes;
  }

  static Blob of(byte[] bytes) {
    return bytes.length == 0 ? EMPTY : new Blob(bytes.clone());
  }

  int length() {
    return bytes.length;
  }

  boolean isEmpty() {
    return bytes.length == 0;
  }

  /** A copy, so nobody can change what this holds. */
  byte[] copy() {
    return bytes.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Blob b && Arrays.equals(bytes, b.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "Blob[" + bytes.length + " bytes]";
  }
}
