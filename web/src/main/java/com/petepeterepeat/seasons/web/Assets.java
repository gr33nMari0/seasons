// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The dashboard page's own files, from the jar: an allow-list read once, each served at {@code
 * /app/<name>} with its type and a strong ETag, checked by the browser every time ({@code
 * no-cache}) so a new jar shows at once. The page itself answers at {@code /} and at each of its
 * own addresses. Nothing else is served, so no path can reach another file.
 */
final class Assets {
  /** Every file of the page, beside this class in {@code dashboard/}. */
  static final List<String> FILES =
      List.of(
          "index.html",
          "style.css",
          "icons.svg",
          "app.mjs",
          "api.mjs",
          "clock.mjs",
          "convert.mjs",
          "dom.mjs",
          "format.mjs",
          "items.mjs",
          "live.mjs",
          "login.mjs",
          "pages.mjs",
          "stream.mjs",
          "wake.mjs",
          "layout.mjs",
          "editor.mjs",
          "help.mjs",
          "share.mjs");

  static final String PREFIX = "/app/";

  /** The page's own addresses, all answered with index.html. */
  private static final Pattern PAGES =
      Pattern.compile(
          "/|/index\\.html|/museum|/history|/screen(/[a-z0-9][a-z0-9-]{0,31})?"
              + "|/hall(/\\d{1,4})?|/player/[0-9a-fA-F-]{36}|/help(/[a-z0-9-]{1,48})?");

  /**
   * Bundled at build time beside these (web/build.gradle.kts): the shared design and the guides.
   */
  static final String BUNDLED = "bundled.txt";

  /**
   * A file ready to send: text in {@code body}, or a binary file, such as a font, in {@code bytes}.
   */
  record Asset(String contentType, String body, String etag, Blob bytes) {
    Asset(String contentType, String body, String etag) {
      this(contentType, body, etag, Blob.EMPTY);
    }

    boolean binary() {
      return !bytes.isEmpty();
    }
  }

  private final Map<String, Asset> files;
  private final Asset page;

  private Assets(Map<String, Asset> files) {
    this.files = Map.copyOf(files);
    this.page = Objects.requireNonNull(files.get("index.html"), "index.html is bundled");
  }

  /** Reads every file of the page from the jar: its own, then the design and guides bundled. */
  static Assets load() {
    Map<String, Asset> files = new LinkedHashMap<>();
    List<String> names = new java.util.ArrayList<>(FILES);
    names.addAll(
        new String(read(BUNDLED), StandardCharsets.UTF_8)
            .lines()
            .map(String::strip)
            .filter(l -> !l.isEmpty())
            .toList());
    for (String name : names) {
      byte[] bytes = read(name);
      String type = type(name);
      files.put(
          name,
          binary(name)
              ? new Asset(type, "", etag(bytes), Blob.of(bytes))
              : new Asset(type, new String(bytes, StandardCharsets.UTF_8), etag(bytes)));
    }
    return new Assets(files);
  }

  private static byte[] read(String name) {
    try (InputStream in =
        Objects.requireNonNull(
            Assets.class.getResourceAsStream("dashboard/" + name), name + " is bundled")) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("Can't read the dashboard's " + name, e);
    }
  }

  private static boolean binary(String name) {
    return name.endsWith(".woff2") || name.endsWith(".png") || name.endsWith(".ttf");
  }

  /** A bundled file's text, such as a guide, by its name under the dashboard. */
  Optional<String> text(String name) {
    return Optional.ofNullable(files.get(name)).filter(a -> !a.binary()).map(Asset::body);
  }

  /** The names of the bundled files under a folder, such as {@code help/}. */
  List<String> under(String folder) {
    return files.keySet().stream().filter(n -> n.startsWith(folder)).sorted().toList();
  }

  /** The file a request path asks for, if it is the page or one of its files. */
  Optional<Asset> find(String path) {
    if (PAGES.matcher(path).matches()) {
      return Optional.of(page);
    }
    if (path.startsWith(PREFIX)) {
      return Optional.ofNullable(files.get(path.substring(PREFIX.length())));
    }
    return Optional.empty();
  }

  /** The reply to a GET for a file: the file, or 304 when the browser's copy is the same. */
  static Reply reply(Asset asset, Optional<String> ifNoneMatch) {
    boolean same =
        ifNoneMatch
            .map(h ->
                List.of(h.split(",", -1)).stream()
                    .map(String::strip)
                    .anyMatch(asset.etag()::equals))
            .orElse(false);
    Reply r =
        same
            ? new Reply(304, asset.contentType(), "", Map.of(), List.of(), false)
            : asset.binary()
                ? Reply.binary(asset.contentType(), asset.bytes())
                : new Reply(200, asset.contentType(), asset.body(), Map.of(), List.of(), false);
    return r.withHeader("ETag", asset.etag()).withHeader("Cache-Control", "no-cache");
  }

  static String type(String name) {
    if (name.endsWith(".html")) {
      return "text/html; charset=utf-8";
    }
    if (name.endsWith(".mjs") || name.endsWith(".js")) {
      return "text/javascript; charset=utf-8";
    }
    if (name.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (name.endsWith(".svg")) {
      return "image/svg+xml";
    }
    if (name.endsWith(".woff2")) {
      return "font/woff2";
    }
    if (name.endsWith(".png")) {
      return "image/png";
    }
    if (name.endsWith(".ttf")) {
      return "font/ttf";
    }
    if (name.endsWith(".md")) {
      return "text/markdown; charset=utf-8";
    }
    if (name.endsWith(".txt")) {
      return "text/plain; charset=utf-8";
    }
    throw new IllegalArgumentException("No type for " + name);
  }

  static String etag(byte[] bytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
