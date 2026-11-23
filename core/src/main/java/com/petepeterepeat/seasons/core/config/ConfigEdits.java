// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.config;

import com.petepeterepeat.seasons.core.audit.AuditLog;
import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.log.SeasonsLog;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.Platform;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.AtomicFiles;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Command edits to the files people also edit by hand: the file is written on the writer thread,
 * every file is loaded again and swapped in, so the edit is in force at once, and the edit is
 * audited as a config change with its path, the old value, the new one and who. The zone, spawn and
 * waypoint commands all edit this way.
 *
 * <p>An edit is on disk only while it is in force. If the write or the load fails, the file is put
 * back as it was in the same turn on the writer thread, before any other edit runs: otherwise a
 * change nobody was told took effect would go live, unaudited, at the next reload, and a file the
 * load refuses would stop every later reload and leave the next startup without it.
 */
public final class ConfigEdits {
  /** The value of a key that isn't there, before an add or after a remove. */
  public static final String NONE = "none";

  private static final Logger LOG = SeasonsLog.get();

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
   * done}, on the server thread. If the write or the load fails, the file is put back as it was,
   * nothing is swapped in or audited, and the source is told with {@code failedKey}, a message
   * whose {@code <error>} is the reason.
   *
   * @param file the file {@code edit} writes
   */
  public void edit(
      CommandSource source,
      Change change,
      Path file,
      FileEdit edit,
      String failedKey,
      Runnable done) {
    edit(source, change, file, edit, failedKey, done, new String[0]);
  }

  /**
   * As {@link #edit(CommandSource, Change, Path, FileEdit, String, Runnable)}, with more
   * placeholders for {@code failedKey}'s message, as name, value pairs.
   */
  public void edit(
      CommandSource source,
      Change change,
      Path file,
      FileEdit edit,
      String failedKey,
      Runnable done,
      String... failedPlaceholders) {
    var _ =
        writer
            .submit(() -> writeAndLoad(file, edit))
            .whenComplete((result, failure) ->
                platform
                    .scheduler()
                    .runGlobal(() -> {
                      if (failure != null || result.snapshot().isEmpty()) {
                        String error = failure != null ? reason(failure) : firstProblem(result);
                        LOG.error("A config edit ({}) failed: {}", change.target(), error, failure);
                        String[] placeholders =
                            Arrays.copyOf(failedPlaceholders, failedPlaceholders.length + 2);
                        placeholders[failedPlaceholders.length] = "error";
                        placeholders[failedPlaceholders.length + 1] = error;
                        source.reply(messages().get(failedKey, placeholders));
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

  /**
   * On the writer thread: the edit, then every file loaded again. If either fails, the file goes
   * back as it was before this returns, so the next edit starts from it.
   */
  private ConfigLoader.Result writeAndLoad(Path file, FileEdit edit) throws IOException {
    Optional<byte[]> before =
        Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    ConfigLoader.Result result;
    try {
      edit.apply();
      result = loader.load();
    } catch (IOException | RuntimeException e) {
      putBack(file, before, String.valueOf(e.getMessage()));
      throw e;
    }
    if (result.snapshot().isEmpty()) {
      putBack(file, before, firstProblem(result));
    }
    return result;
  }

  /**
   * Puts a file back as it was before an edit, or removes it if there was none. The copy the edit
   * kept in the backups is of this same content.
   *
   * @param reason why the edit didn't take, for the error if the file can't be put back
   */
  private static void putBack(Path file, Optional<byte[]> before, String reason)
      throws IOException {
    try {
      if (before.isEmpty()) {
        Files.deleteIfExists(file);
      } else if (!Files.exists(file) || !Arrays.equals(Files.readAllBytes(file), before.get())) {
        AtomicFiles.write(file, before.get());
      }
    } catch (IOException e) {
      LOG.error(
          "{} couldn't be put back after an edit that didn't take; it still holds it", file, e);
      throw new IOException(
          reason
              + "; "
              + file.getFileName()
              + " couldn't be put back, so it still holds the edit ("
              + e.getMessage()
              + ")",
          e);
    }
  }

  /** Why an edit failed, from inside the wrappers the writer thread put round it. */
  private static String reason(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof java.util.concurrent.CompletionException
            || cause instanceof java.io.UncheckedIOException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());
  }

  /** The first reason a load failed, as admins read it. */
  private String firstProblem(ConfigLoader.Result result) {
    return result.problems().stream()
        .findFirst()
        .map(problem -> problem.describe(messages()))
        .orElse("?");
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
