// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.pet;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.ArgumentType;
import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigSnapshot;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.player.PlayerRecord;
import com.petepeterepeat.seasons.core.player.PlayerStore;
import com.petepeterepeat.seasons.core.port.PetEvents;
import com.petepeterepeat.seasons.core.port.PetInfo;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.Phase;
import com.petepeterepeat.seasons.core.season.SeasonState;
import com.petepeterepeat.seasons.core.store.Journal;
import com.petepeterepeat.seasons.core.store.JournalKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registered pets (Pets in the spec): {@code /pet add}, {@code list} and {@code remove}; their last
 * known positions as their chunks unload; deaths reported and dropped; and travel to their owner
 * when the owner enters a later season, at launch or at their first login after it. Registrations
 * clear after travel, so each season starts with a fresh list. Pets are never archived.
 */
public final class Pets implements PetEvents {
  /** Permission for {@code /pet}. */
  public static final String PERMISSION = "seasons.pet";

  /** How far away a player can register a pet from, in blocks. */
  public static final int REACH = 5;

  private static final Logger LOG = LoggerFactory.getLogger(Pets.class);

  /** How one pet's travel came out. */
  enum Travel {
    CAME,
    CHEST,
    MISSING,
    NOT_THEIRS,
    /** The owner left part-way: the registration stays for their next login. */
    LATER
  }

  private final Platform platform;
  private final Supplier<ConfigSnapshot> config;
  private final PlayerStore store;
  private final AuditLog audit;
  private final Optional<Journal> journal;
  private final Executor serverThread;
  private final Set<PlayerId> travelling = ConcurrentHashMap.newKeySet();
  private Supplier<Optional<SeasonState>> state = Optional::empty;
  private Function<Intent, Optional<Decision>> zones = intent -> Optional.empty();

  public Pets(
      Platform platform,
      Supplier<ConfigSnapshot> config,
      PlayerStore store,
      AuditLog audit,
      Optional<Journal> journal) {
    this.platform = platform;
    this.config = config;
    this.store = store;
    this.audit = audit;
    this.journal = journal;
    this.serverThread = platform.scheduler()::runGlobal;
  }

  /**
   * Where the season state comes from, once the lifecycle exists, and the zones' say on reaching an
   * animal, so that none is registered from across a blocked zone's edge.
   */
  public void bind(
      Supplier<Optional<SeasonState>> state, Function<Intent, Optional<Decision>> zones) {
    this.state = state;
    this.zones = zones;
  }

  /** {@code /pet add}, {@code /pet list} and {@code /pet remove <name>}. */
  public CommandNode command() {
    return CommandNode.literal("pet")
        .permission(PERMISSION)
        .then(CommandNode.literal("add").executes(this::add))
        .then(CommandNode.literal("list").executes(this::list))
        .then(
            CommandNode.literal("remove")
                .then(CommandNode.argument("name", ArgumentType.greedy()).executes(this::remove)))
        .build();
  }

