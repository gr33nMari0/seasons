// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Arrays;

/**
 * One item stack as vanilla item data: an NBT compound that carries its {@code DataVersion}, never
 * a platform format. Any adapter can read the bytes a different adapter wrote. Immutable.
 */
public final class NeutralItem {
  private final byte[] nbt;

  /**
   * @param nbt the encoded compound, exactly as the adapter's item codec wrote it
   */
  public NeutralItem(byte[] nbt) {
    this.nbt = nbt.clone();
  }

  /** A copy of the encoded compound. */
  public byte[] nbt() {
    return nbt.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof NeutralItem other && Arrays.equals(nbt, other.nbt);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(nbt);
  }

  @Override
  public String toString() {
    return "NeutralItem[" + nbt.length + " bytes]";
  }
}
