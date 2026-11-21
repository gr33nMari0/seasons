// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.hall;

import com.petepeterepeat.seasons.core.port.Templates;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds cell templates by name (questions 230): {@code templates/<name>.nbt} in the world's Seasons
 * folder, then a structure a structure block saved as {@code seasons:<name>}, then the jar's.
 */
public final class TemplateLibrary {
  /** The templates the jar ships (questions 238). */
  public static final List<String> BUNDLED = List.of("alcove", "plinth");

  /** Template names: what a structure block's name may hold after {@code seasons:}. */
  public static final Pattern NAME = Pattern.compile("[a-z0-9_.-]{1,64}");

  private static final String SUFFIX = ".nbt";

  private final Path directory;
  private final Templates templates;

  /**
   * @param directory {@code <world>/seasons/templates}
   */
  public TemplateLibrary(Path directory, Templates templates) {
    this.directory = directory;
    this.templates = templates;
  }

  /** A template's bytes and where they came from. */
  public static final class Found {
    private final Template.Source source;
    private final byte[] data;

    public Found(Template.Source source, byte[] data) {
      this.source = source;
      this.data = data.clone();
    }

    public Template.Source source() {
      return source;
    }

    public byte[] data() {
      return data.clone();
    }
  }

  public Optional<Found> find(String name) {
    if (!NAME.matcher(name).matches()) {
      return Optional.empty();
    }
    Path file = directory.resolve(name + SUFFIX);
    if (Files.isRegularFile(file)) {
      try {
        return Optional.of(new Found(Template.Source.FILE, Files.readAllBytes(file)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    Optional<byte[]> saved = templates.saved(name);
    if (saved.isPresent()) {
      return Optional.of(new Found(Template.Source.STRUCTURE, saved.get()));
    }
    return bundled(name).map(data -> new Found(Template.Source.BUNDLED, data));
  }

  /** One of the jar's templates, from {@code /templates/<name>.nbt} on the class path. */
  public static Optional<byte[]> bundled(String name) {
    if (!BUNDLED.contains(name)) {
      return Optional.empty();
    }
    try (InputStream in =
        TemplateLibrary.class.getResourceAsStream("/templates/" + name + SUFFIX)) {
      return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Every template name there is, sorted. */
  public List<String> names() {
    TreeSet<String> names = new TreeSet<>(BUNDLED);
    names.addAll(templates.savedNames());
    if (Files.isDirectory(directory)) {
      try (Stream<Path> files = Files.list(directory)) {
        files
            .map(f -> f.getFileName().toString())
            .filter(f -> f.endsWith(SUFFIX))
            .map(f -> f.substring(0, f.length() - SUFFIX.length()))
            .filter(n -> NAME.matcher(n).matches())
            .forEach(names::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return List.copyOf(names);
  }
}