  void add(CommandSource source, Arguments arguments) {
    Optional<PlayerId> asker = player(source);
    if (asker.isEmpty()) {
      return;
    }
    PlayerId owner = asker.get();
    Messages messages = messages();
    if (!config.get().config().pets().enabled()) {
      source.reply(messages.get("pet.disabled"));
      return;
    }
    Optional<SeasonState> current = state.get();
    if (current.isEmpty() || !isPlaying(current.get().phase())) {
      source.reply(messages.get("pet.closed"));
      return;
    }
    Optional<PetInfo> found =
        platform.players().targetEntity(owner, REACH).flatMap(platform.entities()::pet);
    if (found.isEmpty()) {
      source.reply(messages.get("pet.look", "reach", Integer.toString(REACH)));
      return;
    }
    PetInfo pet = found.get();
    String type = typeName(pet.type());
    if (!pet.tameable()) {
      source.reply(messages.get("pet.not-tameable", "a", article(type), "type", type));
      return;
    }
    if (!pet.tamed()) {
      source.reply(messages.get("pet.wild", "type", type));
      return;
    }
    String called = pet.customName().filter(n -> !n.isBlank()).orElse(title(type));
    if (!pet.owner().equals(Optional.of(owner))) {
      source.reply(messages.get("pet.not-yours", "name", called));
      return;
    }
    // A pet in a locked zone stays there until Homecoming, saddle and armour included.
    Optional<Decision> zone =
        zones.apply(new Intent.UseEntity(owner, pet.position(), "minecraft:" + pet.type()));
    if (zone.isPresent() && zone.get() instanceof Decision.Deny deny) {
      source.reply(deny.message().orElseGet(() -> messages.get("pet.locked", "name", called)));
      return;
    }
    PlayerRecord record = store.getOrCreate(owner, name(owner));
    Optional<PlayerRecord.Pet> already =
        record.pets().stream().filter(p -> p.entity().equals(pet.id())).findFirst();
    if (already.isPresent()) {
      source.reply(messages.get("pet.already", "name", already.get().name()));
      return;
    }
    if (pet.carryingChest()) {
      source.reply(messages.get("pet.chested", "name", called));
      return;
    }
    int max = config.get().config().pets().maxPerPlayer();
    if (record.pets().size() >= max) {
      source.reply(messages.get("pet.limit", "max", Integer.toString(max)));
      return;
    }
    String name = unique(called, record.pets());
    List<PlayerRecord.Pet> pets = new ArrayList<>(record.pets());
    pets.add(
        new PlayerRecord.Pet(
            pet.id(),
            pet.type(),
            name,
            Optional.of(pet.position()),
            current.get().season(),
            false));
    store.put(record.withPets(pets));
    platform.entities().markPet(pet.id(), Optional.of(owner));
    audit.record(name(owner), "pet-register", name(owner), "ok", name + " (" + pet.type() + ")");
    source.reply(
        messages.get(
            "pet.registered",
            "name",
            name,
            "type",
            type,
            "count",
            Integer.toString(pets.size()),
            "max",
            Integer.toString(max)));
  }

  void list(CommandSource source, Arguments arguments) {
    Optional<PlayerId> asker = player(source);
    if (asker.isEmpty()) {
      return;
    }
    Messages messages = messages();
    List<PlayerRecord.Pet> pets = store.find(asker.get()).map(PlayerRecord::pets).orElse(List.of());
    if (pets.isEmpty()) {
      source.reply(messages.get("pet.none"));
      return;
    }
    source.reply(
        messages.get(
            "pet.list",
            "count",
            Integer.toString(pets.size()),
            "max",
            Integer.toString(config.get().config().pets().maxPerPlayer())));
    for (PlayerRecord.Pet pet : pets) {
      String type = typeName(pet.type());
      source.reply(
          pet.lastSeen()
              .map(at ->
                  messages.get(
                      "pet.line",
                      "name",
                      pet.name(),
                      "type",
                      type,
                      "x",
                      Integer.toString(at.blockX()),
                      "y",
                      Integer.toString(at.blockY()),
                      "z",
                      Integer.toString(at.blockZ()),
                      "dimension",
                      at.dimension()))
              .orElseGet(() -> messages.get("pet.line-unseen", "name", pet.name(), "type", type)));
    }
  }

  void remove(CommandSource source, Arguments arguments) {
    Optional<PlayerId> asker = player(source);
    if (asker.isEmpty()) {
      return;
    }
    String name = arguments.string("name").strip();
    Optional<PlayerRecord> record = store.find(asker.get());
    Optional<PlayerRecord.Pet> pet =
        record.flatMap(
            r -> r.pets().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst());
    if (pet.isEmpty()) {
      source.reply(messages().get("pet.unknown", "name", name));
      return;
    }
    drop(asker.get(), pet.get().entity());
    platform.entities().markPet(pet.get().entity(), Optional.empty());
    audit.record(name(asker.get()), "pet-remove", name(asker.get()), "ok", pet.get().name());
    source.reply(messages().get("pet.removed", "name", pet.get().name()));
  }

  @Override
  public void unloaded(UUID pet, PlayerId owner, Position at) {
    store
        .find(owner)
        .ifPresent(record -> {
          List<PlayerRecord.Pet> pets =
              record.pets().stream()
                  .map(p -> p.entity().equals(pet) ? p.withLastSeen(at) : p)
                  .toList();
          if (!pets.equals(record.pets())) {
            store.put(record.withPets(pets));
          }
        });
  }

