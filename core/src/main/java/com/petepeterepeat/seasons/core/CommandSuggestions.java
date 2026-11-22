// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.command.CommandHelp;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.Positions;
import com.petepeterepeat.seasons.core.command.Suggester;
import com.petepeterepeat.seasons.core.command.Words;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.config.SeasonFile;
import com.petepeterepeat.seasons.core.hall.AnchorType;
import com.petepeterepeat.seasons.core.hall.Cell;
import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.zone.ZoneMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * What tab completion offers for each argument, and the choices help shows, by the argument's name
 * and where it is (questions 266). Everything comes from loaded, immutable state.
 */
final class CommandSuggestions implements CommandHelp.Suggestions, CommandHelp.Choices {
  private static final List<String> OPEN_SHUT = List.of("open", "shut");
  private static final List<String> AFTER = List.of("off", "no-build");

  private final Platform platform;
  private final ConfigHolder config;
  private final Supplier<Optional<PlayerStore>> players;
  private final Supplier<Optional<SeasonState>> state;
  private final Supplier<List<String>> templates;

  CommandSuggestions(
      Platform platform,
      ConfigHolder config,
      Supplier<Optional<PlayerStore>> players,
      Supplier<Optional<SeasonState>> state,
      Supplier<List<String>> templates) {
    this.platform = platform;
    this.config = config;
    this.players = players;
    this.state = state;
    this.templates = templates;
  }

  @Override
  public Optional<List<String>> of(List<String> path, CommandNode argument) {
    String name = argument.name();
    return switch (name) {
      case "state" -> Optional.of(OPEN_SHUT);
      case "type" -> Optional.of(anchorTypes());
      case "mode" ->
          path.contains("wand")
              ? Optional.of(List.of("block", "place"))
              : path.contains("after") ? Optional.of(AFTER) : Optional.of(zoneModes());
      default -> Optional.empty();
    };
  }

  @Override
  public Optional<Suggester> forArgument(List<String> path, CommandNode argument) {
    Optional<List<String>> choices = of(path, argument);
    if (choices.isPresent()) {
      List<String> values = choices.get();
      return Optional.of((source, typed) -> values);
    }
    String root = path.getFirst();
    return Optional.ofNullable(
        switch (argument.name()) {
          case "gallery" -> (source, typed) -> galleries();
          case "cell", "anchor" -> (source, typed) -> cells();
          case "player" ->
              path.contains("swap") ? (source, typed) -> online() : (source, typed) -> players();
          case "target" ->
              root.equals("season")
                  ? (source, typed) -> concat(List.of("homecoming"), zones())
                  : (source, typed) -> concat(seasons(), players());
          case "season" -> (source, typed) -> seasons();
          case "template" -> (source, typed) -> templates.get();
          case "candidate" -> (source, typed) -> candidates();
          case "name" ->
              path.contains("candidate") && !path.contains("add")
                  ? (source, typed) -> candidates()
                  : null;
          case "link" -> path.contains("add") ? null : (source, typed) -> links();
          case "id", "zone" ->
              (path.contains("zone") && !path.contains("add")) || argument.name().equals("zone")
                  ? (source, typed) -> zones()
                  : null;
          case "set" -> (source, typed) -> markerSets();
          case "path" -> (source, typed) -> settings(typed);
          case "at" -> (source, typed) -> position(typed, false);
          case "corners" -> (source, typed) -> position(typed, true);
          case "shape" -> (source, typed) -> shape(typed);
          case "options" -> options(path);
          default -> null;
        });
  }

  private static List<String> anchorTypes() {
    return Arrays.stream(AnchorType.values()).map(AnchorType::id).toList();
  }

  private static List<String> zoneModes() {
    return List.of(
        ZoneMode.OFF.id(), ZoneMode.ANNOUNCE.id(), ZoneMode.BLOCK.id(), ZoneMode.NO_BUILD.id());
  }

  private List<String> galleries() {
    return List.copyOf(config.get().galleries().keySet());
  }

  private List<String> cells() {
    List<String> cells = new ArrayList<>();
    for (Gallery gallery : config.get().allGalleries()) {
      for (Cell cell : gallery.cells()) {
        cells.add(gallery.id() + ":" + cell.id());
      }
    }
    return cells;
  }

