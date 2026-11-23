// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.map.MapPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The few lines startup writes to the server log (questions 295): what Seasons found, so an owner
 * reading the console sees at once whether it is idle, whether the Hall is built, whether the
 * journal is sound, and what to look at next. The lines are for the owner, so they are plain
 * English in code rather than in messages.yml.
 */
final class StartupSummary {
  private StartupSummary() {}

  /** The season in progress, as startup found it. */
  record Season(int number, String phase, Optional<String> plannedEnd, int zonesInForce) {}

  /** The Hall of Fame: whether the hub is marked, and its galleries' cells. */
  record Hall(boolean hub, int galleries, int cells, int free) {}

  /**
   * What startup found.
   *
   * @param journalError why the journal couldn't be opened, if it couldn't
   * @param interrupted operations a crash or stop cut short, which recovery finishes now
   * @param waiting players with a record who played the season that last ended and aren't yet
   *     archived into it
   * @param configProblems problems in the config files, which left the bundled defaults in force
   * @param hallProblems what {@code /hof hall check} would find, without looking at the world
   */
  record Facts(
      String version,
      String platform,
      int java,
      Optional<Season> season,
      Hall hall,
      Optional<String> journalError,
      int interrupted,
      int records,
      int waiting,
      boolean publicWebhook,
      boolean adminWebhook,
      MapPublisher.Status map,
      int configProblems,
      int hallProblems,
      long readyMillis) {}

  static List<String> lines(Facts facts) {
    List<String> out = new ArrayList<>();
    out.add("Seasons " + facts.version() + " on " + facts.platform() + ", Java " + facts.java());
    out.add(
        facts
            .season()
            .map(StartupSummary::season)
            .orElse("Season: none recorded. Seasons is idle until you run /season init <season>."));
    out.add(hall(facts.hall()));
    out.add(journal(facts));
    out.add(
        "Discord: public webhook "
            + (facts.publicWebhook() ? "set" : "not set")
            + ", admin webhook "
            + (facts.adminWebhook() ? "set" : "not set"));
    out.add(
        switch (facts.map()) {
          case MapPublisher.Status.NotInstalled _ -> "BlueMap: not installed, so no map markers";
          case MapPublisher.Status.Waiting _ -> "BlueMap: found; markers follow once it has loaded";
          case MapPublisher.Status.Published published ->
              "BlueMap: "
                  + count(published.markers(), "marker")
                  + " on "
                  + count(published.maps(), "map");
        });
    out.add("Ready in " + facts.readyMillis() + " ms. " + next(facts));
    return out;
  }

  private static String season(Season season) {
    return "Season "
        + season.number()
        + ": "
        + season.phase()
        + ", "
        + season.plannedEnd().map(end -> "ends " + end).orElse("no planned end")
        + ", "
        + count(season.zonesInForce(), "zone")
        + " in force";
  }

  private static String hall(Hall hall) {
    if (!hall.hub() && hall.galleries() == 0) {
      return "Hall of Fame: not set up yet; /hof help shows where to start";
    }
    return "Hall of Fame: "
        + (hall.hub() ? "hub set, " : "no hub yet, ")
        + count(hall.galleries(), "gallery", "galleries")
        + ", "
        + count(hall.cells(), "cell")
        + " ("
        + hall.free()
        + " free)";
  }

  private static String journal(Facts facts) {
    String records =
        count(facts.records(), "player record")
            + ", "
            + facts.waiting()
            + " waiting to be archived";
    if (facts.journalError().isPresent()) {
      return "Journal unavailable ("
          + facts.journalError().get()
          + "): nobody can be archived until it is fixed; "
          + records;
    }
    if (facts.interrupted() > 0) {
      return "Journal: finishing "
          + count(facts.interrupted(), "interrupted operation")
          + " from before the restart; "
          + records;
    }
    return "Journal clean; " + records;
  }

  private static String next(Facts facts) {
    int problems = facts.configProblems() + facts.hallProblems();
    if (problems == 0) {
      return "/season info for details.";
    }
    List<String> commands = new ArrayList<>();
    if (facts.configProblems() > 0) {
      commands.add("/season validate");
    }
    if (facts.hallProblems() > 0) {
      commands.add("/hof hall check");
    }
    return "Setup check found "
        + count(problems, "problem")
        + ": run "
        + String.join(" and ", commands);
  }

  static String count(long n, String one) {
    return count(n, one, one + "s");
  }

  static String count(long n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }
}