  @Override
  public void died(UUID pet, PlayerId owner) {
    Optional<PlayerRecord.Pet> registered =
        store
            .find(owner)
            .flatMap(r -> r.pets().stream().filter(p -> p.entity().equals(pet)).findFirst());
    if (registered.isEmpty()) {
      return;
    }
    drop(owner, pet);
    audit.record(AuditLog.SEASONS, "pet-died", name(owner), "dropped", registered.get().name());
    tell(owner, "pet.died", "name", registered.get().name());
  }

  @Override
  public void onShoulder(UUID pet, PlayerId owner) {
    store
        .find(owner)
        .ifPresent(record -> {
          List<PlayerRecord.Pet> pets =
              record.pets().stream()
                  .map(p -> p.entity().equals(pet) ? p.withOnShoulder() : p)
                  .toList();
          if (!pets.equals(record.pets())) {
            store.put(record.withPets(pets));
          }
        });
  }

  /**
   * Brings an owner's pets that are due (registered in a season before the one they have entered)
   * to stand beside them, one at a time. Harmless to call again: pets already brought are no longer
   * registered, and a second call while one runs does nothing.
   */
  public CompletableFuture<Void> travel(PlayerId owner) {
    Optional<PlayerRecord> record = store.find(owner);
    List<PlayerRecord.Pet> due =
        record
            .map(r -> r.pets().stream().filter(p -> p.season() < r.seasonEntered()).toList())
            .orElse(List.of());
    if (due.isEmpty() || !travelling.add(owner)) {
      return CompletableFuture.completedFuture(null);
    }
    if (journal.isEmpty()) {
      LOG.warn("Pets of {} wait to travel: the journal is unavailable", owner);
      travelling.remove(owner);
      return CompletableFuture.completedFuture(null);
    }
    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
    for (PlayerRecord.Pet pet : due) {
      chain =
          chain
              .thenCompose(done -> bring(owner, pet))
              .thenAcceptAsync(outcome -> settle(owner, pet, outcome), serverThread)
              .exceptionally(failure -> {
                LOG.error("Bringing {}'s pet {} failed", owner, pet.name(), failure);
                return null;
              });
    }
    return chain.whenComplete((done, failure) -> travelling.remove(owner));
  }

  /** After a login's catch-up: messages left while they were away, then any pets still due. */
  public void loggedIn(PlayerId player) {
    if (platform.players().find(player).isEmpty()) {
      return;
    }
    store
        .find(player)
        .filter(record -> !record.notices().isEmpty())
        .ifPresent(record -> {
          Messages messages = messages();
          for (PlayerRecord.Notice notice : record.notices()) {
            platform
                .players()
                .sendMessage(
                    player,
                    messages.get(notice.key(), notice.placeholders().toArray(String[]::new)));
          }
          store.put(record.withNotices(List.of()));
        });
    var _ = travel(player);
  }

  /** Pets registered in an earlier season than the one being played, still to travel. */
  public long waiting() {
    Optional<SeasonState> current = state.get();
    if (current.isEmpty() || current.get().launchedAt().isEmpty()) {
      return 0;
    }
    int season = current.get().season();
    return store.all().stream()
        .flatMap(record -> record.pets().stream())
        .filter(pet -> pet.season() < season)
        .count();
  }

  /** At shutdown: where every loaded registered pet stands, since unloads after it go unheard. */
  public void stop() {
    for (PlayerRecord record : store.all()) {
      for (PlayerRecord.Pet pet : record.pets()) {
        platform
            .entities()
            .position(pet.entity())
            .ifPresent(at -> unloaded(pet.entity(), record.id(), at));
      }
    }
  }

  private CompletableFuture<Travel> bring(PlayerId owner, PlayerRecord.Pet pet) {
    return platform
        .entities()
        .load(pet.entity(), pet.lastSeen())
        .thenComposeAsync(
            found -> {
              if (found.isEmpty()) {
                // A parrot on its owner's shoulder is out of the world, and already with them.
                return CompletableFuture.completedFuture(
                    pet.onShoulder() ? Travel.CAME : Travel.MISSING);
              }
              PetInfo info = found.get();
              Travel refused =
                  !info.owner().equals(Optional.of(owner))
                      ? Travel.NOT_THEIRS
                      : info.carryingChest()
                          ? Travel.CHEST
                          : platform.players().find(owner).isEmpty() ? Travel.LATER : Travel.CAME;
              if (refused != Travel.CAME) {
                platform.entities().release(pet.entity());
                return CompletableFuture.completedFuture(refused);
              }
              Position to = platform.players().position(owner);
              int season = state.get().map(SeasonState::season).orElse(pet.season() + 1);
              return journal
                  .orElseThrow()
                  .append(JournalKind.PET_TRAVELLED, owner, season, pet.entity().toString())
                  .thenComposeAsync(
                      entry -> platform.entities().bring(pet.entity(), to), serverThread)
                  .thenApply(arrived -> arrived ? Travel.CAME : Travel.MISSING);
            },
            serverThread);
  }

