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
          "wake.mjs");

  static final String PREFIX = "/app/";

  /** The page's own addresses, all answered with index.html. */
  private static final Pattern PAGES =
      Pattern.compile(
          "/|/index\\.html|/museum|/history|/screen(/[a-z0-9][a-z0-9-]{0,31})?"
              + "|/hall(/\\d{1,4})?|/player/[0-9a-fA-F-]{36}");

  /** A file ready to send. */
  record Asset(String contentType, String body, String etag) {}

  private final Map<String, Asset> files;
  private final Asset page;

  private Assets(Map<String, Asset> files) {
    this.files = Map.copyOf(files);
    this.page = Objects.requireNonNull(files.get("index.html"), "index.html is bundled");
  }

  /** Reads every file of the page from the jar. */
  static Assets load() {
    Map<String, Asset> files = new LinkedHashMap<>();
    for (String name : FILES) {
      try (InputStream in =
          Objects.requireNonNull(
              Assets.class.getResourceAsStream("dashboard/" + name), name + " is bundled")) {
        byte[] bytes = in.readAllBytes();
        files.put(
            name, new Asset(type(name), new String(bytes, StandardCharsets.UTF_8), etag(bytes)));
      } catch (IOException e) {
        throw new IllegalStateException("Can't read the dashboard's " + name, e);
      }
    }
    return new Assets(files);
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
    throw new IllegalArgumentException("No type for " + name);
  }

  private static String etag(byte[] bytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      return "\"" + HexFormat.of().formatHex(hash, 0, 16) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
