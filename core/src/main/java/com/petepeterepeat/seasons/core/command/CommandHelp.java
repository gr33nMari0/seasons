// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Help built from the command trees, suggestions on every argument, and next-step hints (questions
 * 266 to 268). Commands are decorated with {@link #decorate} before they are registered: every part
 * of a command that has words after it runs too, listing them; a word that isn't one of them says
 * so; and every root gets {@code help}.
 */
public final class CommandHelp {
  /** The word for help under each root. */
  public static final String HELP = "help";

  /** The argument that catches a word no subcommand has. */
  static final String UNKNOWN = "subcommand";

  /** Where each argument's suggestions come from, by its path and node. */
  @FunctionalInterface
  public interface Suggestions {
    Optional<Suggester> forArgument(List<String> path, CommandNode argument);
  }

  /** What argument values are, when they are one of a few: shown as {@code (a|b|c)}. */
  @FunctionalInterface
  public interface Choices {
    Optional<List<String>> of(List<String> path, CommandNode argument);
  }

  private final Supplier<Messages> messages;
  private final BiPredicate<CommandSource, String> permitted;
  private final BooleanSupplier hintsOn;
  private final Map<String, CommandNode> roots = new LinkedHashMap<>();
  private Choices choices = (path, argument) -> Optional.empty();

  /**
   * @param permitted whether a source holds a permission node; the console holds every one
   * @param hintsOn whether next steps are offered ({@code admin.hints})
   */
  public CommandHelp(
      Supplier<Messages> messages,
      BiPredicate<CommandSource, String> permitted,
      BooleanSupplier hintsOn) {
    this.messages = messages;
    this.permitted = permitted;
    this.hintsOn = hintsOn;
  }

  /** Sets where choices come from, for usage lines. */
  public void choices(Choices choices) {
    this.choices = choices;
  }

  /** A help action, told apart so help lines leave it out. */
  private record HelpAction(List<String> path, CommandHelp help) implements CommandAction {
    @Override
    public void run(CommandSource source, Arguments arguments) {
      help.show(source, path);
    }
  }

  /**
   * The root with help, suggestions and unknown-word replies added, kept for help lines and hints.
   */
  public CommandNode decorate(CommandNode root, Suggestions suggestions) {
    CommandNode decorated = decorate(root, List.of(), List.of(), suggestions);
    List<CommandNode> children = new ArrayList<>(decorated.children());
    children.removeIf(c -> c.isLiteral() && c.name().equals(HELP));
    children.add(
        CommandNode.literal(HELP)
            .executes((source, arguments) -> show(source, List.of(root.name())))
            .then(
                CommandNode.argument("words", ArgumentType.greedy())
                    .suggests((source, typed) -> helpWords(source, root.name(), typed))
                    .executes((source, arguments) -> {
                      List<String> path = new ArrayList<>();
                      path.add(root.name());
                      for (String word : Words.of(arguments.string("words"))) {
                        if (!word.isEmpty()) {
                          path.add(word);
                        }
                      }
                      show(source, path);
                    }))
            .build());
    decorated = decorated.withChildren(children);
    roots.put(root.name(), decorated);
    return decorated;
  }

  /**
   * Decorates one node. Help is by literal words, so the help of {@code /hof cell anchor <cell>} is
   * that of {@code /hof cell anchor}.
   *
   * @param parents the names of the nodes above, arguments included, for suggestions
   * @param literalParents the literal words above, for help
   */
  private CommandNode decorate(
      CommandNode node,
      List<String> parents,
      List<String> literalParents,
      Suggestions suggestions) {
    List<String> path = new ArrayList<>(parents);
    path.add(node.name());
    List<String> literalList = new ArrayList<>(literalParents);
    if (node.isLiteral()) {
      literalList.add(node.name());
    }
    List<String> literals = List.copyOf(literalList);
    CommandNode next = node;
    if (!next.isLiteral() && next.suggester().isEmpty()) {
      Optional<Suggester> found = suggestions.forArgument(List.copyOf(path), next);
      if (found.isPresent()) {
        next = next.withSuggester(found.get());
      }
    }
    List<CommandNode> children = new ArrayList<>();
    for (CommandNode child : next.children()) {
      children.add(decorate(child, path, literals, suggestions));
    }
    boolean onlyLiterals =
        !children.isEmpty() && children.stream().allMatch(CommandNode::isLiteral);
    if (onlyLiterals) {
      List<String> here = literals;
      children.add(
          CommandNode.argument(UNKNOWN, ArgumentType.greedy())
              .executes((source, arguments) -> unknown(source, here, arguments.string(UNKNOWN)))
              .build());
    }
    next = next.withChildren(children);
    if (next.action().isEmpty() && !children.isEmpty()) {
      next = next.withAction(new HelpAction(literals, this));
    }
    return next;
  }

  /** The commands under a path the source may run, as clickable usage lines. */
  public void show(CommandSource source, List<String> path) {
    Messages text = messages.get();
    List<Message> lines = lines(source, path);
    source.reply(text.get("help.header", "command", "/" + String.join(" ", path)));
    if (lines.isEmpty()) {
      source.reply(text.get("help.none", "command", "/" + String.join(" ", path)));
      return;
    }
    lines.forEach(source::reply);
  }

  private void unknown(CommandSource source, List<String> path, String typed) {
    String word = Words.of(typed).stream().findFirst().orElse("");
    source.reply(
        messages.get().get("help.unknown", "command", "/" + String.join(" ", path) + " " + word));
    show(source, path);
  }

  /** The usage lines under a path, for the source. */
  public List<Message> lines(CommandSource source, List<String> path) {
    CommandNode root = roots.get(path.getFirst());
    if (root == null) {
      return List.of();
    }
    List<List<CommandNode>> runs = new ArrayList<>();
    collect(source, root, new ArrayList<>(), runs);
    // Grouped by their words, in tree order: one line for /x and /x <y> [<z>].
    Map<String, List<List<CommandNode>>> groups = new LinkedHashMap<>();
    for (List<CommandNode> run : runs) {
      List<String> literals =
          run.stream().filter(CommandNode::isLiteral).map(CommandNode::name).toList();
      if (literals.size() < path.size() || !literals.subList(0, path.size()).equals(path)) {
        continue;
      }
      groups.computeIfAbsent(String.join(".", literals), k -> new ArrayList<>()).add(run);
    }
    List<Message> lines = new ArrayList<>();
    Messages text = messages.get();
    for (Map.Entry<String, List<List<CommandNode>>> group : groups.entrySet()) {
      List<List<CommandNode>> chain = new ArrayList<>(group.getValue());
      chain.sort(java.util.Comparator.comparingInt(List::size));
      for (String usage : usages(chain)) {
        String key = "help." + group.getKey();
        String description = text.has(key) ? text.text(key) : "";
        String typed = suggestion(chain.getFirst());
        lines.add(
            new Message(
                "<click:suggest_command:'"
                    + typed.replace("'", "\\'")
                    + "'><white><usage></white></click> <gray>- <description></gray>",
                Map.of("usage", usage, "description", description)));
      }
    }
    return lines;
  }

  /**
   * One command for the public command reference (M9): its words, each usage line, its help text
   * and the permission nodes that guard it, outermost first.
   *
   * @param words the literal words, root first, such as {@code [hof, link, go]}
   * @param usages the usage lines, as {@code /hof help} shows them
   * @param description the help text from {@code help.<words>} in messages.yml
   * @param permissions the permission nodes along the command's path, outermost first
   */
  public record Entry(
      List<String> words, List<String> usages, String description, List<String> permissions) {}

  /** Every command under every root, in tree order, for the public command reference. */
  public List<Entry> reference() {
    List<Entry> entries = new ArrayList<>();
    Messages text = messages.get();
    for (CommandNode root : roots.values()) {
      List<List<CommandNode>> runs = new ArrayList<>();
      collect(null, root, new ArrayList<>(), runs);
      Map<String, List<List<CommandNode>>> groups = new LinkedHashMap<>();
      for (List<CommandNode> run : runs) {
        String key =
            String.join(
                ".", run.stream().filter(CommandNode::isLiteral).map(CommandNode::name).toList());
        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(run);
      }
      for (Map.Entry<String, List<List<CommandNode>>> group : groups.entrySet()) {
        List<List<CommandNode>> chain = new ArrayList<>(group.getValue());
        chain.sort(java.util.Comparator.comparingInt(List::size));
        String key = "help." + group.getKey();
        List<String> permissions = new ArrayList<>();
        for (List<CommandNode> run : chain) {
          for (CommandNode node : run) {
            node.permission().filter(p -> !permissions.contains(p)).ifPresent(permissions::add);
          }
        }
        entries.add(
            new Entry(
                List.of(group.getKey().split("\\.")),
                usages(chain),
                text.has(key) ? text.text(key) : "",
                List.copyOf(permissions)));
      }
    }
    return entries;
  }

  /** The help keys every command needs, for a test that none is missing from messages.yml. */
  public List<String> keys() {
    List<String> keys = new ArrayList<>();
    for (CommandNode root : roots.values()) {
      List<List<CommandNode>> runs = new ArrayList<>();
      collect(null, root, new ArrayList<>(), runs);
      for (List<CommandNode> run : runs) {
        String key =
            "help."
                + String.join(
                    ".",
                    run.stream().filter(CommandNode::isLiteral).map(CommandNode::name).toList());
        if (!keys.contains(key)) {
          keys.add(key);
        }
        for (CommandNode node : run) {
          if (!node.isLiteral()) {
            String argument = String.join("|", argumentKeys(run, node));
            if (!keys.contains(argument)) {
              keys.add(argument);
            }
          }
        }
      }
    }
    return keys;
  }

  /** Every path that runs a command, not help, that the source may run. */
  private void collect(
      @Nullable CommandSource source,
      CommandNode node,
      List<CommandNode> parents,
      List<List<CommandNode>> runs) {
    if (node.name().equals(UNKNOWN) || (node.isLiteral() && node.name().equals(HELP))) {
      return;
    }
    if (source != null
        && node.permission().isPresent()
        && !permitted.test(source, node.permission().get())) {
      return;
    }
    List<CommandNode> path = new ArrayList<>(parents);
    path.add(node);
    if (node.action().isPresent() && !(node.action().get() instanceof HelpAction)) {
      runs.add(path);
    }
    for (CommandNode child : node.children()) {
      collect(source, child, path, runs);
    }
  }

  /** Usage lines for runs with the same words: each longer run in brackets after the shorter. */
  private List<String> usages(List<List<CommandNode>> runs) {
    List<List<List<CommandNode>>> chains = new ArrayList<>();
    for (List<CommandNode> run : runs) {
      Optional<List<List<CommandNode>>> extended =
          chains.stream().filter(c -> startsWith(run, c.getLast())).findFirst();
      if (extended.isPresent()) {
        extended.get().add(run);
      } else {
        List<List<CommandNode>> chain = new ArrayList<>();
        chain.add(run);
        chains.add(chain);
      }
    }
    List<String> usages = new ArrayList<>();
    for (List<List<CommandNode>> chain : chains) {
      StringBuilder usage = new StringBuilder("/");
      List<CommandNode> before = chain.getFirst();
      usage.append(String.join(" ", words(before, before)));
      int open = 0;
      for (int i = 1; i < chain.size(); i++) {
        List<CommandNode> run = chain.get(i);
        usage
            .append(" [")
            .append(String.join(" ", words(run.subList(before.size(), run.size()), run)));
        open++;
        before = run;
      }
      usage.append("]".repeat(open));
      usages.add(usage.toString());
    }
    return usages;
  }

  private static boolean startsWith(List<CommandNode> run, List<CommandNode> prefix) {
    return run.size() > prefix.size() && run.subList(0, prefix.size()).equals(prefix);
  }

  /** The words of part of a run: literals as they are, arguments as {@code <name: what>}. */
  private List<String> words(List<CommandNode> part, List<CommandNode> run) {
    List<String> words = new ArrayList<>();
    for (CommandNode node : part) {
      if (node.isLiteral()) {
        words.add(node.name());
        continue;
      }
      List<String> path = new ArrayList<>();
      for (CommandNode step : run) {
        path.add(step.name());
        if (step.equals(node)) {
          break;
        }
      }
      Optional<List<String>> values = choices.of(path, node);
      String what;
      if (values.isPresent()) {
        what = "(" + String.join("|", values.get()) + ")";
      } else {
        Messages text = messages.get();
        what =
            argumentKeys(run, node).stream()
                .filter(text::has)
                .findFirst()
                .map(text::text)
                .orElse(node.name());
      }
      words.add("<" + node.name() + ": " + what + ">");
    }
    return words;
  }

  /**
   * Where an argument's wording may be, most particular first: for its command ({@code
   * help.argument.season.marker.add.name}), its root ({@code help.argument.season.name}), or any
   * command ({@code help.argument.name}).
   */
  private static List<String> argumentKeys(List<CommandNode> run, CommandNode node) {
    List<String> literals = new ArrayList<>();
    for (CommandNode step : run) {
      if (step.equals(node)) {
        break;
      }
      if (step.isLiteral()) {
        literals.add(step.name());
      }
    }
    return List.of(
        "help.argument." + String.join(".", literals) + "." + node.name(),
        "help.argument." + literals.getFirst() + "." + node.name(),
        "help.argument." + node.name());
  }

  /** What clicking a help line types: the command's words up to its first argument. */
  private static String suggestion(List<CommandNode> run) {
    List<String> words = new ArrayList<>();
    for (CommandNode node : run) {
      if (!node.isLiteral()) {
        break;
      }
      words.add(node.name());
    }
    return "/" + String.join(" ", words) + " ";
  }

  private List<String> helpWords(CommandSource source, String root, String typed) {
    List<String> words = new ArrayList<>(Words.of(typed));
    if (!typed.endsWith(" ") && !words.isEmpty()) {
      words.removeLast();
    }
    words.removeIf(String::isEmpty);
    CommandNode node = roots.get(root);
    if (node == null) {
      return List.of();
    }
    for (String word : words) {
      Optional<CommandNode> next =
          node.children().stream().filter(c -> c.isLiteral() && c.name().equals(word)).findFirst();
      if (next.isEmpty()) {
        return List.of();
      }
      node = next.get();
    }
    return node.children().stream()
        .filter(CommandNode::isLiteral)
        .filter(c -> !c.name().equals(HELP))
        .filter(c -> c.permission().map(p -> permitted.test(source, p)).orElse(true))
        .map(CommandNode::name)
        .toList();
  }

  // Next steps (questions 268)

  /**
   * Offers the next steps after a setup step, from {@code next.<key>} in messages.yml: entries
   * split by {@code |}, each a command (clickable, offered only if the source may run it) or a
   * plain hint.
   */
  public void after(CommandSource source, String key, String... placeholders) {
    if (!hintsOn.getAsBoolean() || source.player().isEmpty()) {
      return;
    }
    Messages text = messages.get();
    String messageKey = "next." + key;
    if (!text.has(messageKey)) {
      return;
    }
    List<String> entries = new ArrayList<>();
    for (String entry :
        java.util.regex.Pattern.compile("\\|")
            .splitAsStream(text.text(messageKey, placeholders))
            .toList()) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (trimmed.startsWith("/") && !mayRun(source, trimmed)) {
        continue;
      }
      entries.add(trimmed);
    }
    if (entries.isEmpty()) {
      return;
    }
    StringBuilder template = new StringBuilder("<gray>Next: ");
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < entries.size(); i++) {
      String entry = entries.get(i);
      if (i > 0) {
        template.append(", ");
      }
      String name = "next" + i;
      values.put(name, entry);
      if (entry.startsWith("/")) {
        template
            .append("<click:suggest_command:'")
            .append(entry.replace("'", "\\'"))
            .append("'><white><u><")
            .append(name)
            .append("></u></white></click>");
      } else {
        template.append('<').append(name).append('>');
      }
    }
    template.append("</gray>");
    source.reply(new Message(template.toString(), values));
  }

  /** Whether the source may run a typed command, by the permissions along its path. */
  private boolean mayRun(CommandSource source, String command) {
    List<String> words = Words.of(command.substring(1));
    CommandNode node = roots.get(words.getFirst());
    if (node == null) {
      return false;
    }
    if (node.permission().isPresent() && !permitted.test(source, node.permission().get())) {
      return false;
    }
    for (int i = 1; i < words.size(); i++) {
      String word = words.get(i);
      Optional<CommandNode> next =
          node.children().stream().filter(c -> c.isLiteral() && c.name().equals(word)).findFirst();
      if (next.isEmpty()) {
        next =
            node.children().stream()
                .filter(c -> !c.isLiteral() && !c.name().equals(UNKNOWN))
                .findFirst();
      }
      if (next.isEmpty()) {
        return true;
      }
      node = next.get();
      if (node.permission().isPresent() && !permitted.test(source, node.permission().get())) {
        return false;
      }
    }
    return true;
  }
}