  private List<String> online() {
    return platform.players().online().stream().map(PlayerRef::name).sorted().toList();
  }

  /** Online players first, then every name in the player records. */
  private List<String> players() {
    Set<String> names = new LinkedHashSet<>(online());
    players
        .get()
        .ifPresent(
            store -> store.all().stream().map(PlayerRecord::lastName).sorted().forEach(names::add));
    return List.copyOf(names);
  }

  private List<String> seasons() {
    TreeSet<Integer> seasons = new TreeSet<>(config.get().seasons().keySet());
    state.get().ifPresent(s -> seasons.add(s.season()));
    config.get().allGalleries().forEach(g -> seasons.add(g.season()));
    return seasons.stream().map(String::valueOf).toList();
  }

  /** The next season's candidates, which the spawn commands edit. */
  private List<String> candidates() {
    int next = state.get().map(s -> s.season() + 1).orElse(1);
    return config
        .get()
        .season(next)
        .map(f -> List.copyOf(new TreeSet<>(f.spawnCandidates().keySet())))
        .orElse(List.of());
  }

  private List<String> links() {
    return List.copyOf(config.get().hall().links().keySet());
  }

  private List<String> zones() {
    TreeSet<String> zones = new TreeSet<>();
    for (SeasonFile file : config.get().seasons().values()) {
      zones.addAll(file.zones().keySet());
    }
    return List.copyOf(zones);
  }

  private List<String> markerSets() {
    return List.copyOf(new TreeSet<>(config.get().waypoints().sets().keySet()));
  }

  /** Setting paths one level at a time: {@code hof}, then {@code hof.entry.requireBed}. */
  private List<String> settings(String typed) {
    ConfigSnapshot snapshot = config.get();
    String prefix = typed.contains(".") ? typed.substring(0, typed.lastIndexOf('.') + 1) : "";
    TreeSet<String> next = new TreeSet<>();
    for (Map.Entry<String, String> setting : snapshot.settings().entrySet()) {
      String key = setting.getKey();
      if (key.startsWith("secrets") || !key.startsWith(prefix)) {
        continue;
      }
      String rest = key.substring(prefix.length());
      int dot = rest.indexOf('.');
      next.add(prefix + (dot < 0 ? rest : rest.substring(0, dot)));
    }
    return List.copyOf(next);
  }

  private static List<String> position(String typed, boolean box) {
    int done = Words.of(typed).size() - (typed.endsWith(" ") || typed.isEmpty() ? 0 : 1);
    List<String> words = new ArrayList<>(List.of("~", "~ ~ ~"));
    if (done == 0) {
      words.add(Positions.HERE);
      if (box) {
        words.add(Positions.SELECTION);
      }
    } else if (box && done == 3) {
      words.add(Positions.HERE);
    }
    return words;
  }

  private static List<String> shape(String typed) {
    List<String> words = Words.of(typed);
    int done = words.size() - (typed.endsWith(" ") || typed.isEmpty() ? 0 : 1);
    if (done == 0) {
      return List.of("cylinder", "box", "outside", "y-above", "y-below");
    }
    return switch (words.getFirst()) {
      case "box" -> done == 1 ? List.of("~", "~ ~ ~", "sel") : List.of("~", "~ ~ ~");
      case "outside" -> done == 1 ? List.of("circle", "box") : List.of("~");
      case "cylinder" -> done == 1 ? List.of() : List.of("--max-y", "--min-y");
      default -> List.of();
    };
  }

  private static Suggester options(List<String> path) {
    List<String> flags;
    if (path.contains("end")) {
      flags = List.of("--dry-run", "--now");
    } else if (path.contains("launch") || path.contains("import")) {
      flags = List.of("--dry-run");
    } else if (path.contains("unarchive")) {
      flags = List.of("--force");
    } else if (path.contains("stamp")) {
      flags = List.of("--step", "--rotate");
    } else {
      flags = List.of();
    }
    return (source, typed) -> flags;
  }

  private static List<String> concat(List<String> a, List<String> b) {
    List<String> all = new ArrayList<>(a);
    all.addAll(b);
    return all;
  }
}
