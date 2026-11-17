// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.port;

import com.petepeterepeat.seasons.core.message.Message;

/** A boss bar every online player sees, such as the end countdown. */
public interface ProgressBar {
  /**
   * Changes the title and fill, and shows the bar to anyone who joined since.
   *
   * @param progress from 0 (empty) to 1 (full)
   */
  void update(Message title, float progress);

  /** Removes the bar for everyone. */
  void hide();
}
