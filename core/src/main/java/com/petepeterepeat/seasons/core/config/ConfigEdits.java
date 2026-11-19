// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command edits to the files people also edit by hand: the file is written on the writer thread,
 * every file is loaded again and swapped in, so the edit is in force at once, and the edit is
 * audited as a config change with its path, the old value, the new one and who. The zone, spawn and
 * waypoint commands all edit this way.
 */
public final class ConfigEdits {
  /** The value of a key that isn't there, before an add or after a remove. */
  public static final String NONE = "none";

  private static final Logger LOG = LoggerFactory.getLogger(ConfigEdits.class);

  private final Platform platform;
  private final ConfigHolder config;
  private final ConfigLoader loader;
  private final SerialWriter writer;
  private final AuditLog audit;
  private final Consumer<ConfigSnapshot> reloaded;

  /**
   * @param reloaded told of each snapshot an edit swaps in, on the server thread, as after {@code
   *     /season reload}
   */
  public ConfigEdits(
      Platform platform,
      ConfigHolder config,
      ConfigLoader loader,
      SerialWriter writer,
      AuditLog audit,
      Consumer<ConfigSnapshot> reloaded) {
    this.platform = platform;
    this.config = config;
    this.loader = loader;
    this.writer = writer;
    this.audit = audit;
    this.reloaded = reloaded;
  }

  /** Writes one file. */
  public interface FileEdit {
    void apply() throws IOException;
  }

  /**
   * What an edit changes, for the audit log.
   *
   * @param target the file and the key path in it, such as {@code seasons/3.yml zones.base}
   */
  public record Change(String target, String before, String after) {}

  /**
   * Writes a file, loads every file again and swaps them in, audits the change and then runs {@code
   * done}, on the server thread. If the write or the load fails, nothing is swapped in and the
   * source is told with {@code failedKey}, a message whose {@code <error>} is the reason.
   */
  public void edit(
      CommandSource source, Change change, FileEdit edit, String failedKey, Runnable done) {
    var _ =
        writer
            .submit(() -> {
              edit.apply();
              return loader.load();
            })
            .whenComplete((result, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      if (failure != null || result.snapshot().isEmpty()) {
                        String error =
                            failure != null
                                ? String.valueOf(failure.getMessage())
                                : result.problems().stream()
                                    .map(Object::toString)
                                    .findFirst()
                                    .orElse("?");
                        LOG.error("A config edit ({}) failed: {}", change.target(), error, failure);
                        source.reply(messages().get(failedKey, "error", error));
                        return;
                      }
                      config.set(result.snapshot().get());
                      reloaded.accept(result.snapshot().get());
                      audit.record(
                          actor(source),
                          "config",
                          change.target(),
                          "ok",
                          change.before() + " -> " + change.after());
                      done.run();
                    }));
  }

  /** A position as the audit log writes it: {@code 3000, 64, 0 in minecraft:overworld}. */
  public static String describe(Position at) {
    return at.blockX() + ", " + at.blockY() + ", " + at.blockZ() + " in " + at.dimension();
  }

  /** Who ran a command, as the audit log names them. */
  public String actor(CommandSource source) {
    return source
        .player()
        .flatMap(id -> platform.players().find(id))
        .map(PlayerRef::name)
        .orElse(messages().text("config.console-name"));
  }

  private Messages messages() {
    return config.get().messages();
  }
}
