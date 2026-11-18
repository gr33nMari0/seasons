// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package seasons.scenarios;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkParameters;

/** What one shard of the scenario suite runs, and where. */
public interface ShardParameters extends WorkParameters {
  /** The shard's number, from 1. */
  Property<Integer> getShard();

  /** The java executable the servers run on. */
  Property<String> getJava();

  RegularFileProperty getPaperJar();

  ConfigurableFileCollection getPluginJars();

  /** The shard's own server folder. */
  DirectoryProperty getRunDirectory();

  /** Where the shard writes its boots' output, its log and shard.json. */
  DirectoryProperty getOutputDirectory();

  Property<Integer> getServerPort();

  Property<Integer> getWebhookPort();

  /** JVM arguments every boot gets, before the per-boot ones. */
  ListProperty<String> getJvmArgs();

  /** The steps in order, each {@code name:session:sessions:isolated}. */
  ListProperty<String> getSteps();

  /** False runs every scenario on a fresh server, as {@code -PscenarioIsolation=fresh}. */
  Property<Boolean> getShared();
}
