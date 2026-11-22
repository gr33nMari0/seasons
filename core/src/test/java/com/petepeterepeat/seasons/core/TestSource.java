// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import com.petepeterepeat.seasons.core.command.CommandSource;
import com.petepeterepeat.seasons.core.message.Message;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.port.PlayerId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A command source that records its replies. */
public final class TestSource implements CommandSource {
  public final List<Message> replies = new ArrayList<>();
  private final Optional<PlayerId> player;

  public TestSource(Optional<PlayerId> player) {
    this.player = player;
  }

  public static TestSource console() {
    return new TestSource(Optional.empty());
  }

  @Override
  public Optional<PlayerId> player() {
    return player;
  }

  @Override
  public void reply(Message message) {
    replies.add(message);
  }

  private boolean hints;

  /**
   * Keeps next-step hints in {@link #text}; without this, they are left out, so tests of other
   * replies needn't list them (questions 268).
   */
  public TestSource withHints() {
    hints = true;
    return this;
  }

  /** The replies as plain text, with default messages. */
  public List<String> text() {
    return replies.stream()
        .map(TestSource::plain)
        .filter(line -> hints || !line.startsWith("Next: "))
        .toList();
  }

  public static String plain(Message message) {
    return Messages.plain(message);
  }
}
