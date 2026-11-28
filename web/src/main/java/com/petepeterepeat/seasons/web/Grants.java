// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which of the dashboard's admin widgets a player holds: the children of {@code seasons.web.admin}
 * that are widgets, one per widget, so an admin can be given the audit log without server health
 * (M10 plan, R9). Holding {@code seasons.web.admin} grants them all. Its other children, {@code
 * .setup} and {@code .reload}, are commands, which ops hold by default, and never make a dashboard
 * session an admin's.
 */
record Grants(Set<Grants.Grant> held) {
  /** One part, as its permission node's last word. */
  enum Grant {
    /** The audit-log widget and {@code /api/v1/admin/audit}. */
    AUDIT,
    /** The server-health widget and {@code /api/v1/admin/health}. */
    HEALTH,
    /** The pending-players widget and {@code /api/v1/admin/pending}. */
    PLAYERS;

    /** Its node, such as {@code seasons.web.admin.audit}. */
    String node() {
      return PaperWebCommands.ADMIN_PERMISSION + "." + name().toLowerCase(Locale.ROOT);
    }

    static Optional<Grant> parse(String word) {
      for (Grant g : values()) {
        if (g.name().equalsIgnoreCase(word.strip())) {
          return Optional.of(g);
        }
      }
      return Optional.empty();
    }
  }

  static final Grants NONE = new Grants(Set.of());
  static final Grants ALL = new Grants(EnumSet.allOf(Grant.class));

  Grants {
    held = held.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(held));
  }

  /** All of them or none, as a 1.4 session's admin flag says. */
  static Grants of(boolean admin) {
    return admin ? ALL : NONE;
  }

  static Grants of(Collection<Grant> held) {
    return new Grants(Set.copyOf(held));
  }

  /** What a player holds, asked node by node. */
  static Grants from(Predicate<String> has) {
    EnumSet<Grant> held = EnumSet.noneOf(Grant.class);
    for (Grant g : Grant.values()) {
      if (has.test(g.node())) {
        held.add(g);
      }
    }
    return new Grants(held);
  }

  /** Whether any part is held: the admin flag the dashboard and older files know. */
  boolean any() {
    return !held.isEmpty();
  }

  boolean has(Grant grant) {
    return held.contains(grant);
  }

  /**
   * The part a widget or field belongs to, if it is one of the admin widgets: an admins-only key
   * with none needs any part.
   */
  static Optional<Grant> forKey(String key) {
    int dot = key.indexOf('.');
    String widget = dot < 0 ? key : key.substring(0, dot);
    return switch (widget) {
      case "audit-log" -> Optional.of(Grant.AUDIT);
      case "server-health" -> Optional.of(Grant.HEALTH);
      case "pending-players" -> Optional.of(Grant.PLAYERS);
      default -> Optional.empty();
    };
  }

  /** Whether these grants see a key whose level is admins. */
  boolean sees(String key) {
    return forKey(key).map(this::has).orElseGet(this::any);
  }
}
