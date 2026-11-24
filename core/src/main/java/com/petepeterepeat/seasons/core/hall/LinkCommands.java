// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.command.Positions;
import com.petepeterepeat.seasons.core.command.Words;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Hall link commands M8 adds under {@code /hof link} and {@code /hof point}: links from any
 * source to any destination, several triggers, two-way pairs, landings, renames, access and named
 * points. The 1.1 forms stay in {@link HallCommands} and {@link HallLists}, which hand the new
 * grammar here. Runs on the server thread.
 */
public final class LinkCommands {
  private static final int REACH = 6;
  private static final int NEAR = 32;
  private static final String OVERWORLD = "minecraft:overworld";
  private static final Set<String> DESTINATIONS =
      Set.of("hall", "return", "season", "point", "link", "gallery");

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final CellEdits edits;
  private final Wand wand;
  private final LinkService links;
  private final HallVisits visits;

  public LinkCommands(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      CellEdits edits,
      Wand wand,
      LinkService links,
      HallVisits visits) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.edits = edits;
    this.wand = wand;
    this.links = links;
    this.visits = visits;
  }

  /** The {@code /hof} words this class adds, to merge into the Hall's tree. */
  public CommandNode root() {
    String admin = LinkService.PERMISSION_ADMIN;
    return CommandNode.literal("hof")
        .then(
            CommandNode.literal("link")
                .then(
                    CommandNode.literal("from")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .then(
                                    CommandNode.argument("source", ArgumentType.greedy())
                                        .executes(this::from))))
                .then(
                    CommandNode.literal("trigger")
                        .permission(admin)
                        .then(
                            CommandNode.literal("add")
                                .then(
                                    CommandNode.argument("link", ArgumentType.word())
                                        .then(
                                            CommandNode.argument("source", ArgumentType.greedy())
                                                .executes(this::from))))
                        .then(
                            CommandNode.literal("kind")
                                .then(
                                    CommandNode.argument("link", ArgumentType.word())
                                        .then(
                                            CommandNode.argument("rest", ArgumentType.greedy())
                                                .executes(this::triggerKind))))
                        .then(
                            CommandNode.literal("remove")
                                .then(
                                    CommandNode.argument("link", ArgumentType.word())
                                        .then(
                                            CommandNode.argument("which", ArgumentType.greedy())
                                                .executes(this::triggerRemove))))
                        .then(
                            CommandNode.literal("list")
                                .then(
                                    CommandNode.argument("link", ArgumentType.word())
                                        .executes(this::triggerList))))
                .then(
                    CommandNode.literal("landing")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::landing)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::landing))))
                .then(
                    CommandNode.literal("pair")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .then(
                                    CommandNode.argument("other", ArgumentType.word())
                                        .executes(this::pair))))
                .then(
                    CommandNode.literal("unpair")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .executes(this::unpair)))
                .then(
                    CommandNode.literal("rename")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .then(
                                    CommandNode.argument("new", ArgumentType.word())
                                        .executes(this::rename))))
                .then(
                    CommandNode.literal("name")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .then(
                                    CommandNode.argument("text", ArgumentType.greedy())
                                        .executes(this::name))))
                .then(
                    CommandNode.literal("access")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .then(
                                    CommandNode.argument("who", ArgumentType.greedy())
                                        .executes(this::access))))
                .then(
                    CommandNode.literal("when")
                        .permission(admin)
                        .then(
                            CommandNode.argument("link", ArgumentType.word())
                                .then(
                                    CommandNode.argument("condition", ArgumentType.greedy())
                                        .executes(this::when))))
                .then(
                    CommandNode.literal("go")
                        .then(
                            CommandNode.argument("link", ArgumentType.word()).executes(this::go))))
        .then(
            CommandNode.literal("point")
                .permission(admin)
                .then(
                    CommandNode.literal("add")
                        .then(
                            CommandNode.argument("point", ArgumentType.word())
                                .executes(this::pointAdd)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::pointAdd))))
                .then(
                    CommandNode.literal("move")
                        .then(
                            CommandNode.argument("point", ArgumentType.word())
                                .executes(this::pointMove)
                                .then(
                                    CommandNode.argument("at", ArgumentType.greedy())
                                        .executes(this::pointMove))))
                .then(
                    CommandNode.literal("rename")
                        .then(
                            CommandNode.argument("point", ArgumentType.word())
                                .then(
                                    CommandNode.argument("new", ArgumentType.word())
                                        .executes(this::pointRename))))
                .then(
                    CommandNode.literal("remove")
                        .then(
                            CommandNode.argument("point", ArgumentType.word())
                                .executes(this::pointRemove)
                                .then(
                                    CommandNode.argument("options", ArgumentType.greedy())
                                        .suggests((s, t) -> List.of("--force"))
                                        .executes(this::pointRemove))))
                .then(CommandNode.literal("list").executes(this::pointList))
                .then(
                    CommandNode.literal("info")
                        .then(
                            CommandNode.argument("point", ArgumentType.word())
                                .executes(this::pointInfo)))
                .then(
                    CommandNode.literal("tp")
                        .then(
                            CommandNode.argument("point", ArgumentType.word())
                                .executes(this::pointTp))))
        .build();
  }

  // The new grammar

  /** Whether {@code /hof link add <id> <rest>} uses M8's grammar rather than 1.1's. */
  public static boolean newGrammar(String rest) {
    List<String> words = Words.of(rest);
    return words.contains("to") || words.contains("from") || rest.contains("--");
  }

  /**
   * Whether {@code /hof link to <id> <rest>} names one of M8's destinations rather than 1.1's spot.
   */
  public static boolean newDestination(String rest) {
    List<String> words = Words.of(rest);
    return !words.isEmpty() && DESTINATIONS.contains(words.getFirst());
  }

  /**
   * {@code /hof link add <id> [from <source>] to <destination> [--trigger <kind>] [--two-way]
   * [--name <text>]}.
   */
  public void add(CommandSource source, String id, String rest) {
    if (left(source)) {
      return;
    }
    if (!HallFiles.ID.matcher(id).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    HallLayout layout = hall.layout();
    if (layout.link(id).isPresent()) {
      source.reply(messages().get("hof.link.exists", "link", id));
      return;
    }
    Flags flags = flags(Words.of(rest));
    if (flags.bad()) {
      source.reply(messages().get("hof.link.usage-new"));
      return;
    }
    List<String> words = flags.words();
    int from = words.indexOf("from");
    int to = words.indexOf("to");
    if (to < 0 || (from >= 0 && from > to)) {
      source.reply(messages().get("hof.link.usage-new"));
      return;
    }
    List<String> sourceWords = from >= 0 ? words.subList(from + 1, to) : List.of();
    List<String> destinationWords = words.subList(to + 1, words.size());
    Optional<HallLayout.Target> target = destination(source, id, destinationWords);
    if (target.isEmpty()) {
      return;
    }
    List<HallLayout.Trigger> triggers = new ArrayList<>();
    if (from >= 0) {
      Optional<HallLayout.Trigger> trigger =
          trigger(source, sourceWords, flags.kind(), flags.area());
      if (trigger.isEmpty()) {
        return;
      }
      if (taken(source, layout, trigger.get(), id)) {
        return;
      }
      triggers.add(trigger.get());
    } else {
      // No from: the block the wand last put aside (M8), else the selection, else none.
      Optional<HallLayout.Trigger> pending = pending(source, flags.kind());
      if (pending.isPresent()) {
        if (taken(source, layout, pending.get(), id)) {
          return;
        }
        triggers.add(pending.get());
      }
    }
    HallLayout.Access access = links.defaultAccess(target);
    HallLayout.Link made = HallLayout.Link.of(id, target, triggers, access).withName(flags.name());
    HallLayout next = layout.withLink(made);
    List<Message> replies = new ArrayList<>();
    replies.add(
        messages()
            .get(
                "hof.link.made",
                "link",
                id,
                "to",
                links.describe(target.get()),
                "from",
                triggers.isEmpty()
                    ? messages().text("hof.link.from-none")
                    : describe(triggers.getFirst())));
    if (flags.twoWay()) {
      String other = id + "-back";
      if (layout.link(other).isPresent()) {
        source.reply(messages().get("hof.link.exists", "link", other));
        return;
      }
      HallLayout.Link back =
          HallLayout.Link.of(
                  other, Optional.of(new HallLayout.Target.ToLink(id)), List.of(), access)
              .withPair(Optional.of(id));
      next = next.withLink(made.withPair(Optional.of(other))).withLink(back);
      replies.add(messages().get("hof.link.made-pair", "link", id, "other", other));
    }
    String details =
        made.to().map(links::describe).orElse("") + "; " + triggers.size() + " trigger(s)";
    save(source, next, replies, "link-add", id, details);
  }

  /** {@code /hof link to <id> <destination>} with one of M8's destinations. */
  public void to(CommandSource source, String id, String rest) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, id);
    if (link.isEmpty()) {
      return;
    }
    Optional<HallLayout.Target> target = destination(source, id, Words.of(rest));
    if (target.isEmpty()) {
      return;
    }
    save(
        source,
        hall.layout().withLink(link.get().withTo(target)),
        List.of(messages().get("hof.link.to-dest", "link", id, "to", links.describe(target.get()))),
        "link-to",
        id,
        links.describe(target.get()));
  }

  /** {@code /hof link from <id> <source>}, and {@code trigger add}: another trigger. */
  void from(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    String id = arguments.string("link");
    Optional<HallLayout.Link> link = link(source, id);
    if (link.isEmpty()) {
      return;
    }
    Flags flags = flags(Words.of(arguments.string("source")));
    if (flags.bad()) {
      source.reply(messages().get("hof.link.bad-source"));
      return;
    }
    Optional<HallLayout.Trigger> trigger =
        trigger(source, flags.words(), flags.kind(), flags.area());
    if (trigger.isEmpty() || taken(source, hall.layout(), trigger.get(), id)) {
      return;
    }
    addTrigger(source, link.get(), trigger.get());
  }

  /** Adds a trigger to a link, as {@code /hof link trigger add} does, and says so. */
  public void addTrigger(CommandSource source, HallLayout.Link link, HallLayout.Trigger trigger) {
    if (left(source) || taken(source, hall.layout(), trigger, link.id())) {
      return;
    }
    save(
        source,
        hall.layout().withLink(link.withTrigger(trigger)),
        List.of(
            messages()
                .get("hof.link.trigger-added", "link", link.id(), "trigger", describe(trigger))),
        "link-trigger-add",
        link.id(),
        describe(trigger));
  }

  void triggerList(CommandSource source, Arguments arguments) {
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    List<HallLayout.Trigger> triggers = link.get().triggers();
    if (triggers.isEmpty()) {
      source.reply(messages().get("hof.link.no-triggers", "link", link.get().id()));
      return;
    }
    for (int i = 0; i < triggers.size(); i++) {
      source.reply(
          messages()
              .get(
                  "hof.link.trigger-line",
                  "n",
                  Integer.toString(i + 1),
                  "trigger",
                  describe(triggers.get(i))));
    }
  }

  /** {@code /hof link trigger kind <id> <n> use|step|both}. */
  void triggerKind(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    List<String> words = Words.of(arguments.string("rest"));
    Optional<HallLayout.BlockKind> kind =
        words.size() == 2 ? HallLayout.BlockKind.of(words.get(1)) : Optional.empty();
    int n = number(words.isEmpty() ? "" : words.getFirst());
    List<HallLayout.Trigger> triggers = new ArrayList<>(link.get().triggers());
    if (kind.isEmpty() || n < 1 || n > triggers.size()) {
      source.reply(
          messages()
              .get(
                  "hof.link.trigger-unknown",
                  "link",
                  link.get().id(),
                  "n",
                  words.isEmpty() ? "?" : words.getFirst()));
      return;
    }
    if (!(triggers.get(n - 1) instanceof HallLayout.Trigger.OnBlock on)) {
      source.reply(
          messages()
              .get(
                  "hof.link.trigger-not-block", "link", link.get().id(), "n", Integer.toString(n)));
      return;
    }
    HallLayout.Trigger next = new HallLayout.Trigger.OnBlock(on.at(), kind.get());
    triggers.set(n - 1, next);
    save(
        source,
        hall.layout().withLink(link.get().withTriggers(triggers)),
        List.of(
            messages()
                .get(
                    "hof.link.trigger-kind",
                    "link",
                    link.get().id(),
                    "n",
                    Integer.toString(n),
                    "trigger",
                    describe(next))),
        "link-trigger-kind",
        link.get().id(),
        n + " " + kind.get().word());
  }

  /** {@code /hof link trigger remove <id> <n|looked>}. */
  void triggerRemove(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    String which = arguments.string("which").trim();
    List<HallLayout.Trigger> triggers = new ArrayList<>(link.get().triggers());
    int n = number(which);
    if (which.equals("looked")) {
      Optional<Position> looked =
          source.player().flatMap(p -> platform.players().targetBlock(p, REACH));
      for (int i = 0; i < triggers.size() && looked.isPresent(); i++) {
        if (HallLayout.sameBlock(triggers.get(i).at(), looked.get())) {
          n = i + 1;
        }
      }
    }
    if (n < 1 || n > triggers.size()) {
      source.reply(messages().get("hof.link.trigger-unknown", "link", link.get().id(), "n", which));
      return;
    }
    HallLayout.Trigger gone = triggers.remove(n - 1);
    save(
        source,
        hall.layout().withLink(link.get().withTriggers(triggers)),
        List.of(
            messages()
                .get(
                    "hof.link.trigger-removed",
                    "link",
                    link.get().id(),
                    "n",
                    Integer.toString(n),
                    "trigger",
                    describe(gone))),
        "link-trigger-remove",
        link.get().id(),
        describe(gone));
  }

  /** {@code /hof link landing <id> [position [yaw [pitch]]]}. */
  void landing(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    Optional<Position> at = spot(source, Words.of(arguments.optionalString("at").orElse("here")));
    if (at.isEmpty()) {
      return;
    }
    save(
        source,
        hall.layout().withLink(link.get().withLanding(at)),
        List.of(messages().get("hof.link.landing", place("link", link.get().id(), at.get()))),
        "link-landing",
        link.get().id(),
        CellEdits.corner(at.get()));
  }

  void pair(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> a = link(source, arguments.string("link"));
    Optional<HallLayout.Link> b =
        a.isEmpty() ? Optional.empty() : link(source, arguments.string("other"));
    if (a.isEmpty() || b.isEmpty()) {
      return;
    }
    if (a.get().id().equals(b.get().id())) {
      source.reply(messages().get("hof.link.self"));
      return;
    }
    HallLayout next =
        unpaired(unpaired(hall.layout(), a.get()), b.get())
            .withLink(
                hall.layout().link(a.get().id()).orElseThrow().withPair(Optional.of(b.get().id())));
    next = next.withLink(next.link(b.get().id()).orElseThrow().withPair(Optional.of(a.get().id())));
    save(
        source,
        next,
        List.of(messages().get("hof.link.paired", "link", a.get().id(), "other", b.get().id())),
        "link-pair",
        a.get().id(),
        b.get().id());
  }

  void unpair(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    save(
        source,
        unpaired(hall.layout(), link.get()),
        List.of(messages().get("hof.link.unpaired", "link", link.get().id())),
        "link-unpair",
        link.get().id(),
        "");
  }

  /** A layout where a link and its partner, if any, are no longer paired. */
  private static HallLayout unpaired(HallLayout layout, HallLayout.Link link) {
    HallLayout next =
        layout.withLink(layout.link(link.id()).orElse(link).withPair(Optional.empty()));
    if (link.pair().isPresent()) {
      Optional<HallLayout.Link> other = next.link(link.pair().get());
      if (other.isPresent() && other.get().pair().filter(link.id()::equals).isPresent()) {
        next = next.withLink(other.get().withPair(Optional.empty()));
      }
    }
    return next;
  }

  /** {@code /hof link rename <id> <new>}: every link leading to it, and its partner, follow. */
  void rename(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    String id = link.get().id();
    String next = arguments.string("new");
    if (!HallFiles.ID.matcher(next).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    if (hall.layout().link(next).isPresent()) {
      source.reply(messages().get("hof.link.exists", "link", next));
      return;
    }
    Map<String, HallLayout.Link> renamed = new LinkedHashMap<>();
    for (HallLayout.Link each : hall.layout().links().values()) {
      HallLayout.Link changed = each.id().equals(id) ? each.withId(next) : each;
      if (changed.to().filter(t -> t.equals(new HallLayout.Target.ToLink(id))).isPresent()) {
        changed = changed.withTo(Optional.of(new HallLayout.Target.ToLink(next)));
      }
      if (changed.pair().filter(id::equals).isPresent()) {
        changed = changed.withPair(Optional.of(next));
      }
      renamed.put(changed.id(), changed);
    }
    save(
        source,
        hall.layout().withLinks(renamed),
        List.of(messages().get("hof.link.renamed", "link", id, "new", next)),
        "link-rename",
        "link:" + id,
        next);
  }

  void name(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    String text = LinkText.clean(arguments.string("text").trim(), styleTags());
    save(
        source,
        hall.layout().withLink(link.get().withName(Optional.of(text))),
        List.of(
            messages()
                .get("hof.link.named", "link", link.get().id(), "name", LinkText.plain(text))),
        "link-name",
        link.get().id(),
        text);
  }

  /** {@code /hof link access <id> everyone|op|permission [node]}. */
  void access(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    List<String> words = Words.of(arguments.string("who"));
    HallLayout.Access access;
    switch (words.isEmpty() ? "" : words.getFirst()) {
      case "everyone" -> access = HallLayout.Access.EVERYONE;
      case "op" -> access = HallLayout.Access.OP;
      case "permission" ->
          access =
              new HallLayout.Access(
                  HallLayout.Access.Kind.PERMISSION,
                  words.size() > 1 ? Optional.of(words.get(1)) : Optional.empty());
      default -> {
        source.reply(messages().get("hof.link.access-usage"));
        return;
      }
    }
    save(
        source,
        hall.layout().withLink(link.get().withAccess(access)),
        List.of(
            messages()
                .get("hof.link.access", "link", link.get().id(), "who", who(link.get(), access))),
        "link-access",
        link.get().id(),
        access.word() + access.node().map(n -> " " + n).orElse(""));
  }

  /** {@code /hof link when <id> <condition>}. */
  void when(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, arguments.string("link"));
    if (link.isEmpty()) {
      return;
    }
    Optional<HallLayout.When> when = HallLayout.When.parse(arguments.string("condition"));
    if (when.isEmpty()) {
      source.reply(messages().get("hof.link.when-usage"));
      return;
    }
    save(
        source,
        hall.layout().withLink(link.get().withWhen(when.get())),
        List.of(messages().get("hof.link.when", "link", link.get().id(), "when", when(when.get()))),
        "link-when",
        link.get().id(),
        when.get().text());
  }

  /** {@code /hof link go <id>}: a player takes a link, its access and rules applying. */
  void go(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    String id = arguments.string("link");
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      source.reply(messages().get("hof.link.unknown", "link", id));
      return;
    }
    visits.takeLink(source.player().get(), link.get());
  }

  /** {@code /hof link remove <id> [--both] [--force]}: refused while used or paired. */
  public void remove(CommandSource source, String id, String options) {
    if (left(source)) {
      return;
    }
    Optional<HallLayout.Link> link = link(source, id);
    if (link.isEmpty()) {
      return;
    }
    List<String> words = Words.of(options);
    boolean both = words.contains("--both");
    boolean force = words.contains("--force");
    HallLayout layout = hall.layout();
    Set<String> gone = new java.util.LinkedHashSet<>(List.of(id));
    Optional<String> partner = link.get().pair().filter(p -> layout.link(p).isPresent());
    if (partner.isPresent() && !both) {
      source.reply(messages().get("hof.link.remove-pair", "link", id, "other", partner.get()));
      return;
    }
    partner.ifPresent(gone::add);
    List<String> users =
        layout.links().values().stream()
            .filter(l -> !gone.contains(l.id()))
            .filter(l ->
                l.to()
                    .filter(
                        t -> t instanceof HallLayout.Target.ToLink(String to) && gone.contains(to))
                    .isPresent())
            .map(HallLayout.Link::id)
            .toList();
    if (!users.isEmpty() && !force) {
      source.reply(
          messages().get("hof.link.remove-used", "link", id, "users", String.join(", ", users)));
      return;
    }
    HallLayout next = layout;
    for (String each : gone) {
      next = next.withoutLink(each);
    }
    for (String user : users) {
      next = next.withLink(next.link(user).orElseThrow().withTo(Optional.empty()));
    }
    Message reply =
        partner.isPresent()
            ? messages().get("hof.link.removed-both", "link", id, "other", partner.get())
            : users.isEmpty()
                ? messages().get("hof.link.removed", "link", id)
                : messages()
                    .get("hof.link.removed-forced", "link", id, "users", String.join(", ", users));
    save(source, next, List.of(reply), "link-remove", "link:" + id, String.join(", ", gone));
  }

  /** {@code /hof link list [near|world|to <destination>]}. */
  public void list(
      CommandSource source,
      String filter,
      java.util.function.Function<HallLayout.Link, Message> line) {
    List<String> words = Words.of(filter);
    Optional<Position> origin = source.player().map(p -> platform.players().position(p));
    java.util.function.Predicate<HallLayout.Link> keep = l -> true;
    if (!words.isEmpty()) {
      switch (words.getFirst()) {
        case "near" -> {
          if (origin.isEmpty()) {
            source.reply(messages().get("hof.player-only"));
            return;
          }
          keep = l -> near(l, origin.get(), NEAR);
        }
        case "world" -> {
          if (origin.isEmpty()) {
            source.reply(messages().get("hof.player-only"));
            return;
          }
          keep = l -> l.placed() && l.block().dimension().equals(origin.get().dimension());
        }
        case "to" -> {
          Optional<HallLayout.Target> target =
              destination(source, "", words.subList(1, words.size()));
          if (target.isEmpty()) {
            return;
          }
          keep = l -> l.to().equals(target);
        }
        default -> {
          source.reply(messages().get("hof.link.list-usage"));
          return;
        }
      }
    }
    List<HallLayout.Link> shown = hall.layout().links().values().stream().filter(keep).toList();
    if (shown.isEmpty()) {
      source.reply(messages().get("hof.link.none"));
      return;
    }
    shown.forEach(l -> source.reply(line.apply(l)));
  }

  private static boolean near(HallLayout.Link link, Position at, int distance) {
    for (HallLayout.Trigger trigger : link.triggers()) {
      Position t = trigger.at();
      if (t.dimension().equals(at.dimension())
          && Math.hypot(Math.hypot(t.x() - at.x(), t.y() - at.y()), t.z() - at.z()) <= distance) {
        return true;
      }
    }
    return false;
  }

  /** The lines {@code /hof link info} adds for a link 1.1 couldn't have made. */
  public List<Message> details(HallLayout.Link link) {
    List<Message> lines = new ArrayList<>();
    if (link.simple()) {
      return lines;
    }
    link.name()
        .ifPresent(n -> lines.add(messages().get("hof.link.info-name", "name", LinkText.plain(n))));
    for (int i = 0; i < link.triggers().size(); i++) {
      lines.add(
          messages()
              .get(
                  "hof.link.info-trigger",
                  "n",
                  Integer.toString(i + 1),
                  "trigger",
                  describe(link.triggers().get(i))));
    }
    if (!link.access().equals(HallLayout.Access.EVERYONE)
        || !link.when().equals(HallLayout.When.ALWAYS)) {
      String rule = who(link, link.access());
      if (!link.when().equals(HallLayout.When.ALWAYS)) {
        rule = rule + ", " + when(link.when());
      }
      lines.add(messages().get("hof.link.info-access", "rule", rule));
    }
    link.landing()
        .ifPresent(
            at -> lines.add(messages().get("hof.link.info-landing", place("link", link.id(), at))));
    link.pair().ifPresent(p -> lines.add(messages().get("hof.link.info-pair", "other", p)));
    return lines;
  }

  // Points

  void pointAdd(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    String name = arguments.string("point");
    if (!HallFiles.ID.matcher(name).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    if (hall.layout().point(name).isPresent()) {
      source.reply(messages().get("hof.point.exists", "point", name));
      return;
    }
    Optional<HallLayout.Point> point = pointAt(source, name, arguments.optionalString("at"));
    if (point.isEmpty()) {
      return;
    }
    savePoint(source, hall.layout().withPoint(point.get()), point.get(), "point-add", false);
  }

  void pointMove(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    String name = arguments.string("point");
    if (hall.layout().point(name).isEmpty()) {
      source.reply(messages().get("hof.point.unknown", "point", name));
      return;
    }
    Optional<HallLayout.Point> point = pointAt(source, name, arguments.optionalString("at"));
    if (point.isEmpty()) {
      return;
    }
    savePoint(source, hall.layout().withPoint(point.get()), point.get(), "point-move", true);
  }

  private void savePoint(
      CommandSource source, HallLayout next, HallLayout.Point point, String action, boolean moved) {
    Message reply =
        point.area().isPresent() && !moved
            ? messages()
                .get(
                    "hof.point.added-area",
                    "point",
                    point.name(),
                    "from",
                    CellEdits.corner(point.area().get().min()),
                    "to",
                    CellEdits.corner(point.area().get().max()))
            : messages()
                .get(
                    moved ? "hof.point.moved" : "hof.point.added",
                    place("point", point.name(), point.at()));
    save(
        source,
        next,
        List.of(reply),
        action,
        "point:" + point.name(),
        CellEdits.corner(point.at()));
  }

  /** A point from typed words: a spot with a yaw, or a box of two corners; here by default. */
  private Optional<HallLayout.Point> pointAt(
      CommandSource source, String name, Optional<String> typed) {
    List<String> words = Words.of(typed.orElse("here"));
    Optional<Position> origin = origin(source);
    String dimension = origin.map(Position::dimension).orElse(OVERWORLD);
    if (words.size() == 6) {
      Positions.Read a = Positions.block(words, 0, origin, dimension);
      Positions.Read b = Positions.block(words, 3, origin, dimension);
      if (a instanceof Positions.Read.Block from && b instanceof Positions.Read.Block to) {
        Bounds box = box(from.block(), to.block());
        if (tooBig(source, box)) {
          return Optional.empty();
        }
        return Optional.of(new HallLayout.Point(name, from.block(), Optional.of(box)));
      }
      source.reply(messages().get(Positions.BAD));
      return Optional.empty();
    }
    return spot(source, words).map(at -> new HallLayout.Point(name, at, Optional.empty()));
  }

  void pointRename(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    String name = arguments.string("point");
    Optional<HallLayout.Point> point = hall.layout().point(name);
    if (point.isEmpty()) {
      source.reply(messages().get("hof.point.unknown", "point", name));
      return;
    }
    String next = arguments.string("new");
    if (!HallFiles.ID.matcher(next).matches()) {
      source.reply(messages().get("hof.bad-id"));
      return;
    }
    if (hall.layout().point(next).isPresent()) {
      source.reply(messages().get("hof.point.exists", "point", next));
      return;
    }
    Map<String, HallLayout.Point> points = new LinkedHashMap<>();
    for (HallLayout.Point each : hall.layout().points().values()) {
      HallLayout.Point changed =
          each.name().equals(name) ? new HallLayout.Point(next, each.at(), each.area()) : each;
      points.put(changed.name(), changed);
    }
    Map<String, HallLayout.Link> rewritten = new LinkedHashMap<>();
    for (HallLayout.Link link : hall.layout().links().values()) {
      rewritten.put(
          link.id(),
          link.to().filter(t -> t.equals(new HallLayout.Target.AtPoint(name))).isPresent()
              ? link.withTo(Optional.of(new HallLayout.Target.AtPoint(next)))
              : link);
    }
    save(
        source,
        hall.layout().withPoints(points).withLinks(rewritten),
        List.of(messages().get("hof.point.renamed", "point", name, "new", next)),
        "point-rename",
        "point:" + name,
        next);
  }

  void pointRemove(CommandSource source, Arguments arguments) {
    if (left(source)) {
      return;
    }
    String name = arguments.string("point");
    if (hall.layout().point(name).isEmpty()) {
      source.reply(messages().get("hof.point.unknown", "point", name));
      return;
    }
    boolean force = Words.of(arguments.optionalString("options").orElse("")).contains("--force");
    List<String> users =
        hall.layout().links().values().stream()
            .filter(
                l -> l.to().filter(t -> t.equals(new HallLayout.Target.AtPoint(name))).isPresent())
            .map(HallLayout.Link::id)
            .toList();
    if (!users.isEmpty() && !force) {
      source.reply(
          messages().get("hof.point.used", "point", name, "users", String.join(", ", users)));
      return;
    }
    HallLayout next = hall.layout().withoutPoint(name);
    for (String user : users) {
      next = next.withLink(next.link(user).orElseThrow().withTo(Optional.empty()));
    }
    save(
        source,
        next,
        List.of(
            users.isEmpty()
                ? messages().get("hof.point.removed", "point", name)
                : messages()
                    .get(
                        "hof.point.removed-forced",
                        "point",
                        name,
                        "users",
                        String.join(", ", users))),
        "point-remove",
        "point:" + name,
        String.join(", ", users));
  }

  void pointList(CommandSource source, Arguments arguments) {
    if (hall.layout().points().isEmpty()) {
      source.reply(messages().get("hof.point.none"));
      return;
    }
    for (HallLayout.Point point : hall.layout().points().values()) {
      source.reply(messages().get("hof.point.line", place("point", point.name(), point.at())));
    }
  }

  void pointInfo(CommandSource source, Arguments arguments) {
    String name = arguments.string("point");
    Optional<HallLayout.Point> point = hall.layout().point(name);
    if (point.isEmpty()) {
      source.reply(messages().get("hof.point.unknown", "point", name));
      return;
    }
    source.reply(messages().get("hof.point.line", place("point", name, point.get().at())));
  }

  void pointTp(CommandSource source, Arguments arguments) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("hof.player-only"));
      return;
    }
    String name = arguments.string("point");
    Optional<HallLayout.Point> point = hall.layout().point(name);
    if (point.isEmpty()) {
      source.reply(messages().get("hof.point.unknown", "point", name));
      return;
    }
    visits.adminGo(source.player().get(), hall.land(point.get().at(), point.get().area()));
  }

  // Reading words

  /** What the flags after a source or destination say, and the words left. */
  private record Flags(
      List<String> words,
      Optional<HallLayout.BlockKind> kind,
      boolean area,
      boolean twoWay,
      Optional<String> name,
      boolean bad) {}

  private static Flags flags(List<String> all) {
    List<String> words = new ArrayList<>();
    Optional<HallLayout.BlockKind> kind = Optional.empty();
    boolean area = false;
    boolean twoWay = false;
    Optional<String> name = Optional.empty();
    for (int i = 0; i < all.size(); i++) {
      String word = all.get(i);
      switch (word) {
        case "--trigger" -> {
          if (i + 1 >= all.size()) {
            return new Flags(words, kind, area, twoWay, name, true);
          }
          String next = all.get(++i);
          if (next.equals("area")) {
            area = true;
          } else {
            kind = HallLayout.BlockKind.of(next);
            if (kind.isEmpty()) {
              return new Flags(words, kind, area, twoWay, name, true);
            }
          }
        }
        case "--two-way" -> twoWay = true;
        case "--name" -> {
          // The name takes the rest of the line.
          name = Optional.of(String.join(" ", all.subList(i + 1, all.size())));
          i = all.size();
        }
        default -> {
          if (word.startsWith("--")) {
            return new Flags(words, kind, area, twoWay, name, true);
          }
          words.add(word);
        }
      }
    }
    return new Flags(words, kind, area, twoWay, name.filter(n -> !n.isBlank()), false);
  }

  /** The trigger a source's words name, telling the sender if they name none. */
  @SuppressWarnings("RefactorSwitch") // Each case reads its own words and returns early.
  private Optional<HallLayout.Trigger> trigger(
      CommandSource source, List<String> words, Optional<HallLayout.BlockKind> kind, boolean area) {
    List<String> rest = new ArrayList<>(words);
    Optional<Position> origin = origin(source);
    String dimension = origin.map(Position::dimension).orElse(OVERWORLD);
    if (rest.isEmpty()) {
      source.reply(messages().get("hof.link.bad-source"));
      return Optional.empty();
    }
    switch (rest.getFirst()) {
      case Positions.SELECTION -> {
        Optional<Bounds> box = source.player().flatMap(wand::selection);
        if (box.isEmpty()) {
          source.reply(messages().get("hof.wand.no-selection"));
          return Optional.empty();
        }
        if (tooBig(source, box.get())) {
          return Optional.empty();
        }
        return Optional.of(new HallLayout.Trigger.Area(box.get()));
      }
      case "looked" -> {
        Optional<Position> looked =
            source.player().flatMap(p -> platform.players().targetBlock(p, REACH));
        if (looked.isEmpty()) {
          source.reply(messages().get("hof.cell.anchor-where"));
          return Optional.empty();
        }
        return Optional.of(onBlock(blockOf(looked.get()), kind));
      }
      case "point" -> {
        String name = rest.size() > 1 ? rest.get(1) : "";
        Optional<HallLayout.Point> point = hall.layout().point(name);
        if (point.isEmpty()) {
          source.reply(messages().get("hof.point.unknown", "point", name));
          return Optional.empty();
        }
        if (point.get().area().isPresent()) {
          return Optional.of(new HallLayout.Trigger.Area(point.get().area().get()));
        }
        return Optional.of(
            new HallLayout.Trigger.OnBlock(
                blockOf(point.get().at()), kind.orElse(HallLayout.BlockKind.STEP)));
      }
      default -> {
        Positions.Read first = Positions.block(rest, 0, origin, dimension);
        if (!(first instanceof Positions.Read.Block a)) {
          source.reply(messages().get(((Positions.Read.Failed) first).messageKey()));
          return Optional.empty();
        }
        if (rest.size() >= a.used() + 3) {
          Positions.Read second = Positions.block(rest, a.used(), origin, dimension);
          if (second instanceof Positions.Read.Block b) {
            Bounds box = box(a.block(), b.block());
            if (tooBig(source, box)) {
              return Optional.empty();
            }
            return Optional.of(new HallLayout.Trigger.Area(box));
          }
        }
        if (rest.size() != a.used()) {
          source.reply(messages().get("hof.link.bad-source"));
          return Optional.empty();
        }
        Position block = blockOf(a.block());
        if (area) {
          return Optional.of(new HallLayout.Trigger.Area(box(block, block)));
        }
        if (rest.getFirst().equals(Positions.HERE)) {
          // Where the admin stands: a tile to step on, unless told otherwise.
          return Optional.of(
              new HallLayout.Trigger.OnBlock(block, kind.orElse(HallLayout.BlockKind.STEP)));
        }
        return Optional.of(onBlock(block, kind));
      }
    }
  }

  private HallLayout.Trigger onBlock(Position block, Optional<HallLayout.BlockKind> kind) {
    return new HallLayout.Trigger.OnBlock(
        block, kind.orElseGet(() -> LinkService.newKind(platform.world().block(block))));
  }

  /** The block the wand put aside, or the selection, for {@code add} with no {@code from}. */
  private Optional<HallLayout.Trigger> pending(
      CommandSource source, Optional<HallLayout.BlockKind> kind) {
    if (source.player().isEmpty()) {
      return Optional.empty();
    }
    PlayerId player = source.player().get();
    Optional<Position> block = wand.takePending(player);
    if (block.isPresent()) {
      return Optional.of(onBlock(block.get(), kind));
    }
    return Optional.empty();
  }

  /** A destination's words, telling the sender if they name none. */
  @SuppressWarnings("RefactorSwitch") // Each case checks its own words and returns early.
  private Optional<HallLayout.Target> destination(
      CommandSource source, String self, List<String> words) {
    if (words.isEmpty()) {
      source.reply(messages().get("hof.link.bad-destination"));
      return Optional.empty();
    }
    String second = words.size() > 1 ? words.get(1) : "";
    switch (words.getFirst()) {
      case "hall" -> {
        return words.size() == 1 ? Optional.of(new HallLayout.Target.Hall()) : bad(source);
      }
      case "return" -> {
        return words.size() == 1 ? Optional.of(new HallLayout.Target.Return()) : bad(source);
      }
      case "season" -> {
        int season = number(second);
        return season >= 1 && words.size() == 2
            ? Optional.of(new HallLayout.Target.SeasonSpawn(season))
            : bad(source);
      }
      case "point" -> {
        if (hall.layout().point(second).isEmpty()) {
          source.reply(messages().get("hof.point.unknown", "point", second));
          return Optional.empty();
        }
        return Optional.of(new HallLayout.Target.AtPoint(second));
      }
      case "link" -> {
        if (second.equals(self)) {
          source.reply(messages().get("hof.link.self"));
          return Optional.empty();
        }
        if (hall.layout().link(second).isEmpty()) {
          source.reply(messages().get("hof.link.unknown", "link", second));
          return Optional.empty();
        }
        return Optional.of(new HallLayout.Target.ToLink(second));
      }
      case "gallery" -> {
        if (hall.gallery(second).isEmpty()) {
          source.reply(messages().get("hof.no-gallery", "gallery", second));
          return Optional.empty();
        }
        return Optional.of(new HallLayout.Target.ToGallery(second));
      }
      default -> {
        return spot(source, words).map(HallLayout.Target.At::new);
      }
    }
  }

  private Optional<HallLayout.Target> bad(CommandSource source) {
    source.reply(messages().get("hof.link.bad-destination"));
    return Optional.empty();
  }

  /**
   * A spot: {@code here} with the sender's facing, or a position with an optional yaw and pitch.
   */
  private Optional<Position> spot(CommandSource source, List<String> words) {
    Optional<Position> origin = origin(source);
    String dimension = origin.map(Position::dimension).orElse(OVERWORLD);
    Positions.Read read = Positions.block(words, 0, origin, dimension);
    if (!(read instanceof Positions.Read.Block found)) {
      source.reply(messages().get(((Positions.Read.Failed) read).messageKey()));
      return Optional.empty();
    }
    float yaw = words.getFirst().equals(Positions.HERE) ? Positions.facing(origin) : 0f;
    float pitch = 0f;
    int used = found.used();
    if (words.size() > used) {
      Optional<Float> typed = Positions.yaw(words.get(used));
      if (typed.isEmpty()) {
        source.reply(messages().get(Positions.BAD));
        return Optional.empty();
      }
      yaw = typed.get();
    }
    if (words.size() > used + 1) {
      Optional<Float> typed = Positions.yaw(words.get(used + 1));
      if (typed.isEmpty() || words.size() > used + 2) {
        source.reply(messages().get(Positions.BAD));
        return Optional.empty();
      }
      pitch = typed.get();
    }
    Position at = found.block();
    return Optional.of(
        new Position(at.dimension(), at.blockX(), at.blockY(), at.blockZ(), yaw, pitch));
  }

  // Helpers

  /** Whether a trigger's block is another link's already; says so if it is. */
  private boolean taken(
      CommandSource source, HallLayout layout, HallLayout.Trigger trigger, String id) {
    if (trigger instanceof HallLayout.Trigger.OnBlock on) {
      Optional<HallLayout.Link> there = layout.linkAt(on.at());
      if (there.isPresent() && !there.get().id().equals(id)) {
        source.reply(messages().get("hof.link.taken", place("link", there.get().id(), on.at())));
        return true;
      }
      for (Gallery gallery : hall.galleries()) {
        if (gallery.contains(on.at()) && edits.held(source, gallery)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean tooBig(CommandSource source, Bounds box) {
    try {
      GalleryFiles.checkArea(box, "area");
      return false;
    } catch (GalleryFiles.Invalid e) {
      source.reply(
          messages().get("hof.area.too-big", "max", Integer.toString(GalleryFiles.MAX_AREA)));
      return true;
    }
  }

  /** A trigger as admins read it. */
  public String describe(HallLayout.Trigger trigger) {
    return describe(messages(), trigger);
  }

  /** A trigger as admins read it. */
  public static String describe(Messages messages, HallLayout.Trigger trigger) {
    return switch (trigger) {
      case HallLayout.Trigger.OnBlock(Position at, HallLayout.BlockKind kind) ->
          messages.text("hof.link.trigger-" + kind.word(), xyz(at));
      case HallLayout.Trigger.Area(Bounds box) ->
          messages.text(
              "hof.link.trigger-area",
              "from",
              CellEdits.corner(box.min()),
              "to",
              CellEdits.corner(box.max()));
      case HallLayout.Trigger.Text text -> messages.text("hof.link.trigger-text", xyz(text.at()));
    };
  }

  private String who(HallLayout.Link link, HallLayout.Access access) {
    return switch (access.kind()) {
      case EVERYONE -> messages().text("hof.link.who-everyone");
      case OP -> messages().text("hof.link.who-op");
      case PERMISSION -> messages().text("hof.link.who-node", "node", access.node(link.id()));
    };
  }

  private String when(HallLayout.When when) {
    return switch (when.kind()) {
      case "after-homecoming" -> messages().text("hof.link.when-homecoming");
      case "stage" -> messages().text("hof.link.when-stage", "stage", when.stage().orElse("open"));
      case "played" ->
          messages()
              .text("hof.link.when-played", "season", Integer.toString(when.season().orElse(0)));
      default -> messages().text("hof.link.when-always");
    };
  }

  private Optional<HallLayout.Link> link(CommandSource source, String id) {
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      source.reply(messages().get("hof.link.unknown", "link", id));
    }
    return link;
  }

  /** Whether hall.yml was left out at startup, so links mustn't be written; says so if it was. */
  private boolean left(CommandSource source) {
    if (hall.hallLeftOut()) {
      source.reply(messages().get("hof.hall.left-out"));
      return true;
    }
    return false;
  }

  private void save(
      CommandSource source,
      HallLayout next,
      List<Message> replies,
      String action,
      String target,
      String details) {
    String audited = target.contains(":") ? target : "link:" + target;
    edits.saved(
        source,
        hall.saveHall(next),
        replies.getFirst(),
        () -> {
          replies.subList(1, replies.size()).forEach(source::reply);
          hall.audited(actor(source), action, audited, details);
        });
  }

  private String actor(CommandSource source) {
    return source
        .player()
        .flatMap(p -> platform.players().find(p))
        .map(PlayerRef::name)
        .orElse("console");
  }

  private Optional<Position> origin(CommandSource source) {
    return source.player().map(p -> platform.players().position(p));
  }

  private static Position blockOf(Position at) {
    return Position.of(at.dimension(), at.blockX(), at.blockY(), at.blockZ());
  }

  private static Bounds box(Position a, Position b) {
    return Bounds.between(
        a.dimension(), a.blockX(), a.blockY(), a.blockZ(), b.blockX(), b.blockY(), b.blockZ());
  }

  private static int number(String word) {
    try {
      return Integer.parseInt(word.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String[] xyz(Position at) {
    return new String[] {
      "x",
      Integer.toString(at.blockX()),
      "y",
      Integer.toString(at.blockY()),
      "z",
      Integer.toString(at.blockZ())
    };
  }

  private static String[] place(String key, String value, Position at) {
    return new String[] {
      key,
      value,
      "x",
      Integer.toString(at.blockX()),
      "y",
      Integer.toString(at.blockY()),
      "z",
      Integer.toString(at.blockZ())
    };
  }

  private List<String> styleTags() {
    return config.get().config().links().styleTags();
  }

  private Messages messages() {
    return config.get().messages();
  }
}
