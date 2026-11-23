// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.curate;

import com.petepeterepeat.seasons.core.announce.AdminAlerts;
import com.petepeterepeat.seasons.core.announce.Announcer;
import com.petepeterepeat.seasons.core.archive.ArchiveFiles;
import com.petepeterepeat.seasons.core.archive.ArchiveHash;
import com.petepeterepeat.seasons.core.archive.ArchivePages;
import com.petepeterepeat.seasons.core.archive.ArchiveRecord;
import com.petepeterepeat.seasons.core.archive.ArchiveSlot;
import com.petepeterepeat.seasons.core.archive.VaultFiles;
import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.guard.ArchiveHold;
import com.petepeterepeat.seasons.core.hall.HallRenderer;
import com.petepeterepeat.seasons.core.hall.HallService;
import com.petepeterepeat.seasons.core.hall.Museum;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.ChatInput;
import com.petepeterepeat.seasons.core.port.Menus;
import com.petepeterepeat.seasons.core.port.NeutralItem;
import com.petepeterepeat.seasons.core.port.PagedView;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.scenario.CrashPoint;
import com.petepeterepeat.seasons.core.scenario.CrashPoints;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import com.petepeterepeat.seasons.core.store.JournalRecovery;
import com.petepeterepeat.seasons.core.store.OpenOperation;
import com.petepeterepeat.seasons.core.store.Operation;
import com.petepeterepeat.seasons.core.store.RecoveryHandler;
import com.petepeterepeat.seasons.core.store.RecoveryOutcome;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Curation (see Curation in the spec): curators move items out of an archive only through four
 * actions, each saved, audited and announced, and the archive view never becomes an editable chest.
 *
 * <p>{@code /hof curate <player> <season>}, or sneak-clicking a cell's archive anchor or figure
 * with {@code seasons.curate.view}, opens the curator view: the archive's pages as the archive view
 * shows them, every click refused. A click on an item opens the actions the curator holds a node
 * for; the museum asks which free plinth. The curator then types the reason in chat, which Seasons
 * takes before anyone else sees it (questions 215), unless {@code curate.requireReason} is off.
 *
 * <p>Each action is one transaction, queued per curator: keep the item aside ({@link CurateFiles}),
 * journal {@code CURATE_STARTED}, save the archive without it, deliver it, then forget the copy and
 * journal {@code CURATE_DONE}. A delivery that fails (a full inventory, a plinth taken since) puts
 * the archive back as it was. After a crash, whether the archive still holds the item and whether
 * it arrived say which way to settle. The owner and the curator are held throughout, so no
 * archiving, unarchive or vault withdrawal of theirs runs alongside. Destroyed items wait 30 days
 * in the recycle bin, and {@code /hof curate undo <id>} puts one back in its slot.
 *
 * <p>Every action is audited with the item's snapshot, owner, season, slot, destination, curator
 * and reason; the admin webhook hears all of it, the owner is told in-game (at their next login if
 * offline) with the reason, and the public channel gets a line without it (questions 214).
 */
public final class Curation implements ChatInput, HallService.CuratorView {
  public static final String PERMISSION_VIEW = "seasons.curate.view";
  public static final String PERMISSION_MUSEUM = "seasons.curate.museum";
  public static final String PERMISSION_RETURN = "seasons.curate.return";
  public static final String PERMISSION_TAKE = "seasons.curate.take";
  public static final String PERMISSION_DESTROY = "seasons.curate.destroy";

  /** How long the recycle bin keeps a destroyed item. */
  public static final Duration KEEP = Duration.ofDays(30);

  /** How long a curator has to type the reason. */
  public static final Duration PROMPT = Duration.ofMinutes(2);

  /** How often the recycle bin is emptied of what is older than {@link #KEEP}. */
  static final Duration PURGE = Duration.ofHours(1);

  /** How often unanswered questions are looked at. */
  static final Duration EXPIRE = Duration.ofSeconds(1);

  private static final Logger LOG = SeasonsLog.get();
  private static final String NONE = "-";
  private static final String UNDO = "undo";

  /** The four actions, in the order the action menu offers them. */
  public enum Action {
    MUSEUM("minecraft:item_frame", PERMISSION_MUSEUM),
    RETURN("minecraft:ender_chest", PERMISSION_RETURN),
    TAKE("minecraft:hopper", PERMISSION_TAKE),
    DESTROY("minecraft:lava_bucket", PERMISSION_DESTROY);

    private final String icon;
    private final String permission;

    Action(String icon, String permission) {
      this.icon = icon;
      this.permission = permission;
    }

    /** Its word in messages.yml, the audit log and the journal: {@code museum}, {@code take}. */
    public String word() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Optional<Action> of(String word) {
      for (Action action : values()) {
        if (action.word().equals(word)) {
          return Optional.of(action);
        }
      }
      return Optional.empty();
    }
  }

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final ArchiveFiles archives;
  private final VaultFiles vaults;
  private final CurateFiles files;
  private final Museum museum;
  private final HallRenderer renderer;
  private final PlayerStore players;
  private final SerialWriter writer;
  private final Journal journal;
  private final JournalRecovery recovery;
  private final ArchiveHold hold;
  private final AuditLog audit;
  private final Announcer announcer;
  private final AdminAlerts alerts;
  private final CrashPoints crash;
  private final Clock clock;
  private final Executor serverThread;
  private final Function<PlayerReturn, CompletableFuture<Void>> returns;
  private final AtomicLong counter = new AtomicLong();

