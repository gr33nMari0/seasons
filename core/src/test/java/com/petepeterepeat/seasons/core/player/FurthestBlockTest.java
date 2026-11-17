// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.Position;
import org.junit.jupiter.api.Test;

class FurthestBlockTest {
  private static final Position SPAWN = Position.of(FurthestBlock.OVERWORLD, 100, 64, 0);

  private static Position block(int x, int z) {
    return Position.of(FurthestBlock.OVERWORLD, x, 64, z);
  }

  @Test
  void keepsTheFurthestFromSpawnAndFromTheOriginSeparately() {
    FurthestBlock best = FurthestBlock.NONE.place(block(5, 0), SPAWN).place(block(150, 0), SPAWN);

    assertThat(best.fromOrigin().orElseThrow().distance()).isEqualTo(150);
    assertThat(best.fromSpawn().orElseThrow().distance()).isEqualTo(94);
    assertThat(best.fromSpawn().orElseThrow().at()).isEqualTo(block(5, 0));
  }

  @Test
  void aNearerBlockChangesNothing() {
    FurthestBlock best = FurthestBlock.NONE.place(block(30000, 0), SPAWN);

    assertThat(best.place(block(10, 10), SPAWN)).isEqualTo(best);
    assertThat(best.fromOrigin().orElseThrow().distance()).isEqualTo(30000);
  }
}
