// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.announce;

import java.net.URI;

/**
 * Posts to Discord webhooks. Implementations queue the post and send it off the server thread,
 * retrying with backoff, and never log the URL.
 */
public interface Webhooks extends AutoCloseable {
  /** Webhooks that post nothing. */
  Webhooks NONE =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  /**
   * Queues a post.
   *
   * @param content plain text; mentions such as {@code @everyone} never ping anyone
   */
  void post(URI webhook, String content);

  /** Stops sending, giving queued posts a moment to go out. */
  @Override
  void close();
}
