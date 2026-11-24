// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.config.SeasonsConfig;
import com.petepeterepeat.seasons.core.event.Decision;
import com.petepeterepeat.seasons.core.event.Intent;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Displays;
import com.petepeterepeat.seasons.core.port.GameMode;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.port.ScheduledTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Hall links in the world (M8): link signs placed from {@code /hof link item}, a link block broken
 * by a builder, the floating texts and their hitboxes, and the editor on a sneak-click. Floating
 * texts are never saved with the world; they are drawn from links.yml whenever their chunk is
 * loaded, and taken away when their trigger goes. Runs on the server thread.
 */
public final class LinkWorld {
  /** The tag every floating text and hitbox carries, then the link, the trigger and a print. */
  public static final String TAG = "seasons:link-text:";

  private static final Duration SWEEP = Duration.ofSeconds(1);

  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final HallVisits visits;
  private BiConsumer<PlayerId, HallLayout.Link> editor = (p, l) -> {};

  /**
   * The Hall's and the zones' say on placing or breaking a block, asked before a link sign is
   * placed or a link block's trigger comes off, so neither gets past locked land or someone's cell.
   */
  private Function<Intent, Optional<Decision>> rules = intent -> Optional.empty();

  /** What is drawn now: tag to the text's and the hitbox's entity ids. */
  private final Map<String, Drawn> drawn = new HashMap<>();

  private @Nullable ScheduledTask sweeper;

