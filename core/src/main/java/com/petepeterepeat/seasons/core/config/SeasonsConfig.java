// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.time.Duration;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * {@code plugins/Seasons/config.yml}: human-owned settings. The bundled file holds every default
 * and documents each key; a server's file only needs the keys it changes.
 */
@ConfigSerializable
public record SeasonsConfig(
    Schedule schedule,
    Launch launch,
    Resets resets,
    Cells cells,
    Decorating decorating,
    Pets pets,
    Firsts firsts,
    Hof hof,
    Dragon dragon,
    Deaths deaths) {

  /**
   * When the season ends and how players are reminded.
   *
   * @param autoEnd whether the countdown starts by itself at the planned end
   * @param reminders how long before the planned end each reminder goes out
   * @param endCountdown the boss-bar countdown {@code /season end} starts
   */
  @ConfigSerializable
  public record Schedule(boolean autoEnd, List<Duration> reminders, Duration endCountdown) {
    public Schedule {
      reminders = List.copyOf(reminders);
    }
  }

  @ConfigSerializable
  public record Launch(boolean requireManual) {}

  @ConfigSerializable
  public record Resets(
      ResetMode xp,
      EnderChestPolicy enderChest,
      ClearMode respawnPoint,
      ClearMode effects,
      AdvancementPolicy advancements,
      KeepMode recipes) {}

  @ConfigSerializable
  public record Cells(
      CellAssignment assignment, Duration minPlaytime, CellAccess access, List<String> containers) {
    public Cells {
      containers = List.copyOf(containers);
    }
  }

  @ConfigSerializable
  public record Decorating(Duration opensBefore, boolean lockAtEnd, Duration afterArchive) {}

  @ConfigSerializable
  public record Pets(boolean enabled, int maxPerPlayer) {}

  @ConfigSerializable
  public record Firsts(List<String> rareItems) {
    public Firsts {
      rareItems = List.copyOf(rareItems);
    }
  }

  @ConfigSerializable
  public record Hof(Entry entry, ReturnSafety returnSafety, Duration arrivalImmunity) {
    /**
     * Where and when /hof works.
     *
     * @param bedDistance how near a bed a player must stand
     * @param anyBed any bed of any kind, or only their own respawn bed
     * @param noHostiles refused with monsters near, as vanilla refuses sleep
     * @param noDamageFor refused for this long after taking damage
     * @param onGround refused while riding, gliding, falling or swimming
     * @param noHazards refused in lava, fire or powder snow, or while burning
     * @param cooldown between visits
     * @param rateLimit between attempts
     */
    @ConfigSerializable
    public record Entry(
        int bedDistance,
        boolean anyBed,
        boolean noHostiles,
        Duration noDamageFor,
        boolean onGround,
        boolean noHazards,
        Duration cooldown,
        Duration rateLimit) {}

    @ConfigSerializable
    public record ReturnSafety(int hostileRadius, List<ReturnFallback> fallbacks) {
      public ReturnSafety {
        fallbacks = List.copyOf(fallbacks);
      }
    }
  }

  @ConfigSerializable
  public record Dragon(boolean newEggEachSeason, EggTo eggTo) {}

  @ConfigSerializable
  public record Deaths(String display) {}

  public enum ResetMode {
    RESET,
    KEEP
  }

  public enum KeepMode {
    KEEP,
    RESET
  }

  public enum ClearMode {
    CLEAR,
    KEEP
  }

  public enum EnderChestPolicy {
    VAULT,
    ARCHIVE,
    KEEP
  }

  public enum AdvancementPolicy {
    ARCHIVE_AND_RESET,
    KEEP
  }

  public enum CellAssignment {
    RANDOM,
    CLAIM,
    ORDERED,
    MANUAL
  }

  public enum CellAccess {
    OPEN,
    BOUNDS,
    GATE
  }

  public enum ReturnFallback {
    BED,
    NEARBY,
    SPAWN
  }

  public enum EggTo {
    SLAYER,
    PORTAL
  }
}
