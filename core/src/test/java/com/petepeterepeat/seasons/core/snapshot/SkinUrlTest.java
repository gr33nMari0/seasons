// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.port.Skin;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SkinUrlTest {
  private static Skin skin(String url) {
    String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
    return new Skin(
        Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)),
        Optional.empty());
  }

  @Test
  void mojangsTextureServerOverHttps() {
    assertThat(SkinUrl.of(skin("http://textures.minecraft.net/texture/0123456789abcdef0123")))
        .contains("https://textures.minecraft.net/texture/0123456789abcdef0123");
  }

  @Test
  void nothingElse() {
    assertThat(SkinUrl.of(skin("https://evil.example.com/texture/0123456789abcdef"))).isEmpty();
    assertThat(SkinUrl.of(new Skin("not base64!", Optional.empty()))).isEmpty();
    assertThat(SkinUrl.of(skin("javascript:alert(1)"))).isEmpty();
  }
}