  public LinkWorld(Platform platform, ConfigHolder config, HallService hall, HallVisits visits) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.visits = visits;
  }

  /** Connects the Hall's and the zones' rules for placing and breaking blocks. */
  public void bindRules(Function<Intent, Optional<Decision>> next) {
    this.rules = next;
  }

  /** Whether a decision stops what was asked. */
  private static boolean stops(Decision decision) {
    return decision instanceof Decision.Deny || decision instanceof Decision.Redirect;
  }

  /** Connects the link editor. */
  public void bindEditor(BiConsumer<PlayerId, HallLayout.Link> next) {
    this.editor = next;
  }

  public void start() {
    sweeper = platform.scheduler().runGlobalRepeating(SWEEP, this::draw);
  }

  public void stop() {
    if (sweeper != null) {
      sweeper.cancel();
    }
    drawn.values().forEach(this::remove);
    drawn.clear();
  }

  // Deciding

  /** This class's say on an intent, if it has one. */
  public Optional<Decision> decide(Intent intent) {
    return switch (intent) {
      case Intent.PlaceLinkItem place -> Optional.of(place(place));
      case Intent.BreakBlock broken -> broken(broken);
      case Intent.EditSign sign ->
          hall.layout().linkAt(sign.block()).isPresent()
              ? Optional.of(new Decision.Deny(Optional.empty()))
              : Optional.empty();
      case Intent.UseDisplay use -> used(use.player(), use.tag(), use.sneaking(), false);
      case Intent.HitDisplay hit -> used(hit.player(), hit.tag(), false, true);
      case Intent.UseBlock use -> sneakClick(use);
      default -> Optional.empty();
    };
  }

  private Decision place(Intent.PlaceLinkItem place) {
    PlayerId player = place.player();
    if (!platform.permissions().has(player, LinkService.PERMISSION_PLACE)) {
      return Decision.deny(messages().get("hof.link.item-not-allowed"));
    }
    if (hall.hallLeftOut()) {
      return Decision.deny(messages().get("hof.hall.left-out"));
    }
    Optional<HallLayout.Link> link = hall.layout().link(place.link());
    if (link.isEmpty()) {
      return Decision.deny(messages().get("hof.link.unknown", "link", place.link()));
    }
    if (hall.layout().linkAt(place.block()).isPresent()) {
      return new Decision.Deny(Optional.empty());
    }
    Position block = blockOf(place.block());
    // Placed as any block is: locked land and other players' cells refuse it.
    Optional<Decision> ruled =
        rules.apply(new Intent.PlaceBlock(player, place.block(), place.blockType()));
    if (ruled.isPresent() && stops(ruled.get())) {
      return ruled.get();
    }
    var _ =
        hall.saveHall(
            hall.layout()
                .withLink(
                    link.get()
                        .withTrigger(
                            new HallLayout.Trigger.OnBlock(block, HallLayout.BlockKind.USE))));
    // The sign's text once it stands, the tick after.
    SeasonsConfig.HallLinks.Item item = config.get().config().links().item();
    platform
        .scheduler()
        .runGlobal(() -> {
          List<String> lines = item.lines();
          for (int i = 0; i < Math.min(4, lines.size()); i++) {
            platform
                .world()
                .writeSignLine(block, i, Message.of(LinkText.clean(lines.get(i), styleTags())));
          }
          platform.world().glowSign(block, item.glowing());
        });
    return Decision.allow();
  }

  /**
   * A link's block broken: only a builder gets this far past the Hall's rules, and then the trigger
   * comes off its link; a link sign broken in survival comes back as a link sign.
   */
  private Optional<Decision> broken(Intent.BreakBlock broken) {
    Optional<HallLayout.Link> link = hall.layout().linkAt(broken.block());
    PlayerId player = broken.player();
    if (link.isEmpty() || !builder(player) || hall.hallLeftOut()) {
      return Optional.empty();
    }
    // Broken as any block is: a builder still can't break into locked land.
    Optional<Decision> ruled = rules.apply(broken);
    if (ruled.isPresent() && stops(ruled.get())) {
      return ruled;
    }
    List<HallLayout.Trigger> triggers = new ArrayList<>(link.get().triggers());
    triggers.removeIf(t ->
        t instanceof HallLayout.Trigger.OnBlock on
            && HallLayout.sameBlock(on.at(), broken.block()));
    var _ = hall.saveHall(hall.layout().withLink(link.get().withTriggers(triggers)));
    boolean sign = platform.world().block(broken.block()).type().endsWith("_sign");
    if (sign && platform.players().gameMode(player) != GameMode.CREATIVE) {
      SeasonsConfig.HallLinks.Item item = config.get().config().links().item();
      platform
          .inventories()
          .giveLinkItem(player, item.material(), Message.of(item.name()), link.get().id(), 1);
      return Optional.of(Decision.allowWithoutDrops());
    }
    return Optional.of(Decision.allow());
  }

  private boolean builder(PlayerId player) {
    return platform.permissions().has(player, HallService.BYPASS)
        || (config.get().config().hof().creativeBuilds()
            && platform.players().gameMode(player) == GameMode.CREATIVE);
  }

  /** A floating text clicked or hit: its link, or for a sneaking admin its editor. */
  private Optional<Decision> used(PlayerId player, String tag, boolean sneaking, boolean left) {
    if (!tag.startsWith(TAG)) {
      return Optional.empty();
    }
    Optional<Found> found = find(tag);
    if (found.isPresent()) {
      if (sneaking && admin(player) && config.get().config().links().editor().sneakClick()) {
        editor.accept(player, found.get().link());
      } else if (!left || found.get().text().left()) {
        visits.takeLink(player, found.get().link());
      }
    }
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  /** A sneak-click with an empty hand on a trigger by a link admin opens its editor (M8). */
  private Optional<Decision> sneakClick(Intent.UseBlock use) {
    if (!use.sneaking()
        || !use.emptyHand()
        || !config.get().config().links().editor().sneakClick()
        || !admin(use.player())) {
      return Optional.empty();
    }
    Optional<HallLayout.Link> link = hall.layout().linkAt(use.block());
    if (link.isEmpty()) {
      return Optional.empty();
    }
    if (use.mainHand()) {
      editor.accept(use.player(), link.get());
    }
    return Optional.of(new Decision.Deny(Optional.empty()));
  }

  private boolean admin(PlayerId player) {
    return platform.permissions().has(player, LinkService.PERMISSION_ADMIN)
        || platform.permissions().has(player, HallCommands.PERMISSION_ADMIN);
  }

  // Drawing

  private record Found(HallLayout.Link link, HallLayout.Trigger.Text text, int index) {}

  private Optional<Found> find(String tag) {
    String rest = tag.substring(TAG.length());
    int first = rest.indexOf(':');
    int second = first < 0 ? -1 : rest.indexOf(':', first + 1);
    if (first < 0) {
      return Optional.empty();
    }
    Optional<HallLayout.Link> link = hall.layout().link(rest.substring(0, first));
    int index;
    try {
      index =
          Integer.parseInt(
              second < 0 ? rest.substring(first + 1) : rest.substring(first + 1, second));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    if (link.isEmpty() || index < 0 || index >= link.get().triggers().size()) {
      return Optional.empty();
    }
    return link.get().triggers().get(index) instanceof HallLayout.Trigger.Text text
        ? Optional.of(new Found(link.get(), text, index))
        : Optional.empty();
  }

  /** The tag a text trigger is drawn with: its link, its place and a print of how it looks. */
  static String tag(HallLayout.Link link, int index, HallLayout.Trigger.Text text) {
    return TAG + link.id() + ":" + index + ":" + Integer.toHexString(text.hashCode());
  }

  /** Draws every text trigger in a loaded chunk that isn't drawn, and takes away the rest. */
  void draw() {
    Map<String, Placed> wanted = new HashMap<>();
    for (HallLayout.Link link : hall.layout().links().values()) {
      for (int i = 0; i < link.triggers().size(); i++) {
        if (link.triggers().get(i) instanceof HallLayout.Trigger.Text text
            && platform.world().isLoaded(text.at())) {
          wanted.put(tag(link, i, text), new Placed(link, text));
        }
      }
    }
    for (var it = drawn.entrySet().iterator(); it.hasNext(); ) {
      var entry = it.next();
      if (!wanted.containsKey(entry.getKey()) || !alive(entry.getValue())) {
        remove(entry.getValue());
        it.remove();
      }
    }
    for (var entry : wanted.entrySet()) {
      if (!drawn.containsKey(entry.getKey())) {
        drawn.put(entry.getKey(), spawn(entry.getKey(), entry.getValue().text()));
      }
    }
  }

  private record Placed(HallLayout.Link link, HallLayout.Trigger.Text text) {}

  /** A floating text as drawn: its text display, its hitbox and where they stand. */
  private record Drawn(UUID text, UUID box, Position at) {}

  /** Whether a drawn text is still there: its chunk loaded and its display not gone. */
  private boolean alive(Drawn drawn) {
    Position p = drawn.at();
    if (!platform.world().isLoaded(p)) {
      return false;
    }
    return platform
        .displays()
        .tagged(
            Position.of(p.dimension(), p.x() - 1, p.y() - 1, p.z() - 1),
            Position.of(p.dimension(), p.x() + 1, p.y() + 2, p.z() + 1))
        .stream()
        .anyMatch(shown -> shown.id().equals(drawn.text()));
  }

  private Drawn spawn(String tag, HallLayout.Trigger.Text text) {
    SeasonsConfig.HallLinks.Text look = config.get().config().links().text();
    Displays.LinkLook style =
        new Displays.LinkLook(
            text.scale(),
            text.billboard(),
            argb(look.background()),
            look.shadow(),
            look.fullBright());
    String joined = String.join("<newline>", cleaned(text.lines()));
    UUID shown = platform.displays().spawnLinkText(text.at(), Message.of(joined), tag, style);
    int longest = text.lines().stream().mapToInt(l -> LinkText.plain(l).length()).max().orElse(4);
    double width = Math.max(0.6, Math.min(12, longest * 0.13 * text.scale()));
    double height = Math.max(0.4, Math.max(1, text.lines().size()) * 0.3 * text.scale());
    UUID box = platform.displays().spawnHitbox(text.at(), width, height, tag);
    return new Drawn(shown, box, text.at());
  }

  private void remove(Drawn drawn) {
    platform.displays().remove(drawn.text());
    platform.displays().remove(drawn.box());
  }

  private List<String> cleaned(List<String> lines) {
    return lines.stream().map(l -> LinkText.clean(l, styleTags())).toList();
  }

  /** {@code #AARRGGBB} or {@code #RRGGBB} as a colour, or a faint black. */
  static int argb(String text) {
    try {
      String hex = text.startsWith("#") ? text.substring(1) : text;
      long value = Long.parseLong(hex, 16);
      return (int) (hex.length() == 6 ? value | 0xFF000000L : value);
    } catch (NumberFormatException e) {
      return 0x40000000;
    }
  }

  private static Position blockOf(Position at) {
    return Position.of(at.dimension(), at.blockX(), at.blockY(), at.blockZ());
  }

  private List<String> styleTags() {
    return config.get().config().links().styleTags();
  }

  private Messages messages() {
    return config.get().messages();
  }
}
