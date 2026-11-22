// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * {@code plugins/Seasons/config.yml}: human-owned settings. The bundled file holds every default
 * and documents each key; a server's file only needs the keys it changes.
 */
@ConfigSerializable
public record SeasonsConfig(
    Schedule schedule,
    Launch launch,
    Archive archive,
    Resets resets,
    Cells cells,
    Decorating decorating,
    Pets pets,
    Firsts firsts,
    Hof hof,
    Zones zones,
    Dragon dragon,
    Deaths deaths,
    Curate curate,
    Wand wand,
    Admin admin,
    Map<String, Award> awards) {
  public SeasonsConfig {
    awards = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(awards));
  }

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

  /**
   * Launching the next season.
   *
   * @param requireManual only {@code /season launch} or the launch button launches
   * @param setWorldSpawn whether launch moves the world spawn to the new season's spawn; false
   *     keeps it where it is, such as at the Hall spawn (questions 258)
   */
  @ConfigSerializable
  public record Launch(boolean requireManual, boolean setWorldSpawn) {}

  /**
   * Archiving at season end.
   *
   * @param arrival where archiving sends everyone: their cell, or the Hall spawn (questions 257)
   */
  @ConfigSerializable
  public record Archive(Arrival arrival) {}

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
      CellAssignment assignment,
      Duration minPlaytime,
      CellAccess access,
      List<String> containers,
      String gateBlock,
      GateMode gates,
      boolean moveOut) {
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

  /**
   * Visiting the Hall.
   *
   * @param sealed what leaving the Hall's bounds does (questions 245)
   * @param leaveWithoutSpot what {@code /hof leave} does with no saved spot (questions 246)
   * @param links taking links (questions 249)
   * @param creativeBuilds whether players in creative mode build in the Hall as bypass holders do
   *     (questions 252)
   */
  @ConfigSerializable
  public record Hof(
      Entry entry,
      ReturnSafety returnSafety,
      Duration arrivalImmunity,
      Sealing sealed,
      LeaveWithoutSpot leaveWithoutSpot,
      Links links,
      boolean creativeBuilds) {
    /**
     * Links.
     *
     * @param returnToLink whether {@code /hof leave} after taking a link to the Hall goes back to
     *     it
     */
    @ConfigSerializable
    public record Links(boolean returnToLink) {}

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

  /**
   * Zones over the old world; the zones themselves live in each season's file.
   *
   * @param announceToDiscord whether announce-mode entries are posted to Discord too
   * @param frontierBorder whether players inside a locked {@code outside} circle see a world border
   *     of their own around it, visual only
   */
  @ConfigSerializable
  public record Zones(boolean announceToDiscord, boolean frontierBorder) {}

  @ConfigSerializable
  public record Dragon(boolean newEggEachSeason, EggTo eggTo) {}

  /**
   * Curation.
   *
   * @param requireReason whether every curation action needs a reason typed in chat
   */
  @ConfigSerializable
  public record Curate(boolean requireReason) {}

  /**
   * The Hall admins' wand (questions 226).
   *
   * @param item the item {@code /hof wand} gives, marked as Seasons' own
   */
  @ConfigSerializable
  public record Wand(String item) {}

  /**
   * Admin conveniences.
   *
   * @param hints whether a setup step suggests the commands that usually come next (questions 268)
   */
  @ConfigSerializable
  public record Admin(boolean hints) {}

  /**
   * The deaths display.
   *
   * @param display the scoreboard slot each season's deaths objective is shown in
   */
  @ConfigSerializable
  public record Deaths(String display) {
    /** The slots {@code display} may name. */
    public static final List<String> SLOTS = List.of("list", "sidebar", "below_name", "none");
  }

  /**
   * One award (see Stats, season cards and awards in the spec), by its id in the {@code awards}
   * section, in the order the section lists them.
   *
   * @param measure what it counts, as {@link AwardMeasure} reads it
   * @param item the item each winner's trophy shows
   */
  @ConfigSerializable
  public record Award(@Nullable String measure, @Nullable String item) {
    /** The measure, if it is one Seasons knows. */
    public Optional<AwardMeasure> parsed() {
      return Optional.ofNullable(measure).flatMap(AwardMeasure::parse);
    }

    /** Whether the award is given at all. */
    public boolean on() {
      return parsed().filter(m -> m != AwardMeasure.Named.OFF).isPresent();
    }
  }

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

  /** How a cell's gate is set in a {@code gate} gallery (questions 250). */
  public enum GateMode {
    /** Open unless an admin or the owner shut it. */
    OPEN,
    /** Shut whenever its cell is locked, open while its owner may decorate it. */
    AUTO
  }

  /** What leaving the Hall's bounds does (questions 245). */
  public enum Sealing {
    /** Just leave, except during the Intermission, when everyone goes back to the Hall spawn. */
    INTERMISSION,
    /** Back to the saved spot, or the Hall spawn. */
    ALWAYS,
    /** Just leave. */
    NEVER
  }

  /** What {@code /hof leave} does with no saved spot (questions 246). */
  public enum LeaveWithoutSpot {
    SEASON_SPAWN,
    REFUSE
  }

  /** Where archiving sends everyone at season end (questions 257). */
  public enum Arrival {
    CELL,
    HUB
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
