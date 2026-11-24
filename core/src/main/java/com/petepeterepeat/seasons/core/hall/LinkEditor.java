// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.command.Arguments;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.config.ConfigHolder;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Menus;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The link editor (M8): a form an admin opens by sneak-clicking a trigger, or clicking one with the
 * wand, to change where the link leads, its name and who may take it, to test it, or to remove the
 * trigger or the link. Every button re-checks that the admin may still edit links, and does what
 * the matching command does, through the same code. Where the game can't show a form, a choice menu
 * offers the buttons and chat shows the link. Runs on the server thread.
 */
public final class LinkEditor {
  private final Platform platform;
  private final ConfigHolder config;
  private final HallService hall;
  private final LinkCommands commands;
  private final HallVisits visits;

  /** The form each admin has open: the link it edits and how to answer it, for scenario hooks. */
  private final Map<PlayerId, Open> open = new HashMap<>();

  private record Open(String link, Menus.Answered answered) {}

  public LinkEditor(
      Platform platform,
      ConfigHolder config,
      HallService hall,
      LinkCommands commands,
      HallVisits visits) {
    this.platform = platform;
    this.config = config;
    this.hall = hall;
    this.commands = commands;
    this.visits = visits;
  }

  /** Opens the editor of a link for an admin. */
  public void open(PlayerId player, HallLayout.Link link) {
    Optional<Position> clicked =
        platform.players().targetBlock(player, 6).filter(at -> link.isAt(at));
    Menus.Answered answered =
        (button, values) -> answer(player, link.id(), clicked, button, values);
    open.put(player, new Open(link.id(), answered));
    Menus.Form form = form(link, clicked.isPresent());
    boolean shown =
        config.get().config().links().editor().ui().equals("dialog")
            && platform.menus().openForm(player, form, answered);
    if (!shown) {
      // The buttons as a choice menu, and the link in chat.
      tell(player, messages().get("hof.link.editing", "link", link.id()));
      commands.details(link).forEach(line -> tell(player, line));
      List<Menus.Choice> choices = new ArrayList<>();
      List<String> keys = new ArrayList<>();
      for (Menus.Button button : form.buttons()) {
        if (button.key().equals("save")) {
          continue;
        }
        choices.add(new Menus.Choice(icon(button.key()), button.label()));
        keys.add(button.key());
      }
      platform
          .menus()
          .openChoice(
              player,
              form.title(),
              choices,
              index -> {
                platform.menus().close(player);
                if (index >= 0 && index < keys.size()) {
                  answered.answered(keys.get(index), Map.of());
                }
              });
    }
  }

  private static String icon(String button) {
    return switch (button) {
      case "here" -> "minecraft:compass";
      case "test" -> "minecraft:ender_pearl";
      case "remove-trigger" -> "minecraft:shears";
      case "remove" -> "minecraft:barrier";
      default -> "minecraft:paper";
    };
  }

  /** The form for a link: what it is, what can change, and the buttons. */
  Menus.Form form(HallLayout.Link link, boolean fromTrigger) {
    Messages m = messages();
    List<Message> body = new ArrayList<>();
    body.add(
        m.get(
            "hof.link.editor.leads",
            "to",
            link.to()
                .map(t -> LinkService.describe(m, t))
                .orElse(m.text("hof.link.editor.nowhere"))));
    for (int i = 0; i < link.triggers().size(); i++) {
      body.add(
          m.get(
              "hof.link.info-trigger",
              "n",
              Integer.toString(i + 1),
              "trigger",
              LinkCommands.describe(m, link.triggers().get(i))));
    }
    link.pair().ifPresent(p -> body.add(m.get("hof.link.info-pair", "other", p)));
    List<Menus.Field> fields =
        List.of(
            new Menus.Field.Text(
                "to", m.get("hof.link.editor.to"), link.to().map(LinkEditor::words).orElse(""), 64),
            new Menus.Field.Text(
                "name", m.get("hof.link.editor.name"), link.name().orElse(""), 120),
            new Menus.Field.Choice(
                "access",
                m.get("hof.link.editor.access"),
                List.of("everyone", "op", "permission"),
                List.of(
                    m.get("hof.link.who-everyone"),
                    m.get("hof.link.who-op"),
                    m.get("hof.link.editor.permission")),
                link.access().word()),
            new Menus.Field.Text(
                "node", m.get("hof.link.editor.node"), link.access().node().orElse(""), 64));
    List<Menus.Button> buttons = new ArrayList<>();
    buttons.add(new Menus.Button("save", m.get("hof.link.editor.save")));
    buttons.add(new Menus.Button("here", m.get("hof.link.editor.here")));
    buttons.add(new Menus.Button("test", m.get("hof.link.editor.test")));
    if (fromTrigger) {
      buttons.add(new Menus.Button("remove-trigger", m.get("hof.link.editor.remove-trigger")));
    }
    buttons.add(new Menus.Button("remove", m.get("hof.link.editor.remove")));
    return new Menus.Form(m.get("hof.link.editor.title", "link", link.id()), body, fields, buttons);
  }

