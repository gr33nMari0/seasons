// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.FakeItems;
import com.petepeterepeat.seasons.core.config.SeasonsConfig.EnderChestPolicy;
import com.petepeterepeat.seasons.core.port.AdvancementSnapshot;
import com.petepeterepeat.seasons.core.port.Experience;
import com.petepeterepeat.seasons.core.port.PlayerId;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.season.SeasonFirst;
import com.petepeterepeat.seasons.core.store.nbt.Nbt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Archive files: honours, older files, and changing what a record holds besides its items. */
class ArchiveFilesTest {
  private static final PlayerId ALEX = new PlayerId(UUID.randomUUID());

  @TempDir Path dir;

  private static ArchiveRecord archive() {
    return new ArchiveRecord(
        ALEX,
        "Alex",
        2,
        Instant.ofEpochMilli(1_000),
        FakeItems.gear(),
        List.of(),
        EnderChestPolicy.VAULT,
        new AdvancementSnapshot(Map.of(), 3, 122),
        Experience.NONE,
        Map.of("minecraft:deaths", 4L),
        Optional.empty(),
        Position.of("minecraft:overworld", 0, 0, 0),
        Optional.empty(),
        "hash");
  }

  private static ArchiveRecord.Honours honours() {
    return new ArchiveRecord.Honours(
        List.of(new SeasonFirst(2, "nether", "", ALEX, "Alex", Instant.ofEpochMilli(5_000))),
        List.of("most-deaths"));
  }

  @Test
  void honoursRoundTrip() throws IOException {
    ArchiveRecord record = archive().withHonours(honours());

    ArchiveRecord read = ArchiveFiles.decode(ArchiveFiles.encode(record, FakeItems.DATA_VERSION));

    assertThat(read.honours()).isEqualTo(honours());
    assertThat(read).isEqualTo(record);
  }

  @Test
  void version2ReadsWithNoAwards() throws IOException {
    Map<String, Object> root =
        new java.util.LinkedHashMap<>(
            Nbt.read(ArchiveFiles.encode(archive(), FakeItems.DATA_VERSION)));
    root.put("format-version", 2);
    root.remove("honours");

    ArchiveRecord read = ArchiveFiles.decode(Nbt.writeCompressed(root));

    assertThat(read.honours()).isEqualTo(ArchiveRecord.Honours.NONE);
    assertThat(read.carried()).isEqualTo(archive().carried());
  }

  @Test
  void anUpdateKeepsTheItemsHashAndDataVersion() throws IOException {
    ArchiveFiles files = new ArchiveFiles(dir);
    files.write(archive(), 1234);
    Files.writeString(dir.resolve("s2").resolve(ALEX + ".superseded-1.dat"), "aside");

    assertThat(files.updateHonours(ALEX, 2, h -> honours())).isTrue();

    ArchiveRecord read = files.read(ALEX, 2).orElseThrow();
    assertThat(read.honours()).isEqualTo(honours());
    assertThat(read.contentHash()).isEqualTo("hash");
    assertThat(read.carried()).isEqualTo(archive().carried());
    assertThat(Nbt.read(Files.readAllBytes(files.path(ALEX, 2))).get("DataVersion"))
        .isEqualTo(1234);
    // Nothing was moved aside, and records beside the archive aren't listed.
    assertThat(files.all(2).records()).containsExactly(read);
    assertThat(files.players(2)).containsExactly(ALEX);
    assertThat(files.updateHonours(new PlayerId(UUID.randomUUID()), 2, h -> h)).isFalse();
    assertThat(files.all(3).records()).isEmpty();
  }

  @Test
  void anUpdateRewritesOnlyTheHonours() throws IOException {
    ArchiveRecord full =
        new ArchiveRecord(
            ALEX,
            "Alex",
            2,
            Instant.ofEpochMilli(1_000),
            FakeItems.gear(),
            List.of(Optional.of(FakeItems.item("minecraft:stone", 3)), Optional.empty()),
            EnderChestPolicy.ARCHIVE,
            new AdvancementSnapshot(Map.of(), 3, 122),
            new Experience(12, 0.5f, 120),
            Map.of("minecraft:deaths", 4L, "minecraft:play_time", 72_000L),
            Optional.of(
                new com.petepeterepeat.seasons.core.player.FurthestBlock(
                    Optional.of(
                        new com.petepeterepeat.seasons.core.player.FurthestBlock.Best(
                            900, Position.of("minecraft:overworld", 900, 64, 0))),
                    Optional.empty())),
            Position.of("minecraft:the_nether", 10, 70, -5),
            Optional.of(new com.petepeterepeat.seasons.core.port.Skin("tex", Optional.of("sig"))),
            "hash");
    ArchiveFiles files = new ArchiveFiles(dir);
    files.write(full.withHeldSlot(4), 1234);
    Path file = files.path(ALEX, 2);
    Map<String, Object> before = new java.util.LinkedHashMap<>(Nbt.read(Files.readAllBytes(file)));
    // A tag this build doesn't know is kept too.
    before.put("from-the-future", "kept");
    Files.write(file, Nbt.writeCompressed(before));

    files.updateHonours(ALEX, 2, h -> honours());

    Map<String, Object> after = new java.util.LinkedHashMap<>(Nbt.read(Files.readAllBytes(file)));
    assertThat(after.remove("honours")).isNotNull();
    before.remove("honours");
    assertThat(Nbt.canonical(after)).isEqualTo(Nbt.canonical(before));
    assertThat(files.read(ALEX, 2).orElseThrow().honours()).isEqualTo(honours());
  }

  @Test
  void aDamagedArchiveIsNamedAndLeftOut() throws IOException {
    ArchiveFiles files = new ArchiveFiles(dir);
    files.write(archive(), 1234);
    PlayerId other = new PlayerId(UUID.randomUUID());
    Files.writeString(dir.resolve("s2").resolve(other + ".dat"), "not an archive");

    ArchiveFiles.SeasonArchives found = files.all(2);

    assertThat(found.records()).extracting(ArchiveRecord::player).containsExactly(ALEX);
    assertThat(found.damaged()).containsExactly(other + ".dat");
    assertThat(files.players(2)).containsExactlyInAnyOrder(ALEX, other);
  }

  @Test
  void aRewriteChangesTheRecordInPlaceAndKeepsItsDataVersion() throws IOException {
    ArchiveFiles files = new ArchiveFiles(dir);
    files.write(archive(), 1234);
    ArchiveRecord changed =
        archive().with(ArchiveSlot.of(ArchiveSlot.STORAGE, 0), Optional.empty());

    files.rewrite(changed);

    assertThat(files.read(ALEX, 2)).contains(changed);
    assertThat(Nbt.read(Files.readAllBytes(files.path(ALEX, 2))).get("DataVersion"))
        .isEqualTo(1234);
    try (var listed = Files.list(dir.resolve("s2"))) {
      assertThat(listed).hasSize(1);
    }
  }

  @Test
  void aRewriteNeedsAnArchive() {
    ArchiveFiles files = new ArchiveFiles(dir);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> files.rewrite(archive()))
        .isInstanceOf(IOException.class);
  }
}
