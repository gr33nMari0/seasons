// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.announce;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminAlertsTest {
  @Test
  void alertsCloseTogetherShareAPostALineEach() {
    assertThat(AdminAlerts.posts(List.of("one", "two"))).containsExactly("one\ntwo");
  }

  @Test
  void aPostNeverPassesDiscordsLimit() {
    String line = "x".repeat(1000);
    List<String> posts = AdminAlerts.posts(List.of(line, line, line, "y".repeat(5000)));

    assertThat(posts).hasSize(4).allMatch(post -> post.length() <= AdminAlerts.MAX_POST);
    assertThat(posts.getLast()).endsWith("…");
  }

  @Test
  void nothingWaitingIsNoPost() {
    assertThat(AdminAlerts.posts(Collections.emptyList())).isEmpty();
  }
}
