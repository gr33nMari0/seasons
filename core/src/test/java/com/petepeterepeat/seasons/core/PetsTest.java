// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.petepeterepeat.seasons.core.announce.Webhooks;
import com.petepeterepeat.seasons.core.command.CommandNode;
import com.petepeterepeat.seasons.core.port.Capabilities;
import com.petepeterepeat.seasons.core.port.Capability;
import com.petepeterepeat.seasons.core.port.PlayerRef;
import com.petepeterepeat.seasons.core.port.Position;
import com.petepeterepeat.seasons.core.store.SerialWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registered pets on the fake platform: the real commands, records and travel. */
class PetsTest {
  private static final Position YARD = Position.of("minecraft:overworld", 200, 64, 200);

  /** A spot in the yard, {@code dx} blocks east. */
  private static Position beside(int dx) {
    return Position.of(YARD.dimension(), YARD.x() + dx, YARD.y(), YARD.z());
  }

  @TempDir Path dir;
  private FakePlatform fake = new FakePlatform();
  private Seasons seasons;

  private final Webhooks webhooks =
      new Webhooks() {
        @Override
        public void post(URI webhook, String content) {}

        @Override
        public void close() {}
      };

  private void start() {
    seasons =
        new Seasons(
            fake.platform(new Capabilities(EnumSet.allOf(Capability.class)), dir),
            "1.2.3",
            fake.clock,
            webhooks,
            Map.of(),
            new SerialWriter(Runnable::run),
            Optional.empty());
    seasons.start();
  }

  private void restart() {
    seasons.stop();
    FakePlatform before = fake;
    fake = new FakePlatform();
    fake.clock.advance(Duration.between(fake.clock.instant(), before.clock.instant()));
    fake.players.putAll(before.players);
    fake.pets.putAll(before.pets);
    start();
  }

  @AfterEach
  void stop() {
    if (seasons != null) {
      seasons.stop();
    }
  }

  private List<String> run(String command) {
    TestSource source = TestSource.console();
    TestCommands.run(fake.commands.getFirst(), source, command);
    return source.text();
  }

  private List<String> pet(PlayerRef player, String command) {
    CommandNode root =
        fake.commands.stream().filter(c -> c.name().equals("pet")).findFirst().orElseThrow();
    TestSource source = new TestSource(Optional.of(player.id()));
    TestCommands.run(root, source, command);
    return source.text();
  }

  /** The player stands in the yard looking at the animal, and runs /pet add. */
  private List<String> register(PlayerRef player, FakePlatform.FakePet animal) {
    fake.player(player).position = YARD;
    fake.looking.put(player.id(), animal.id);
    return pet(player, "/pet add");
  }

  private FakePlatform.FakePet animal(String type, PlayerRef owner) {
    return fake.animal(type, beside(2), owner);
  }

  private List<String> said(PlayerRef player) {
    return fake.sent.stream()
        .filter(sent -> sent.player().equals(player.id()))
        .map(sent -> TestSource.plain(sent.message()))
        .toList();
  }

  private PlayerRef join(String name) {
    PlayerRef player = fake.join(name);
    if (fake.sessions != null) {
      fake.sessions.joined(player, fake.player(player).saves > 0);
    }
    return player;
  }

  private void endAndLaunch() {
    run("/season end --now");
    PlayerRef admin = fake.join("Admin");
    fake.player(admin).position = Position.of("minecraft:overworld", 3000, 64, 0);
    TestCommands.run(
        fake.commands.getFirst(), new TestSource(Optional.of(admin.id())), "/season spawn set");
    fake.leave(admin);
    run("/season launch");
  }

