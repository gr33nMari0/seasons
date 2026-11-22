// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * {@code plugins/Seasons/config.yml}: human-owned settings. The bundled file holds every default
 * and documents each key; a server's file only needs the keys it changes.
 */
@ConfigSerializable
public record SeasonsConfig(
    @Comment("When the season ends and how players are reminded.") Schedule schedule,
    @Comment("Launching the next season.") Launch launch,
    @Comment("Archiving at season end.") Archive archive,
    @Comment("What archiving resets for the new season.") Resets resets,
    @Comment("Hall of Fame cells: who gets one, and how visitors get in.") Cells cells,
    @Comment("The decorating window, when owners arrange their cells.") Decorating decorating,
    @Comment("Pets that travel with their owner to the new spawn at launch (/pet add).") Pets pets,
    @Comment("Season firsts: the first player each season to do or find something.") Firsts firsts,
    @Comment("Visiting the Hall of Fame with /hof.") Hof hof,
    @Comment(
            "Zones over the old world. The zones themselves live in each season's file and are drawn with /season zone.")
        Zones zones,
    @Comment("The ender dragon and its egg.") Dragon dragon,
    @Comment("The deaths display.") Deaths deaths,
    @Comment(
            "Curation (/hof curate): every action, museum, return, take or destroy, is audited, posted to the admin webhook and told to the owner.")
        Curate curate,
    @Comment("The Hall admins' wand (/hof wand).") Wand wand,
    @Comment("Admin conveniences.") Admin admin,
    @Comment(
            "Awards, worked out when the season ends and announced at the Intermission. Each winner gets a trophy (item) on a free trophy anchor in their cell; ties share the award, and an award whose best is 0 goes to nobody. measure is one of deaths, travelled, play-time, diamonds, dragon, furthest-builder (from the season spawn), advancements, or any General-tab statistic id such as minecraft:jump; off turns an award off. A new id adds an award, named in messages.yml under awards.names, or after its id.")
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
  public record Schedule(
      @Comment(
              "false: the season ends only when an admin runs /season end, whatever the planned end says. true: the end countdown starts by itself at the planned end (or 5 minutes after the next start, if the server was down then).")
          boolean autoEnd,
      @Comment("How long before the planned end each reminder goes out, in chat and on Discord.")
          List<Duration> reminders,
      @Comment("The boss-bar countdown /season end starts. /season end --now skips it.")
          Duration endCountdown) {
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
  public record Launch(
      @Comment("The next season starts only with /season launch or the launch button in the Hall.")
          boolean requireManual,
      @Comment(
              "true: launch moves the world spawn to the new season's spawn. false: it stays where it is, such as at the Hall spawn, so first-time joiners and bedless respawns arrive in the hub.")
          boolean setWorldSpawn) {}

  /**
   * Archiving at season end.
   *
   * @param arrival where archiving sends everyone: their cell, or the Hall spawn (questions 257)
   */
  @ConfigSerializable
  public record Archive(
      @Comment(
              "Where archiving sends everyone at season end: cell (their own cell) or hub (the Hall spawn).")
          Arrival arrival) {}

  @ConfigSerializable
  public record Resets(
      @Comment("Experience: reset to zero, or kept.") ResetMode xp,
      @Comment(
              "The ender chest: vault (moved to the player's ender vault, which they can take from), archive (archived with the rest of their gear) or keep.")
          EnderChestPolicy enderChest,
      @Comment("The bed or anchor a player respawns at: cleared, or kept.") ClearMode respawnPoint,
      @Comment("Potion effects: cleared, or kept.") ClearMode effects,
      @Comment(
              "Advancements: archived (the plaque shows the count) and reset, or kept. Recipe unlocks are never archived.")
          AdvancementPolicy advancements,
      @Comment("Recipe unlocks: kept, or reset.") KeepMode recipes) {}

  @ConfigSerializable
  public record Cells(
      @Comment(
              "How cells are given out when the decorating window opens: random (a random free cell each), claim (players pick one with /hof claim), ordered (the free cells in the order they were tagged) or manual (admins assign with /hof cell assign).")
          CellAssignment assignment,
      @Comment("Play time a player needs in the season to get a cell.") Duration minPlaytime,
      @Comment(
              "How visitors get into a cell: open (walk in), bounds (refused at its edge while locked) or gate (a gate fills the doorway).")
          CellAccess access,
      @Comment(
              "Containers owners may place in their cell during decorating; view-only after the lock.")
          List<String> containers,
      @Comment(
              "With access: gate, what a cell's gate plane is while it is shut; air while it is open.")
          String gateBlock,
      @Comment(
              "open: a gate is open unless an admin (/hof cell gate, /hof gallery gate) or its owner (/hof gate) shut it. auto: shut whenever its cell is locked, open while its owner decorates.")
          GateMode gates,
      @Comment(
              "false: nobody is ever moved out of a cell; a shutting gate waits until the cell is empty, and a bounds cell only refuses entry at its edge. true: they are moved to the gallery's entry.")
          boolean moveOut) {
    public Cells {
      containers = List.copyOf(containers);
    }
  }

  @ConfigSerializable
  public record Decorating(
      @Comment(
              "The decorating window opens this long before the planned end (or with /season decorate open).")
          Duration opensBefore,
      @Comment(
              "Owners stop decorating once their cell locks: afterArchive after their archive, or at launch if that is sooner.")
          boolean lockAtEnd,
      @Comment(
              "Time owners get to arrange their cell once archived; then it locks. 0 turns it off.")
          Duration afterArchive) {}

  @ConfigSerializable
  public record Pets(
      @Comment("Whether players can register pets with /pet add to travel with them at launch.")
          boolean enabled,
      @Comment("How many pets each player may register, 0 to 10.") int maxPerPlayer) {}

  @ConfigSerializable
  public record Firsts(
      @Comment("Items whose first pickup each season is a season first.") List<String> rareItems) {
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
      @Comment(
              "Where and when /hof works. Holders of seasons.hof.anywhere (ops by default) skip every check but rateLimit.")
          Entry entry,
      @Comment("Where /hof leave puts a player when their saved spot is unsafe.")
          ReturnSafety returnSafety,
      @Comment("Damage immunity after every Seasons teleport.") Duration arrivalImmunity,
      @Comment(
              "What leaving the Hall by walking, falling or another plugin's teleport does. intermission: just leave, except during the Intermission, when everyone goes back to the Hall spawn. always: back to where they came from. never: just leave.")
          Sealing sealed,
      @Comment(
              "/hof leave with no saved spot (after a respawn or a first join in the hub): season-spawn takes them to the season's spawn, refuse refuses.")
          LeaveWithoutSpot leaveWithoutSpot,
      @Comment("Links between the Hall and each season's spawn.") Links links,
      @Comment(
              "Players in creative mode build anywhere in the Hall, as holders of seasons.hof.bypass do.")
          boolean creativeBuilds) {
    /**
     * Links.
     *
     * @param returnToLink whether {@code /hof leave} after taking a link to the Hall goes back to
     *     it
     */
    @ConfigSerializable
    public record Links(
        @Comment("/hof leave after a link to the Hall goes back to that link.")
            boolean returnToLink) {}

    /**
     * Where and when /hof works.
     *
     * @param requireBed whether /hof needs a bed near at all (questions 261)
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
        @Comment("Whether /hof needs a bed near at all.") boolean requireBed,
        @Comment("How near a bed a player must stand, 0 to 16 blocks.") int bedDistance,
        @Comment("true: any bed of any kind. false: only their own respawn bed.") boolean anyBed,
        @Comment("Refused with monsters near, as the game refuses sleep.") boolean noHostiles,
        @Comment("Refused for this long after taking damage.") Duration noDamageFor,
        @Comment("Refused while riding, gliding, falling or swimming.") boolean onGround,
        @Comment("Refused in lava, fire or powder snow, or while burning.") boolean noHazards,
        @Comment("The least time between visits.") Duration cooldown,
        @Comment("The least time between attempts.") Duration rateLimit) {}

    @ConfigSerializable
    public record ReturnSafety(
        @Comment("A saved spot with monsters within this many blocks is unsafe.") int hostileRadius,
        @Comment(
                "Where to go instead, in order: bed (their respawn bed), nearby (the nearest safe block) or spawn (the season's spawn).")
            List<ReturnFallback> fallbacks) {
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
  public record Zones(
      @Comment(
              "Also post announce-mode entries (\"Peter entered the market\") to the Discord webhook.")
          boolean announceToDiscord,
      @Comment(
              "Draw a locked `outside` circle zone as a world border each player inside it sees: the square around the circle. Visual only: the zone does the locking, and the world's own border is never changed. Players in the Hall, in another dimension or holding seasons.zone.bypass see none.")
          boolean frontierBorder) {}

  @ConfigSerializable
  public record Dragon(
      @Comment(
              "Launch clears the End's previously-killed flag, so each season's first dragon kill drops a new egg.")
          boolean newEggEachSeason,
      @Comment(
              "Where that egg goes: slayer (to the player who killed the dragon, with a fanfare, or to their vault's Returned page if they can't take it) or portal (it stays on the exit portal, as in the game).")
          EggTo eggTo) {}

  /**
   * Curation.
   *
   * @param requireReason whether every curation action needs a reason typed in chat
   */
  @ConfigSerializable
  public record Curate(
      @Comment(
              "The curator types a reason in chat for each action, which nobody else sees; without it, \"no reason given\" is recorded.")
          boolean requireReason) {}

  /**
   * The Hall admins' wand (questions 226).
   *
   * @param item the item {@code /hof wand} gives, marked as Seasons' own
   * @param outlines how far and how many of the Hall's outlines an admin sees (questions 276)
   */
  @ConfigSerializable
  public record Wand(
      @Comment("The item /hof wand gives, marked as Seasons' own, so a plain one does nothing.")
          String item,
      @Comment(
              "The Hall's parts outlined for an admin alone while they hold the wand, or after /hof show on.")
          Outlines outlines) {
    /**
     * The outlines.
     *
     * @param range how far away a box is still drawn, in blocks
     * @param max how many boxes at most, nearest first
     */
    @ConfigSerializable
    public record Outlines(
        @Comment("How far away a box is still drawn, in blocks.") int range,
        @Comment("How many boxes at most, nearest first.") int max) {}
  }

  /**
   * Admin conveniences.
   *
   * @param hints whether a setup step suggests the commands that usually come next (questions 268)
   */
  @ConfigSerializable
  public record Admin(
      @Comment("After a setup step, suggest the commands that usually come next.") boolean hints) {}

  /**
   * The deaths display.
   *
   * @param display the scoreboard slot each season's deaths objective is shown in
   */
  @ConfigSerializable
  public record Deaths(
      @Comment(
              "Where each season's deaths objective (seasons_deaths_s3) is shown: list (the tab list), sidebar, below_name or none. A new objective is made at each launch; earlier ones are kept.")
          String display) {
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
  public record Award(
      @Comment("What the award counts; off turns it off.") @Nullable String measure,
      @Comment("The item each winner's trophy shows.") @Nullable String item) {
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