  /** A destination as the words /hof link to takes. */
  static String words(HallLayout.Target to) {
    return switch (to) {
      case HallLayout.Target.Hall() -> "hall";
      case HallLayout.Target.Return() -> "return";
      case HallLayout.Target.SeasonSpawn(int season) -> "season " + season;
      case HallLayout.Target.AtPoint(String point) -> "point " + point;
      case HallLayout.Target.ToLink(String link) -> "link " + link;
      case HallLayout.Target.ToGallery(String gallery) -> "gallery " + gallery;
      case HallLayout.Target.At(Position at) ->
          at.blockX() + " " + at.blockY() + " " + at.blockZ() + " " + Math.round(at.yaw());
    };
  }

  /** A button clicked: re-checked, then done as its command does it. */
  void answer(
      PlayerId player,
      String id,
      Optional<Position> clicked,
      String button,
      Map<String, String> values) {
    open.remove(player);
    if (!platform.permissions().has(player, LinkService.PERMISSION_ADMIN)
        && !platform.permissions().has(player, HallCommands.PERMISSION_ADMIN)) {
      tell(player, messages().get("hof.link.editor.not-allowed"));
      return;
    }
    Optional<HallLayout.Link> link = hall.layout().link(id);
    if (link.isEmpty()) {
      tell(player, messages().get("hof.link.unknown", "link", id));
      return;
    }
    CommandSource source = source(player);
    switch (button) {
      case "save" -> {
        String to = values.getOrDefault("to", "").trim();
        if (!to.isEmpty() && !link.get().to().map(LinkEditor::words).orElse("").equals(to)) {
          commands.to(source, id, to);
        }
        String name = values.getOrDefault("name", "").trim();
        if (!name.isEmpty() && !link.get().name().orElse("").equals(name)) {
          commands.name(source, new Arguments(Map.of("link", id, "text", name)));
        }
        String access = values.getOrDefault("access", link.get().access().word());
        String node = values.getOrDefault("node", "").trim();
        String who = access.equals("permission") && !node.isEmpty() ? access + " " + node : access;
        String now =
            link.get().access().word() + link.get().access().node().map(n -> " " + n).orElse("");
        if (!who.equals(now)) {
          commands.access(source, new Arguments(Map.of("link", id, "who", who)));
        }
      }
      case "here" -> commands.to(source, id, "here");
      case "test" -> visits.takeLink(player, link.get());
      case "remove-trigger" -> {
        if (clicked.isPresent()) {
          List<HallLayout.Trigger> triggers = link.get().triggers();
          for (int i = 0; i < triggers.size(); i++) {
            if (HallLayout.sameBlock(triggers.get(i).at(), clicked.get())) {
              commands.triggerRemove(
                  source, new Arguments(Map.of("link", id, "which", Integer.toString(i + 1))));
              return;
            }
          }
        }
      }
      case "remove" -> commands.remove(source, id, "");
      default -> {}
    }
  }

  /** The link the form a player has open edits, if they have one open (for scenario hooks). */
  public Optional<String> openFor(PlayerId player) {
    return Optional.ofNullable(open.get(player)).map(Open::link);
  }

  /** Answers the form a player has open as if they clicked a button (for scenario hooks). */
  public boolean answerFor(PlayerId player, String button, Map<String, String> values) {
    Open form = open.get(player);
    if (form == null) {
      return false;
    }
    form.answered().answered(button, values);
    return true;
  }

  private CommandSource source(PlayerId player) {
    return new CommandSource() {
      @Override
      public Optional<PlayerId> player() {
        return Optional.of(player);
      }

      @Override
      public void reply(Message message) {
        tell(player, message);
      }
    };
  }

  private void tell(PlayerId player, Message message) {
    platform.players().sendMessage(player, message);
  }

  private Messages messages() {
    return config.get().messages();
  }
}