  /** Curators being asked for a reason, and what for. */
  private final Map<PlayerId, Prompt> prompts = new ConcurrentHashMap<>();

  /** Each curator's transactions, one at a time in the order asked for. */
  private final Map<PlayerId, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();

  private com.petepeterepeat.seasons.core.port.@Nullable ScheduledTask purging;
  private com.petepeterepeat.seasons.core.port.@Nullable ScheduledTask expiring;

  /**
   * An item handed back to its owner's Returned page under a reference, as {@code Vault#returnItem}
   * does.
   */
  public record PlayerReturn(PlayerId owner, NeutralItem item, String why, String ref) {}

  /**
   * @param returns puts an item on its owner's Returned page, the vault's way
   */
  public Curation(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      ArchiveFiles archives,
      VaultFiles vaults,
      CurateFiles files,
      Museum museum,
      HallRenderer renderer,
      PlayerStore players,
      SerialWriter writer,
      Journal journal,
      JournalRecovery recovery,
      ArchiveHold hold,
      AuditLog audit,
      Announcer announcer,
      AdminAlerts alerts,
      CrashPoints crash,
      Clock clock,
      Function<PlayerReturn, CompletableFuture<Void>> returns) {
    this.platform = platform;
    this.config = config;
    this.archives = archives;
    this.vaults = vaults;
    this.files = files;
    this.museum = museum;
    this.renderer = renderer;
    this.players = players;
    this.writer = writer;
    this.journal = journal;
    this.recovery = recovery;
    this.hold = hold;
    this.audit = audit;
    this.announcer = announcer;
    this.alerts = alerts;
    this.crash = crash;
    this.clock = clock;
    this.returns = returns;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /** Empties the recycle bin of what is too old, now and every hour, and times out questions. */
  public void start() {
    purge();
    purging = platform.scheduler().runGlobalRepeating(PURGE, this::purge);
    expiring = platform.scheduler().runGlobalRepeating(EXPIRE, this::expire);
  }

  public void stop() {
    if (purging != null) {
      purging.cancel();
      purging = null;
    }
    if (expiring != null) {
      expiring.cancel();
      expiring = null;
    }
  }

  /** {@code curate <player> <season>} and {@code curate undo <id>}, under {@code /hof}. */
  public CommandNode command() {
    return CommandNode.literal("curate")
        .permission(PERMISSION_VIEW)
        .then(
            CommandNode.literal(UNDO)
                .permission(PERMISSION_DESTROY)
                .then(CommandNode.argument("id", ArgumentType.word()).executes(this::undo)))
        .then(
            CommandNode.argument("player", ArgumentType.word())
                .then(
                    CommandNode.argument("season", ArgumentType.integer(1, Integer.MAX_VALUE))
                        .executes(this::open)))
        .build();
  }

  // The view

  void open(CommandSource source, Arguments arguments) {
    Messages messages = messages();
    if (source.player().isEmpty()) {
      source.reply(messages.get("curate.player-only"));
      return;
    }
    String name = arguments.string("player");
    int season = arguments.integer("season");
    Optional<PlayerId> owner = player(name);
    if (owner.isEmpty()) {
      source.reply(messages.get("hof.unknown-player", "name", name));
      return;
    }
    show(source.player().get(), owner.get(), season, Optional.of(source));
  }

  /**
   * Opens the curator view of one archive, as sneak-clicking a cell's archive anchor or figure does
   * for a curator.
   */
  @Override
  public void openFor(PlayerId viewer, PlayerId owner, int season) {
    show(viewer, owner, season, Optional.empty());
  }

  /** Whether a player may open the curator view. */
  @Override
  public boolean mayView(PlayerId player) {
    return platform.permissions().has(player, PERMISSION_VIEW);
  }

  private void show(PlayerId viewer, PlayerId owner, int season, Optional<CommandSource> source) {
    var _ =
        writer
            .submit(() -> archives.read(owner, season))
            .whenCompleteAsync(
                (found, failure) -> {
                  if (failure != null) {
                    LOG.error("Can't read an archive for the curator view", failure);
                  }
                  Optional<ArchiveRecord> read = failure == null ? found : Optional.empty();
                  if (platform.players().find(viewer).isEmpty()) {
                    return;
                  }
                  if (read.isEmpty()) {
                    tell(
                        source,
                        messages()
                            .get(
                                "curate.no-archive",
                                "name",
                                knownName(owner),
                                "season",
                                Integer.toString(season)));
                    return;
                  }
                  ArchiveRecord record = read.get();
                  tell(
                      source,
                      messages()
                          .get(
                              "curate.opened",
                              "name",
                              record.name(),
                              "season",
                              Integer.toString(season)));
                  view(viewer, record);
                },
                serverThread);
  }

  private void view(PlayerId viewer, ArchiveRecord record) {
    Messages messages = messages();
    List<List<Optional<NeutralItem>>> pages = ArchivePages.pages(record);
    List<List<Optional<ArchiveSlot>>> slots = ArchivePages.slots(record);
    List<Message> labels = new ArrayList<>();
    for (int i = 0; i < pages.size(); i++) {
      labels.add(
          messages.get(
              "view.page",
              "page",
              Integer.toString(i + 1),
              "pages",
              Integer.toString(pages.size())));
    }
    PagedView view =
        new PagedView(
            messages.get(
                "curate.title", "name", record.name(), "season", Integer.toString(record.season())),
            pages,
            messages.get("view.previous"),
            messages.get("view.next"),
            labels);
    platform
        .menus()
        .openWithdraw(
            viewer,
            view,
            0,
            (page, slot) -> {
              if (page < slots.size() && slot < slots.get(page).size()) {
                slots.get(page).get(slot).ifPresent(at -> picked(viewer, record, at));
              }
            });
  }

  /** What a curator picked: one item of one archive, as the view showed it. */
  private record Pick(
      PlayerId curator,
      PlayerId owner,
      String ownerName,
      int season,
      ArchiveSlot slot,
      NeutralItem item,
      String itemHash) {}

  /** A curator asked for the reason for one action, until a time. */
  private record Prompt(Pick pick, Action action, Optional<String> anchor, Instant expires) {}

  private void picked(PlayerId curator, ArchiveRecord record, ArchiveSlot slot) {
    Optional<NeutralItem> item = record.item(slot);
    if (item.isEmpty()) {
      return;
    }
    Pick pick =
        new Pick(
            curator, record.player(), record.name(), record.season(), slot, item.get(), hash(item));
    List<Action> allowed = allowed(curator);
    Messages messages = messages();
    if (allowed.isEmpty()) {
      platform.players().sendMessage(curator, messages.get("curate.view-only"));
      return;
    }
    List<Menus.Choice> choices = new ArrayList<>();
    for (Action action : allowed) {
      choices.add(
          new Menus.Choice(
              action.icon, messages.get("curate.action." + action.word(), "name", record.name())));
    }
    choices.add(new Menus.Choice("minecraft:barrier", messages.get("curate.action.cancel")));
    platform
        .menus()
        .openChoice(
            curator,
            messages.get("curate.actions", "item", describe(item.get())),
            choices,
            index -> chose(pick, allowed, index));
  }

  private List<Action> allowed(PlayerId curator) {
    List<Action> allowed = new ArrayList<>();
    for (Action action : Action.values()) {
      if (platform.permissions().has(curator, action.permission)) {
        allowed.add(action);
      }
    }
    return allowed;
  }

  private void chose(Pick pick, List<Action> allowed, int index) {
    if (index >= allowed.size()) {
      // Cancel: back to the view, read again.
      openFor(pick.curator(), pick.owner(), pick.season());
      return;
    }
    Action action = allowed.get(index);
    if (action != Action.MUSEUM) {
      ask(pick, action, Optional.empty());
      return;
    }
    var _ =
        museum
            .freePlinths()
            .whenComplete((free, failure) -> {
              if (failure != null || free.isEmpty()) {
                if (failure != null) {
                  LOG.error("Can't list the free museum plinths", failure);
                }
                platform.menus().close(pick.curator());
                platform.players().sendMessage(pick.curator(), messages().get("curate.no-plinth"));
                return;
              }
              List<Menus.Choice> choices = new ArrayList<>();
              for (String anchor : free) {
                choices.add(
                    new Menus.Choice(
                        "minecraft:name_tag", new Message("<anchor>", Map.of("anchor", anchor))));
              }
              platform
                  .menus()
                  .openChoice(
                      pick.curator(),
                      messages().get("curate.plinths"),
                      choices,
                      i -> ask(pick, Action.MUSEUM, Optional.of(free.get(i))));
            });
  }

  /** Asks for the reason, or runs the action at once when none is required. */
  private void ask(Pick pick, Action action, Optional<String> anchor) {
    PlayerId curator = pick.curator();
    platform.menus().close(curator);
    Prompt prompt = new Prompt(pick, action, anchor, clock.instant().plus(PROMPT));
    if (!config.get().config().curate().requireReason()) {
      queue(curator, () -> run(prompt, messages().text("curate.no-reason")));
      return;
    }
    prompts.put(curator, prompt);
    platform
        .players()
        .sendMessage(
            curator,
            messages()
                .get(
                    "curate.prompt",
                    "verb",
                    messages().text("curate.verb." + action.word()),
                    "item",
                    describe(pick.item())));
  }

  /** Forgets every question asked {@link #PROMPT} ago that has no answer yet. */
  private void expire() {
    Instant now = clock.instant();
    for (Map.Entry<PlayerId, Prompt> entry : List.copyOf(prompts.entrySet())) {
      Prompt prompt = entry.getValue();
      if (!now.isBefore(prompt.expires()) && prompts.remove(entry.getKey(), prompt)) {
        say(entry.getKey(), "curate.timed-out", "item", describe(prompt.pick().item()));
      }
    }
  }

  /** A line in chat: the reason, if this curator is being asked for one. Any thread. */
  @Override
  public boolean typed(PlayerId player, String text) {
    Prompt prompt = prompts.get(player);
    if (prompt == null || !prompts.remove(player, prompt)) {
      return false;
    }
    serverThread.execute(() -> answered(prompt, text.strip()));
    return true;
  }

  private void answered(Prompt prompt, String reason) {
    PlayerId curator = prompt.pick().curator();
    String item = describe(prompt.pick().item());
    if (reason.equalsIgnoreCase("cancel")) {
      platform.players().sendMessage(curator, messages().get("curate.cancelled", "item", item));
      return;
    }
    if (reason.isEmpty()) {
      prompts.put(curator, prompt);
      platform.players().sendMessage(curator, messages().get("curate.reason-required"));
      return;
    }
    queue(curator, () -> run(prompt, reason));
  }

  /**
   * Whether a curation a crash left open names a player as the archive's owner, or an undo puts an
   * item back in their archive. It is journalled under the curator, so the owner's own open
   * operations don't show it; {@code /hof import undo} asks this before it touches the record.
   */
  public boolean curating(PlayerId owner) {
    String who = owner.toString();
    return JournalRecovery.openOperations(journal.entries()).stream()
        .filter(op -> op.operation() == Operation.CURATE)
        .map(op -> op.steps().getFirst().payloadHash().split("/", -1))
        .anyMatch(payload -> payload.length == 7 && payload[2].equals(who));
  }

  /** Forgets a curator's question when they leave. */
  public void left(PlayerId player) {
    prompts.remove(player);
  }

  /** One transaction at a time per curator, in the order asked for. */
  private void queue(PlayerId curator, Supplier<CompletableFuture<Void>> transaction) {
    queues.compute(
        curator,
        (who, tail) ->
            (tail == null ? CompletableFuture.<Void>completedFuture(null) : tail)
                .thenCompose(done -> transaction.get())
                .exceptionally(failure -> {
                  LOG.error("A curation by {} failed", curator, failure);
                  return null;
                }));
  }

  // The transaction

  /** How a delivery went. */
  private enum Delivery {
    DELIVERED,
    FULL,
    PLINTH_TAKEN,
    CHANGED,
    BUSY
  }

  /** Where a transaction got to, so a failure part-way knows what to put back. */
  private static final class Progress {
    volatile boolean removed;
    volatile boolean delivered;
    volatile boolean finished;
  }

  private CompletableFuture<Void> run(Prompt prompt, String reason) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    serverThread.execute(() -> {
      try {
        var _ = start(prompt, reason).whenComplete((r, f) -> done.complete(null));
      } catch (RuntimeException e) {
        LOG.error("A curation stopped", e);
        done.complete(null);
      }
    });
    return done;
  }

