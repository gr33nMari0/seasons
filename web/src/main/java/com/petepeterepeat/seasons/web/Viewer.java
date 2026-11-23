// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.UUID;

/** Who is asking: a visitor, or a logged-in player, who may hold {@code seasons.web.admin}. */
sealed interface Viewer {
  /** A visitor who isn't logged in. */
  record Anonymous() implements Viewer {}

  /** A logged-in player. */
  record Player(UUID id, String name, boolean admin, String sessionId) implements Viewer {}

  Viewer ANONYMOUS = new Anonymous();
}
