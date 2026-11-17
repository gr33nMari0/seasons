// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.paper;

import com.petepeterepeat.seasons.core.port.DragonFight;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.DragonBattle;

final class PaperDragonFight implements DragonFight {
  private static final String THE_END = NamespacedKey.minecraft("the_end").asString();

  @Override
  public void setPreviouslyKilled(boolean previouslyKilled) {
    battle().ifPresent(battle -> battle.setPreviouslyKilled(previouslyKilled));
  }

  @Override
  public Optional<Position> exitPortal() {
    return battle()
        .flatMap(battle -> Optional.ofNullable(battle.getEndPortalLocation()))
        .map(PaperPositions::toPosition);
  }

  private static Optional<DragonBattle> battle() {
    return PaperPositions.world(THE_END)
        .flatMap(world -> Optional.ofNullable(world.getEnderDragonBattle()));
  }
}