  /** On the server thread: holds everyone involved, then reads, journals, removes, delivers. */
  private CompletableFuture<Void> start(Prompt prompt, String reason) {
    Pick pick = prompt.pick();
    PlayerId curator = pick.curator();
    Action action = prompt.action();
    if (platform.players().find(curator).isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    Set<PlayerId> involved = new LinkedHashSet<>(List.of(pick.owner(), curator));
    if (!holdAll(involved) || involved.stream().anyMatch(p -> !recovery.open(p).isEmpty())) {
      releaseAll(involved);
      say(curator, "curate.busy", "name", pick.ownerName());
      return CompletableFuture.completedFuture(null);
    }
    String curatorName = knownName(curator);
    String id = Long.toString(clock.millis(), 36) + "-" + counter.incrementAndGet();
    Progress progress = new Progress();
    CompletableFuture<Delivery> chain =
        writer
            .submit(() -> archives.read(pick.owner(), pick.season()))
            .thenComposeAsync(
                read -> {
                  if (read.isEmpty()
                      || !hash(read.get().item(pick.slot())).equals(pick.itemHash())) {
                    return CompletableFuture.completedFuture(Delivery.CHANGED);
                  }
                  ArchiveRecord record = read.get();
                  String before = NONE;
                  if (action == Action.TAKE) {
                    platform.players().saveData(curator);
                    before = ArchiveHash.of(platform.inventories().snapshot(curator), false);
                  }
                  CurateFiles.Kept kept =
                      new CurateFiles.Kept(
                          id,
                          pick.owner(),
                          record.name(),
                          pick.season(),
                          pick.slot(),
                          curatorName,
                          reason,
                          clock.instant(),
                          pick.item());
                  String payload =
                      String.join(
                          "/",
                          id,
                          action.word(),
                          pick.owner().toString(),
                          pick.slot().key(),
                          pick.itemHash(),
                          prompt.anchor().orElse(NONE),
                          before);
                  return writer
                      .submit(() -> {
                        files.keep(kept);
                        return Boolean.TRUE;
                      })
                      .thenCompose(k ->
                          journal.append(
                              JournalKind.CURATE_STARTED, curator, pick.season(), payload))
                      .thenApplyAsync(
                          entry -> {
                            crash.reached(CrashPoint.CURATE_JOURNALLED, curator);
                            return entry;
                          },
                          serverThread)
                      .thenCompose(entry ->
                          writer.submit(() -> {
                            archives.rewrite(record.with(pick.slot(), Optional.empty()));
                            progress.removed = true;
                            return Boolean.TRUE;
                          }))
                      .thenComposeAsync(
                          removed -> {
                            crash.reached(CrashPoint.CURATE_REMOVED, curator);
                            return deliver(prompt, kept, reason);
                          },
                          serverThread)
                      .thenComposeAsync(
                          delivery -> {
                            if (delivery == Delivery.DELIVERED) {
                              progress.delivered = true;
                              crash.reached(CrashPoint.CURATE_DELIVERED, curator);
                              return CompletableFuture.completedFuture(delivery);
                            }
                            return writer.submit(() -> {
                              archives.rewrite(record);
                              progress.removed = false;
                              return delivery;
                            });
                          },
                          serverThread)
                      .thenCompose(delivery ->
                          writer
                              .submit(() -> {
                                files.release(id);
                                return Boolean.TRUE;
                              })
                              .thenCompose(r ->
                                  journal.append(
                                      JournalKind.CURATE_DONE, curator, pick.season(), id))
                              .thenApply(entry -> {
                                progress.finished = true;
                                return delivery;
                              }));
                },
                serverThread);
    return chain
        .handle((delivery, failure) -> {
          if (failure != null) {
            LOG.error("Curation {} of {} stopped", id, pick.ownerName(), failure);
          }
          return failure == null ? delivery : null;
        })
        .thenCompose(delivery ->
            delivery != null || progress.finished
                ? CompletableFuture.completedFuture(delivery)
                : settleFailed(pick, id, progress).thenApply(v -> (Delivery) null))
        .thenAcceptAsync(
            delivery -> {
              releaseAll(involved);
              report(prompt, reason, id, curatorName, delivery);
            },
            serverThread);
  }

  /**
   * A transaction that stopped while the server runs on: the item goes back in the archive now, if
   * it left and wasn't delivered, and the curation is closed. Left to recovery, a take would be
   * judged by an inventory the curator has changed since.
   */
  private CompletableFuture<Void> settleFailed(Pick pick, String id, Progress progress) {
    return writer
        .submit(() -> {
          if (progress.removed && !progress.delivered) {
            Optional<CurateFiles.Kept> kept = files.kept(id);
            Optional<ArchiveRecord> record = archives.read(pick.owner(), pick.season());
            if (kept.isPresent()
                && record.isPresent()
                && record.get().item(pick.slot()).isEmpty()) {
              archives.rewrite(record.get().with(pick.slot(), Optional.of(kept.get().item())));
            }
          }
          files.release(id);
          return Boolean.TRUE;
        })
        .thenCompose(
            put -> journal.append(JournalKind.CURATE_DONE, pick.curator(), pick.season(), id))
        .<Void>thenApply(entry -> null)
        .exceptionally(failure -> {
          LOG.error("Can't settle curation {}; recovery will try at the next restart", id, failure);
          return null;
        });
  }

  /** Delivers the item where the action sends it. Completes on the server thread. */
  private CompletableFuture<Delivery> deliver(Prompt prompt, CurateFiles.Kept kept, String reason) {
    Pick pick = prompt.pick();
    return switch (prompt.action()) {
      case RETURN ->
          returns
              .apply(
                  new PlayerReturn(
                      pick.owner(),
                      pick.item(),
                      "returned by " + kept.curator() + ": " + reason,
                      ref(kept.id())))
              .thenApplyAsync(v -> Delivery.DELIVERED, serverThread);
      case TAKE -> {
        PlayerId curator = pick.curator();
        if (platform.players().find(curator).isEmpty()
            || !platform.inventories().give(curator, pick.item())) {
          yield CompletableFuture.completedFuture(Delivery.FULL);
        }
        platform.players().saveData(curator);
        yield CompletableFuture.completedFuture(Delivery.DELIVERED);
      }
      case MUSEUM ->
          museum
              .placeCurated(
                  prompt.anchor().orElseThrow(), pick.item(), pick.season(), kept.name(), kept.id())
              .thenApply(placed -> placed ? Delivery.DELIVERED : Delivery.PLINTH_TAKEN);
      case DESTROY ->
          writer
              .submit(() -> {
                files.recycle(kept);
                return Delivery.DELIVERED;
              })
              .thenApplyAsync(d -> d, serverThread);
    };
  }

  /** What the curator, the owner, the audit log and the webhooks hear. Server thread. */
  private void report(
      Prompt prompt, String reason, String id, String curatorName, @Nullable Delivery delivery) {
    Pick pick = prompt.pick();
    Action action = prompt.action();
    PlayerId curator = pick.curator();
    String item = describe(pick.item());
    String season = Integer.toString(pick.season());
    String anchor = prompt.anchor().orElse(NONE);
    if (delivery == null) {
      say(curator, "curate.failed", "item", item);
      return;
    }
    String destination =
        messages()
            .text(
                "curate.destination." + action.word(),
                "anchor",
                anchor,
                "name",
                pick.ownerName(),
                "curator",
                curatorName,
                "id",
                id);
    String details =
        "Season "
            + season
            + ", "
            + pick.slot().key()
            + ", "
            + item
            + " to "
            + destination
            + "; reason: "
            + reason
            + "; item: "
            + snapshot(pick.item());
    switch (delivery) {
      case CHANGED -> say(curator, "curate.changed");
      case BUSY -> say(curator, "curate.busy", "name", pick.ownerName());
      case FULL -> {
        audit.record(curatorName, "curate " + action.word(), pick.ownerName(), "refused", details);
        say(curator, "curate.full", "item", item);
      }
      case PLINTH_TAKEN -> {
        audit.record(curatorName, "curate " + action.word(), pick.ownerName(), "refused", details);
        say(curator, "curate.plinth-taken", "anchor", anchor, "item", item);
      }
      case DELIVERED -> {
        audit.record(curatorName, "curate " + action.word(), pick.ownerName(), "ok", details);
        say(
            curator,
            "curate.done." + action.word(),
            "item",
            item,
            "anchor",
            anchor,
            "name",
            pick.ownerName(),
            "id",
            id);
        delivered(pick, action, reason, curatorName, destination);
      }
    }
  }

  /** Once an item has moved: the owner, both webhooks, and the figure drawn again. */
  private void delivered(
      Pick pick, Action action, String reason, String curatorName, String destination) {
    String item = describe(pick.item());
    String season = Integer.toString(pick.season());
    notice(
        pick.owner(),
        "curate.notice." + action.word(),
        "item",
        item,
        "season",
        season,
        "reason",
        reason);
    alerts.alert(
        "alerts.curate",
        "curator",
        curatorName,
        "verb",
        messages().text("curate.alert-verb." + action.word()),
        "item",
        item,
        "name",
        pick.ownerName(),
        "season",
        season,
        "slot",
        pick.slot().key(),
        "destination",
        destination,
        "reason",
        reason);
    announcer.discord(
        "curate.discord." + action.word(),
        "item",
        item,
        "name",
        pick.ownerName(),
        "season",
        season);
    redraw(pick.owner(), pick.season());
  }

  // Undo

  void undo(CommandSource source, Arguments arguments) {
    String id = arguments.string("id");
    String actor =
        source.player().map(this::knownName).orElse(messages().text("config.console-name"));
    var _ =
        writer
            .submit(() -> recycled(id))
            .thenComposeAsync(
                found -> {
                  if (found.isEmpty()) {
                    source.reply(messages().get("curate.undo.unknown", "id", id));
                    return CompletableFuture.completedFuture(null);
                  }
                  return restore(source, actor, found.get());
                },
                serverThread)
            .exceptionally(failure -> {
              LOG.error("Undoing curation {} failed", id, failure);
              source.reply(
                  messages()
                      .get("curate.undo.failed", "error", String.valueOf(failure.getMessage())));
              return null;
            });
  }

  /** What the recycle bin holds under an id, unless it has been there too long. Writer thread. */
  private Optional<CurateFiles.Kept> recycled(String id) throws IOException {
    Optional<CurateFiles.Kept> kept = files.recycled(id);
    if (kept.isPresent() && kept.get().at().plus(KEEP).isBefore(clock.instant())) {
      purge(kept.get());
      return Optional.empty();
    }
    return kept;
  }

  private CompletableFuture<Void> restore(
      CommandSource source, String actor, CurateFiles.Kept kept) {
    PlayerId owner = kept.owner();
    String item = describe(kept.item());
    String season = Integer.toString(kept.season());
    if (!hold.hold(owner) || !recovery.open(owner).isEmpty()) {
      hold.release(owner);
      source.reply(messages().get("curate.busy", "name", kept.name()));
      return CompletableFuture.completedFuture(null);
    }
    String instance = kept.id() + "-" + UNDO;
    String payload =
        String.join(
            "/",
            instance,
            UNDO,
            owner.toString(),
            kept.slot().key(),
            hash(Optional.of(kept.item())),
            NONE,
            kept.id());
    return writer
        .submit(() -> archives.read(owner, kept.season()))
        .thenComposeAsync(
            read -> {
              if (read.isEmpty()) {
                source.reply(
                    messages()
                        .get(
                            "curate.undo.no-archive",
                            "name",
                            kept.name(),
                            "season",
                            season,
                            "item",
                            item));
                return CompletableFuture.completedFuture(Boolean.FALSE);
              }
              if (read.get().item(kept.slot()).isPresent()) {
                source.reply(
                    messages()
                        .get(
                            "curate.undo.slot-taken",
                            "name",
                            kept.name(),
                            "season",
                            season,
                            "item",
                            item));
                return CompletableFuture.completedFuture(Boolean.FALSE);
              }
              ArchiveRecord record = read.get();
              return journal
                  .append(JournalKind.CURATE_STARTED, owner, kept.season(), payload)
                  .thenCompose(entry ->
                      writer.submit(() -> {
                        archives.rewrite(record.with(kept.slot(), Optional.of(kept.item())));
                        files.unrecycle(kept.id());
                        return Boolean.TRUE;
                      }))
                  .thenCompose(put ->
                      journal
                          .append(JournalKind.CURATE_DONE, owner, kept.season(), instance)
                          .thenApply(entry -> put));
            },
            serverThread)
        .whenCompleteAsync(
            (restored, failure) -> {
              hold.release(owner);
              if (failure != null || !Boolean.TRUE.equals(restored)) {
                if (failure != null) {
                  LOG.error("Undoing curation {} stopped", kept.id(), failure);
                  source.reply(
                      messages()
                          .get(
                              "curate.undo.failed", "error", String.valueOf(failure.getMessage())));
                }
                return;
              }
              audit.record(
                  actor,
                  "curate undo",
                  kept.name(),
                  "ok",
                  "Season "
                      + season
                      + ", "
                      + kept.slot().key()
                      + ", "
                      + item
                      + " back from the recycle bin ("
                      + kept.id()
                      + ")");
              source.reply(
                  messages()
                      .get(
                          "curate.undo.done", "item", item, "name", kept.name(), "season", season));
              notice(owner, "curate.notice.undo", "item", item, "season", season);
              alerts.alert(
                  "alerts.curate-undo",
                  "curator",
                  actor,
                  "item",
                  item,
                  "name",
                  kept.name(),
                  "season",
                  season,
                  "slot",
                  kept.slot().key(),
                  "id",
                  kept.id());
              announcer.discord(
                  "curate.discord.undo", "item", item, "name", kept.name(), "season", season);
              redraw(owner, kept.season());
            },
            serverThread)
        .thenApply(restored -> null);
  }

  // The recycle bin

  private void purge() {
    Instant before = clock.instant().minus(KEEP);
    var _ =
        writer
            .submit(() -> files.purge(before))
            .whenComplete((gone, failure) -> {
              if (failure != null) {
                LOG.error("Can't empty the recycle bin", failure);
                return;
              }
              gone.forEach(this::purged);
            });
  }

  /** Lets one item go from the recycle bin for good. Writer thread. */
  private void purge(CurateFiles.Kept kept) throws IOException {
    files.unrecycle(kept.id());
    purged(kept);
  }

  private void purged(CurateFiles.Kept kept) {
    audit.record(
        AuditLog.SEASONS,
        "curate purge",
        kept.name(),
        "ok",
        "Season "
            + kept.season()
            + ", "
            + kept.slot().key()
            + ", "
            + describe(kept.item())
            + " left the recycle bin after 30 days ("
            + kept.id()
            + ")");
  }

  // Recovery

  /** The handler that settles a curation a crash left open. */
  public RecoveryHandler recovery() {
    return this::recover;
  }

  private CompletableFuture<RecoveryOutcome> recover(OpenOperation operation) {
    String[] payload = operation.steps().getFirst().payloadHash().split("/", -1);
    if (payload.length != 7 || operation.player().isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("a curation entry with a damaged payload"));
    }
    String id = payload[0];
    String word = payload[1];
    PlayerId owner;
    Optional<ArchiveSlot> slot = ArchiveSlot.parse(payload[3]);
    try {
      owner = new PlayerId(UUID.fromString(payload[2]));
    } catch (IllegalArgumentException e) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("a curation entry with a damaged payload"));
    }
    if (slot.isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("a curation entry with a damaged payload"));
    }
    String itemHash = payload[4];
    String anchor = payload[5];
    String before = payload[6];
    PlayerId curator = operation.player().get();
    int season = operation.season();
    if (word.equals(UNDO)) {
      return recoverUndo(operation, owner, season, slot.get(), itemHash, before);
    }
    Optional<Action> action = Action.of(word);
    if (action.isEmpty()) {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed("a curation entry with an unknown action " + word));
    }
    if (action.get() == Action.TAKE && platform.players().find(curator).isEmpty()) {
      return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
    }
    return writer
        .submit(() -> {
          Optional<ArchiveRecord> record = archives.read(owner, season);
          boolean arrived =
              switch (action.get()) {
                case RETURN -> vaults.delivered(owner, ref(id));
                case MUSEUM -> museum.holds(anchor, id);
                case DESTROY -> files.recycled(id).isPresent();
                case TAKE -> false;
              };
          return new Found(record, files.kept(id), arrived);
        })
        .thenComposeAsync(
            found -> {
              boolean inArchive =
                  found.record().isPresent()
                      && hash(found.record().get().item(slot.get())).equals(itemHash);
              boolean arrived = found.arrived();
              if (action.get() == Action.TAKE) {
                if (platform.players().find(curator).isEmpty()) {
                  return CompletableFuture.completedFuture(RecoveryOutcome.waitForPlayer());
                }
                arrived =
                    !inArchive
                        && !ArchiveHash.of(platform.inventories().snapshot(curator), false)
                            .equals(before);
              }
              return settle(
                  operation,
                  id,
                  action.get(),
                  owner,
                  season,
                  slot.get(),
                  found,
                  inArchive,
                  arrived);
            },
            serverThread);
  }

  private record Found(
      Optional<ArchiveRecord> record, Optional<CurateFiles.Kept> kept, boolean arrived) {}

  /** Settles one way or the other, then audits and closes the curation. Server thread. */
  private CompletableFuture<RecoveryOutcome> settle(
      OpenOperation operation,
      String id,
      Action action,
      PlayerId owner,
      int season,
      ArchiveSlot slot,
      Found found,
      boolean inArchive,
      boolean arrived) {
    String what;
    boolean putBack = false;
    if (inArchive) {
      what = "the item never left the archive";
    } else if (arrived) {
      what = "the item had arrived, so the curation stands";
    } else if (found.kept().isPresent() && found.record().isPresent()) {
      what = "the item hadn't arrived, so it is back in the archive";
      putBack = true;
    } else {
      return CompletableFuture.completedFuture(
          RecoveryOutcome.failed(
              "curation " + id + " left neither the archive nor its copy holding the item"));
    }
    boolean back = putBack;
    String name = found.record().map(ArchiveRecord::name).orElse(knownName(owner));
    return writer
        .submit(() -> {
          if (back) {
            archives.rewrite(
                found.record().get().with(slot, Optional.of(found.kept().get().item())));
          }
          files.release(id);
          return Boolean.TRUE;
        })
        .thenCompose(done ->
            journal.append(JournalKind.CURATE_DONE, operation.player().orElseThrow(), season, id))
        .thenApplyAsync(
            entry -> {
              LOG.info("Recovered curation {} of {}: {}", id, name, what);
              audit.record(AuditLog.SEASONS, "curate " + action.word(), name, "recovered", what);
              if (arrived && found.kept().isPresent()) {
                CurateFiles.Kept kept = found.kept().get();
                notice(
                    owner,
                    "curate.notice." + action.word(),
                    "item",
                    describe(kept.item()),
                    "season",
                    Integer.toString(season),
                    "reason",
                    kept.reason());
              }
              redraw(owner, season);
              return RecoveryOutcome.finished();
            },
            serverThread);
  }

  private CompletableFuture<RecoveryOutcome> recoverUndo(
      OpenOperation operation,
      PlayerId owner,
      int season,
      ArchiveSlot slot,
      String itemHash,
      String id) {
    return writer
        .submit(() -> {
          Optional<ArchiveRecord> record = archives.read(owner, season);
          boolean restored = record.isPresent() && hash(record.get().item(slot)).equals(itemHash);
          if (restored) {
            files.unrecycle(id);
          }
          return restored;
        })
        .thenCompose(restored ->
            journal
                .append(
                    JournalKind.CURATE_DONE,
                    operation.player().orElseThrow(),
                    season,
                    id + "-" + UNDO)
                .thenApply(entry -> restored))
        .thenApplyAsync(
            restored -> {
              audit.record(
                  AuditLog.SEASONS,
                  "curate undo",
                  knownName(owner),
                  "recovered",
                  restored
                      ? "the item is back in the archive"
                      : "the item stays in the recycle bin");
              redraw(owner, season);
              return RecoveryOutcome.finished();
            },
            serverThread);
  }

  // Helpers

  private boolean holdAll(Set<PlayerId> players) {
    List<PlayerId> taken = new ArrayList<>();
    for (PlayerId player : players) {
      if (!hold.hold(player)) {
        taken.forEach(hold::release);
        return false;
      }
      taken.add(player);
    }
    return true;
  }

  private void releaseAll(Set<PlayerId> players) {
    players.forEach(hold::release);
  }

  private void redraw(PlayerId owner, int season) {
    var _ =
        renderer
            .render(owner, season)
            .exceptionally(failure -> {
              LOG.warn("Can't draw {}'s Season {} figure again", owner, season, failure);
              return null;
            });
  }

  /** Tells a player now, or keeps it for their next login. */
  private void notice(PlayerId player, String key, String... placeholders) {
    if (platform.players().find(player).isPresent()) {
      platform.players().sendMessage(player, messages().get(key, placeholders));
      return;
    }
    players
        .find(player)
        .ifPresent(record -> {
          List<PlayerRecord.Notice> notices = new ArrayList<>(record.notices());
          notices.add(new PlayerRecord.Notice(key, List.of(placeholders)));
          var _ =
              players
                  .putNow(record.withNotices(notices))
                  .exceptionally(failure -> {
                    LOG.error("Can't save a notice for {}", player, failure);
                    return null;
                  });
        });
  }

  private void say(PlayerId player, String key, String... placeholders) {
    platform.players().sendMessage(player, messages().get(key, placeholders));
  }

  private static void tell(Optional<CommandSource> source, Message message) {
    if (source.isPresent()) {
      source.get().reply(message);
    }
  }

  private Optional<PlayerId> player(String name) {
    Optional<PlayerRef> online = platform.players().findByName(name);
    if (online.isPresent()) {
      return Optional.of(online.get().id());
    }
    return players.all().stream()
        .filter(r -> r.lastName().equalsIgnoreCase(name))
        .map(PlayerRecord::id)
        .findFirst();
  }

  private String knownName(PlayerId player) {
    return platform
        .players()
        .find(player)
        .map(PlayerRef::name)
        .or(() -> players.find(player).map(PlayerRecord::lastName))
        .or(() -> platform.players().knownName(player))
        .orElse(player.toString());
  }

  private static String ref(String id) {
    return "curate-" + id;
  }

  /** The canonical hash of what a slot holds, so copies compare by content, not by bytes. */
  static String hash(Optional<NeutralItem> item) {
    return ArchiveHash.ofLoose(List.of(item));
  }

  /** Such as {@code 64 × minecraft:dirt}. */
  static String describe(NeutralItem item) {
    try {
      Map<String, Object> nbt = Nbt.read(item.nbt());
      String id = nbt.get("id") instanceof String s ? s : "?";
      int count = nbt.get("count") instanceof Number n ? n.intValue() : 1;
      return count + " × " + id;
    } catch (IOException e) {
      return "?";
    }
  }

  /** The item's vanilla data, for the audit entry. */
  private static String snapshot(NeutralItem item) {
    return Base64.getEncoder().encodeToString(item.nbt());
  }

  private Messages messages() {
    return config.get().messages();
  }
}
