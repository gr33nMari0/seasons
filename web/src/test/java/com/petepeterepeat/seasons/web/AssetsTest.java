// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AssetsTest {
  private static final Path RESOURCES = Path.of("src/main/resources");
  private static final Path DASHBOARD =
      RESOURCES.resolve("com/petepeterepeat/seasons/web/dashboard");

  @Test
  void everyFileOfThePageIsListedAndNothingElse() throws IOException {
    try (Stream<Path> files = Files.list(DASHBOARD)) {
      assertThat(files.map(p -> p.getFileName().toString()).sorted().toList())
          .containsExactlyInAnyOrderElementsOf(Assets.FILES);
    }
  }

  @Test
  void noImageOrTextureIsBundled() throws IOException {
    try (Stream<Path> files = Files.walk(RESOURCES)) {
      assertThat(
              files
                  .map(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT))
                  .filter(n -> n.matches(".*\\.(png|jpe?g|gif|webp|bmp|ico|tga|ogg|json)$"))
                  .toList())
          .as("no Minecraft textures, sounds or language files")
          .isEmpty();
    }
  }

  @Test
  void thePageAnswersAtItsOwnAddressesAndItsFilesUnderApp() {
    Assets assets = Assets.load();
    for (String page :
        List.of(
            "/",
            "/index.html",
            "/museum",
            "/history",
            "/hall",
            "/hall/2",
            "/screen",
            "/screen/tv",
            "/player/0f9b7c3e-1a2b-4c5d-8e9f-0a1b2c3d4e5f")) {
      assertThat(assets.find(page))
          .as(page)
          .hasValueSatisfying(a -> assertThat(a.contentType()).startsWith("text/html"));
    }
    assertThat(assets.find("/app/app.mjs"))
        .hasValueSatisfying(a -> assertThat(a.contentType()).startsWith("text/javascript"));
    assertThat(assets.find("/app/style.css"))
        .hasValueSatisfying(a -> assertThat(a.contentType()).startsWith("text/css"));
    assertThat(assets.find("/app/icons.svg"))
        .hasValueSatisfying(a -> assertThat(a.contentType()).isEqualTo("image/svg+xml"));
    for (String not :
        List.of(
            "/app/",
            "/app/nothing.mjs",
            "/app/../web.yml",
            "/app/%2e%2e/messages.yml",
            "/app/dashboard/app.mjs",
            "/web.yml",
            "/screen/Bad",
            "/hall/x",
            "/player/nobody")) {
      assertThat(assets.find(not)).as(not).isEmpty();
    }
  }

  @Test
  void aStrongEtagAnd304WhenUnchanged() {
    Assets.Asset app = Assets.load().find("/app/app.mjs").orElseThrow();
    assertThat(app.etag()).matches("\"[0-9a-f]{32}\"");
    Reply fresh = Assets.reply(app, Optional.empty());
    assertThat(fresh.status()).isEqualTo(200);
    assertThat(fresh.headers())
        .containsEntry("ETag", app.etag())
        .containsEntry("Cache-Control", "no-cache");
    assertThat(Assets.reply(app, Optional.of("\"other\", " + app.etag())).status()).isEqualTo(304);
    assertThat(Assets.reply(app, Optional.of("\"other\"")).status()).isEqualTo(200);
  }
}