  @Test
  void onlyTheirOwnTamedAnimalsWithoutChestsCanBeRegistered() {
    start();
    PlayerRef pat = fake.join("Pat");
    PlayerRef quinn = fake.join("Quinn");
    run("/season init 2");

    FakePlatform.FakePet rex = animal("wolf", pat);
    rex.name = Optional.of("Rex");
    assertThat(register(pat, rex))
        .containsExactly(
            "Registered Rex (wolf) to travel with you to the next season's spawn (1 of 5).");
    assertThat(rex.marked).contains(pat.id());
    assertThat(register(pat, rex)).containsExactly("Rex is already registered.");

    FakePlatform.FakePet wild = animal("wolf", null);
    wild.tamed = false;
    assertThat(register(pat, wild)).containsExactly("That wolf isn't tamed, so it can't travel.");
    FakePlatform.FakePet villager = animal("villager", null);
    villager.tameable = false;
    assertThat(register(pat, villager))
        .containsExactly("A villager can't be tamed, so it can't travel.");
    FakePlatform.FakePet tom = animal("cat", quinn);
    tom.name = Optional.of("Tom");
    assertThat(register(pat, tom)).containsExactly("Tom belongs to someone else.");
    FakePlatform.FakePet daisy = animal("donkey", pat);
    daisy.chest = true;
    assertThat(register(pat, daisy))
        .containsExactly(
            "Donkey carries a chest. Take it off first: animals carrying chests can't travel.");
    FakePlatform.FakePet far = fake.animal("wolf", beside(8), pat);
    assertThat(register(pat, far))
        .containsExactly("Look at a tamed animal of yours within 5 blocks.");

    // Unnamed pets are named from their type, numbered when the name is taken.
    assertThat(register(pat, animal("wolf", pat)))
        .first()
        .asString()
        .startsWith("Registered Wolf (wolf)");
    assertThat(register(pat, animal("wolf", pat)))
        .first()
        .asString()
        .startsWith("Registered Wolf 2 (wolf)");
    assertThat(register(pat, animal("skeleton_horse", pat)))
        .first()
        .asString()
        .startsWith("Registered Skeleton Horse (skeleton horse)");
    register(pat, animal("cat", pat));
    assertThat(register(pat, animal("cat", pat)))
        .containsExactly("You have registered 5 pets, the most you can.");

    assertThat(pet(pat, "/pet list"))
        .startsWith(
            "Your pets (5 of 5):",
            "- Rex (wolf), last seen at 202, 64, 200 in minecraft:overworld");
    assertThat(pet(pat, "/pet remove wolf 2")).containsExactly("Wolf 2 won't travel.");
    assertThat(pet(pat, "/pet remove Nobody")).containsExactly("You have no pet called Nobody.");
    assertThat(pet(pat, "/pet list")).first().isEqualTo("Your pets (4 of 5):");
  }

  @Test
  void registrationClosesWhenTheSeasonEnds() {
    start();
    PlayerRef pat = fake.join("Pat");
    run("/season init 2");
    run("/season end --now");
    assertThat(register(pat, animal("wolf", pat)))
        .containsExactly("Registration is closed until the next season starts.");
  }

  @Test
  void aDeathIsReportedAtOnceOrAtNextLoginAndAnUnloadIsNot() {
    start();
    PlayerRef pat = fake.join("Pat");
    PlayerRef quinn = fake.join("Quinn");
    run("/season init 2");
    FakePlatform.FakePet rex = animal("wolf", pat);
    rex.name = Optional.of("Rex");
    register(pat, rex);
    FakePlatform.FakePet tom = animal("cat", quinn);
    tom.name = Optional.of("Tom");
    register(quinn, tom);

    Position far = Position.of("minecraft:overworld", 6000.5, 64, 6000.5);
    fake.petEvents.unloaded(rex.id, pat.id(), far);
    assertThat(pet(pat, "/pet list"))
        .containsExactly(
            "Your pets (1 of 5):",
            "- Rex (wolf), last seen at 6000, 64, 6000 in minecraft:overworld");

    fake.petEvents.died(rex.id, pat.id());
    assertThat(said(pat)).containsExactly("Your pet Rex died, so it won't travel.");
    assertThat(pet(pat, "/pet list")).containsExactly("You have no pets registered.");

    fake.leave(quinn);
    fake.petEvents.died(tom.id, quinn.id());
    assertThat(said(quinn)).isEmpty();
    restart();
    join("Quinn");
    assertThat(said(quinn)).containsExactly("Your pet Tom died, so it won't travel.");
    join("Quinn");
    assertThat(said(quinn)).hasSize(1);
  }

  @Test
  void aParrotOnItsOwnersShoulderStaysRegisteredAndComesWithThem() {
    start();
    PlayerRef pat = fake.join("Pat");
    run("/season init 2");
    FakePlatform.FakePet polly = animal("parrot", pat);
    polly.name = Optional.of("Polly");
    register(pat, polly);

    // It lands on Pat's shoulder: out of the world, but not gone.
    fake.pets.remove(polly.id);
    fake.petEvents.onShoulder(polly.id, pat.id());
    assertThat(said(pat)).doesNotContain("Your pet Polly died, so it won't travel.");
    assertThat(pet(pat, "/pet list")).first().isEqualTo("Your pets (1 of 5):");
    fake.leave(pat);
    restart();
    join("Pat");

    endAndLaunch();

    assertThat(said(pat)).contains("Polly has come with you.");
    assertThat(pet(pat, "/pet list")).containsExactly("You have no pets registered.");
  }

  @Test
  void aRemovalThatIsntADeathKeepsTheRegistrationUntilTravel() {
    start();
    PlayerRef pat = fake.join("Pat");
    run("/season init 2");
    FakePlatform.FakePet ghost = animal("wolf", pat);
    ghost.name = Optional.of("Ghost");
    register(pat, ghost);
    FakePlatform.FakePet polly = animal("parrot", pat);
    polly.name = Optional.of("Polly");
    register(pat, polly);

    // Removed by a command, then Polly lands on a shoulder, hops off and unloads elsewhere.
    fake.pets.remove(ghost.id);
    fake.petEvents.unloaded(ghost.id, pat.id(), beside(2));
    fake.petEvents.onShoulder(polly.id, pat.id());
    Position far = Position.of("minecraft:overworld", 6000.5, 64, 6000.5);
    fake.petEvents.unloaded(polly.id, pat.id(), far);
    fake.pets.remove(polly.id);
    assertThat(pet(pat, "/pet list")).first().isEqualTo("Your pets (2 of 5):");

    endAndLaunch();

    // Neither is anywhere: an unload clears the shoulder, so Polly is missing too.
    assertThat(said(pat))
        .contains(
            "Ghost couldn't be found, so it stayed behind.",
            "Polly couldn't be found, so it stayed behind.");
  }