  /** On the server thread: tells the owner and drops the registration, unless it waits. */
  private void settle(PlayerId owner, PlayerRecord.Pet pet, Travel outcome) {
    if (outcome == Travel.LATER) {
      return;
    }
    drop(owner, pet.entity());
    platform.entities().markPet(pet.entity(), Optional.empty());
    String result =
        switch (outcome) {
          case CAME -> "travelled";
          case CHEST -> "stayed: carries a chest";
          case MISSING -> "stayed: not found";
          case NOT_THEIRS -> "stayed: no longer theirs";
          case LATER -> "waiting";
        };
    audit.record(AuditLog.SEASONS, "pet-travel", name(owner), result, pet.name());
    String key =
        switch (outcome) {
          case CAME -> "pet.came";
          case CHEST -> "pet.stayed-chest";
          case MISSING -> "pet.missing";
          case NOT_THEIRS, LATER -> "pet.not-theirs";
        };
    tell(owner, key, "name", pet.name());
  }

  private void drop(PlayerId owner, UUID pet) {
    store
        .find(owner)
        .ifPresent(record ->
            store.put(
                record.withPets(
                    record.pets().stream().filter(p -> !p.entity().equals(pet)).toList())));
  }

  /** Tells an online player now, or keeps it for their next login. */
  private void tell(PlayerId player, String key, String... placeholders) {
    if (platform.players().find(player).isPresent()) {
      platform.players().sendMessage(player, messages().get(key, placeholders));
      return;
    }
    store
        .find(player)
        .ifPresent(record -> {
          List<PlayerRecord.Notice> notices = new ArrayList<>(record.notices());
          notices.add(new PlayerRecord.Notice(key, List.of(placeholders)));
          store.put(record.withNotices(notices));
        });
  }

  /** The type as players read it: {@code skeleton_horse} becomes {@code skeleton horse}. */
  static String typeName(String type) {
    return type.replace('_', ' ');
  }

  /** "Skeleton Horse", for naming an unnamed pet. */
  static String title(String type) {
    StringBuilder out = new StringBuilder();
    for (String word : type.split(" ", -1)) {
      if (!word.isEmpty()) {
        if (!out.isEmpty()) {
          out.append(' ');
        }
        out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
      }
    }
    return out.toString();
  }

  /** "A" or "An", for the type that follows. */
  static String article(String type) {
    return !type.isEmpty() && "aeiou".indexOf(type.charAt(0)) >= 0 ? "An" : "A";
  }

  /** The name itself if no pet has it, else the same with the lowest free number: "Wolf 2". */
  static String unique(String name, List<PlayerRecord.Pet> pets) {
    if (pets.stream().noneMatch(p -> p.name().equalsIgnoreCase(name))) {
      return name;
    }
    for (int n = 2; ; n++) {
      String numbered = name + " " + n;
      if (pets.stream().noneMatch(p -> p.name().equalsIgnoreCase(numbered))) {
        return numbered;
      }
    }
  }

  private Optional<PlayerId> player(CommandSource source) {
    if (source.player().isEmpty()) {
      source.reply(messages().get("pet.player-only"));
    }
    return source.player();
  }

  private String name(PlayerId player) {
    return platform
        .players()
        .find(player)
        .map(PlayerRef::name)
        .or(() -> store.find(player).map(PlayerRecord::lastName))
        .orElse(player.toString());
  }

  private static boolean isPlaying(Phase phase) {
    return phase instanceof Phase.Active || phase instanceof Phase.Countdown;
  }

  private Messages messages() {
    return config.get().messages();
  }
}
