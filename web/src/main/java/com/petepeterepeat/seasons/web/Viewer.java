// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.UUID;

/** Who is asking: a visitor, or a logged-in player, who may hold {@code seasons.web.admin}. */
sealed interface Viewer {
  /** A visitor who isn't logged in. */
  record Anonymous() implements Viewer {}

  /** A logged-in player, with the parts of the admin side they hold. */
  record Player(UUID id, String name, Grants grants, String sessionId) implements Viewer {
    /** All of the admin side or none of it. */
    Player(UUID id, String name, boolean admin, String sessionId) {
      this(id, name, Grants.of(admin), sessionId);
    }

    /** Whether they hold any part of the admin side. */
    boolean admin() {
      return grants.any();
    }
  }

  Viewer ANONYMOUS = new Anonymous();
}