  @Test
  void aPetInsideALockedZoneCantBeRegisteredFromOutsideItsEdge() {
    start();
    PlayerRef pat = fake.join("Pat");
    run("/season init 2");
    endAndLaunch();
    // Season 3's Frontier: a zone of radius 3 around the yard, locked until Homecoming.
    fake.player(pat).position = YARD;
    TestCommands.run(
        fake.commands.getFirst(),
        new TestSource(Optional.of(pat.id())),
        "/season zone add pen cylinder 3");
    FakePlatform.FakePet rex = fake.animal("wolf", beside(2), pat);
    rex.name = Optional.of("Rex");
    fake.player(pat).position = beside(6);
    fake.looking.put(pat.id(), rex.id);

    assertThat(pet(pat, "/pet add"))
        .singleElement()
        .asString()
        .startsWith("That's in the pen, which is locked until ");
    assertThat(rex.marked).isEmpty();

    // A holder of the bypass node may, as they may reach in.
    fake.bypassing.add(pat.id());
    assertThat(pet(pat, "/pet add")).first().asString().startsWith("Registered Rex (wolf)");
  }

  @Test
  void petsTravelAtLaunchAndAtFirstLoginAfterIt() {
    start();
    PlayerRef pat = fake.join("Pat");
    PlayerRef quinn = fake.join("Quinn");
    run("/season init 2");
    FakePlatform.FakePet rex = animal("wolf", pat);
    rex.name = Optional.of("Rex");
    register(pat, rex);
    FakePlatform.FakePet daisy = animal("donkey", pat);
    daisy.name = Optional.of("Daisy");
    register(pat, daisy);
    FakePlatform.FakePet ghost = animal("wolf", pat);
    ghost.name = Optional.of("Ghost");
    register(pat, ghost);
    FakePlatform.FakePet tom = animal("cat", quinn);
    tom.name = Optional.of("Tom");
    register(quinn, tom);
    // Rex wanders off and unloads; Daisy is given a chest; Ghost vanishes unseen.
    Position far = Position.of("minecraft:overworld", 6000.5, 64, 6000.5);
    rex.at = far;
    rex.loaded = false;
    rex.leashed = true;
    fake.petEvents.unloaded(rex.id, pat.id(), far);
    daisy.chest = true;
    fake.pets.remove(ghost.id);
    fake.leave(quinn);

    endAndLaunch();

    Position spawn = fake.player(pat).position;
    assertThat(spawn.x()).isEqualTo(3000.5);
    assertThat(rex.at).isEqualTo(spawn);
    assertThat(rex.leashed).isFalse();
    assertThat(daisy.at).isEqualTo(beside(2));
    assertThat(said(pat))
        .contains(
            "Rex has come with you.",
            "Daisy stayed behind because it carries a chest: animals carrying chests can't travel.",
            "Ghost couldn't be found, so it stayed behind.");
    assertThat(pet(pat, "/pet list")).containsExactly("You have no pets registered.");
    assertThat(fake.petTickets).isEmpty();
    assertThat(run("/season status")).contains("Pets: 1 waiting to travel");

    join("Quinn");
    assertThat(tom.at).isEqualTo(fake.player(quinn).position);
    assertThat(said(quinn)).contains("Tom has come with you.");
    assertThat(run("/season status")).contains("Pets: 0 waiting to travel");
  }

  @Test
  void aPetRegisteredThisSeasonWaitsForTheNext() {
    start();
    PlayerRef pat = fake.join("Pat");
    run("/season init 2");
    endAndLaunch();
    FakePlatform.FakePet rex = animal("wolf", pat);
    rex.name = Optional.of("Rex");
    register(pat, rex);

    join("Pat");

    assertThat(rex.at).isEqualTo(beside(2));
    assertThat(pet(pat, "/pet list")).first().isEqualTo("Your pets (1 of 5):");
  }

  @Test
  void registrationsSurviveARestart() {
    start();
    PlayerRef pat = fake.join("Pat");
    run("/season init 2");
    FakePlatform.FakePet rex = animal("wolf", pat);
    rex.name = Optional.of("Rex");
    register(pat, rex);

    restart();

    assertThat(pet(pat, "/pet list"))
        .containsExactly(
            "Your pets (1 of 5):",
            "- Rex (wolf), last seen at 202, 64, 200 in minecraft:overworld");
  }
}
