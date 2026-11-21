// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.hall.Gallery;
import com.petepeterepeat.seasons.core.hall.Template;
import com.petepeterepeat.seasons.core.hall.TemplateLibrary;
import com.petepeterepeat.seasons.core.message.Messages;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The two templates the jar ships (questions 238). */
class DefaultTemplatesTest {
  private final FakeTemplates reader = new FakePlatform().templates;

  @Test
  void theCommittedFilesAreWhatTheGeneratorWrites() throws IOException {
    // Gradle runs tests from the core module.
    Path templates = Path.of("..", "templates");
    for (Map.Entry<String, byte[]> entry :
        Map.of("alcove", DefaultTemplates.alcove(), "plinth", DefaultTemplates.plinth())
            .entrySet()) {
      byte[] committed = Files.readAllBytes(templates.resolve(entry.getKey() + ".nbt"));
      assertThat(Nbt.canonical(Nbt.read(committed)))
          .as("%s.nbt: run ./gradlew :core:writeDefaultTemplates", entry.getKey())
          .isEqualTo(Nbt.canonical(Nbt.read(entry.getValue())));
      assertThat(TemplateLibrary.bundled(entry.getKey())).as("in the jar's resources").isPresent();
    }
  }

  @Test
  void theyCarryTheirDataVersionAndPassTheRules() throws IOException {
    assertThat(Nbt.read(DefaultTemplates.alcove()))
        .containsEntry("DataVersion", StructureBuilder.DATA_VERSION);
    Messages messages = Messages.defaults();
    Template alcove =
        new Template(
            "alcove",
            Template.Source.BUNDLED,
            DefaultTemplates.alcove(),
            reader.scan(DefaultTemplates.alcove()));
    Template plinth =
        new Template(
            "plinth",
            Template.Source.BUNDLED,
            DefaultTemplates.plinth(),
            reader.scan(DefaultTemplates.plinth()));
    assertThat(alcove.problem(Gallery.Kind.SEASON, messages)).isEmpty();
    assertThat(plinth.problem(Gallery.Kind.SEASON, messages)).isEmpty();
    assertThat(plinth.problem(Gallery.Kind.MUSEUM, messages)).isEmpty();
    assertThat(alcove.markers())
        .extracting(m -> m.type().id() + " " + m.x() + "," + m.y() + "," + m.z())
        .containsExactlyInAnyOrder(
            "archive 1,1,1", "figure 2,1,1", "plaque 3,1,1", "trophy 2,3,1", "entry 2,1,3");
    assertThat(plinth.markers())
        .extracting(m -> m.type().id() + " " + m.x() + "," + m.y() + "," + m.z())
        .containsExactlyInAnyOrder("figure 1,2,1", "plaque 1,1,2", "trophy 0,1,1");
    assertThat(alcove.scan().sizeX()).isEqualTo(5);
    assertThat(plinth.scan().sizeY()).isEqualTo(4);
  }
}
