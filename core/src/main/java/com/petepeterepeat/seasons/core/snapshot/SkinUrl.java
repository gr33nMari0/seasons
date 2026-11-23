// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.snapshot;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.petepeterepeat.seasons.core.port.Skin;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/** A skin's texture address on Mojang's texture server, from its signed texture value. */
public final class SkinUrl {
  private static final Pattern TEXTURE =
      Pattern.compile("https?://textures\\.minecraft\\.net/texture/[0-9a-f]{16,128}");

  private SkinUrl() {}

  /** The address, always https; empty if the value names anything but Mojang's texture server. */
  public static Optional<String> of(Skin skin) {
    try {
      String json = new String(Base64.getDecoder().decode(skin.value()), StandardCharsets.UTF_8);
      JsonElement url =
          JsonParser.parseString(json)
              .getAsJsonObject()
              .getAsJsonObject("textures")
              .getAsJsonObject("SKIN")
              .get("url");
      String text = url.getAsString();
      if (!TEXTURE.matcher(text).matches()) {
        return Optional.empty();
      }
      return Optional.of(text.replaceFirst("^http:", "https:"));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
