// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import java.util.Optional;

/**
 * A player's skin textures as their game profile carried them, so a figure keeps the skin of its
 * own season.
 *
 * @param value the base64 textures property
 * @param signature its signature, when the profile was signed
 */
public record Skin(String value, Optional<String> signature) {}
