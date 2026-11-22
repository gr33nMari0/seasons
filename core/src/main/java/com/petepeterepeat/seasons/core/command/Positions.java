// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.core.command;

import com.petepeterepeat.seasons.core.port.Position;
import java.util.List;
import java.util.Optional;

/**
 * Positions typed in commands (questions 264): {@code x y z}, each part a number or {@code ~} /
 * {@code ~n} relative to the sender, or all three as {@code ^ ^ ^n} along their facing, as vanilla
 * commands read them; or {@code here}, the block the sender stands in. The console has no position,
 * so it must give numbers.
 */
public final class Positions {
  /** The word for the block the sender stands in. */
  public static final String HERE = "here";

  /** The word for the wand selection, where a box is asked for. */
  public static final String SELECTION = "sel";

  /** The message keys a failure names. */
  public static final String BAD = "position.bad";

  public static final String CONSOLE = "position.console";

  private Positions() {}

  /** What reading a position came to. */
  public sealed interface Read {
    /**
     * A block read.
     *
     * @param block the block, with no facing
     * @param used how many words it took
     */
    record Block(Position block, int used) implements Read {}

    /** It couldn't be read; {@code messageKey} says why. */
    record Failed(String messageKey) implements Read {}
  }

  /**
   * Reads the position starting at {@code words[from]}.
   *
   * @param origin where the sender stands and faces, empty for the console
   * @param dimension the dimension the position is in
   */
  public static Read block(
      List<String> words, int from, Optional<Position> origin, String dimension) {
    if (from >= words.size()) {
      return new Read.Failed(BAD);
    }
    if (words.get(from).equals(HERE)) {
      if (origin.isEmpty()) {
        return new Read.Failed(CONSOLE);
      }
      Position at = origin.get();
      return new Read.Block(Position.of(dimension, at.blockX(), at.blockY(), at.blockZ()), 1);
    }
    if (from + 3 > words.size()) {
      return new Read.Failed(BAD);
    }
    List<String> parts = words.subList(from, from + 3);
    long local = parts.stream().filter(p -> p.startsWith("^")).count();
    if (local == 3) {
      return local(parts, origin, dimension);
    }
    if (local > 0) {
      return new Read.Failed(BAD);
    }
    double[] values = new double[3];
    for (int i = 0; i < 3; i++) {
      String part = parts.get(i);
      Optional<Double> number;
      if (part.startsWith("~")) {
        if (origin.isEmpty()) {
          return new Read.Failed(CONSOLE);
        }
        Position at = origin.get();
        double base = i == 0 ? at.x() : i == 1 ? at.y() : at.z();
        number = offset(part.substring(1)).map(o -> base + o);
      } else {
        number = number(part);
      }
      if (number.isEmpty()) {
        return new Read.Failed(BAD);
      }
      values[i] = number.get();
    }
    return new Read.Block(
        Position.of(
            dimension,
            (int) Math.floor(values[0]),
            (int) Math.floor(values[1]),
            (int) Math.floor(values[2])),
        3);
  }

  /**
   * A whole text that is one position and perhaps a yaw after it: {@code <position> [yaw]}. With no
   * yaw typed, the block gets {@code yaw}.
   */
  public static Read typed(String text, Optional<Position> origin, String dimension, float yaw) {
    List<String> words = Words.of(text);
    Read read = block(words, 0, origin, dimension);
    if (!(read instanceof Read.Block found)) {
      return read;
    }
    Optional<Float> typedYaw = Optional.empty();
    if (words.size() == found.used() + 1) {
      typedYaw = yaw(words.get(found.used()));
      if (typedYaw.isEmpty()) {
        return new Read.Failed(BAD);
      }
    } else if (words.size() != found.used()) {
      return new Read.Failed(BAD);
    }
    Position at = found.block();
    return new Read.Block(
        new Position(dimension, at.x(), at.y(), at.z(), typedYaw.orElse(yaw), 0f), words.size());
  }

  /** Whether a word could start a position, so a command can tell it from a name. */
  public static boolean startsPosition(String word) {
    return word.equals(HERE)
        || word.startsWith("~")
        || word.startsWith("^")
        || number(word).isPresent();
  }

  /** A yaw typed after a position, if the word is a number. */
  public static Optional<Float> yaw(String word) {
    return number(word).map(Double::floatValue);
  }

  /** The sender's facing rounded as anchors and spawns keep it, or 0 for the console. */
  public static float facing(Optional<Position> origin) {
    return origin.map(Position::yaw).orElse(0f);
  }

  /**
   * {@code ^left ^up ^forward} from the sender's feet, as vanilla's local coordinates are worked
   * out.
   */
  private static Read local(List<String> parts, Optional<Position> origin, String dimension) {
    if (origin.isEmpty()) {
      return new Read.Failed(CONSOLE);
    }
    double[] amounts = new double[3];
    for (int i = 0; i < 3; i++) {
      Optional<Double> amount = offset(parts.get(i).substring(1));
      if (amount.isEmpty()) {
        return new Read.Failed(BAD);
      }
      amounts[i] = amount.get();
    }
    Position at = origin.get();
    double yaw = Math.toRadians(at.yaw() + 90);
    double pitch = Math.toRadians(-at.pitch());
    double upPitch = Math.toRadians(-at.pitch() + 90);
    double[] forward = {
      Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch)
    };
    double[] up = {
      Math.cos(yaw) * Math.cos(upPitch), Math.sin(upPitch), Math.sin(yaw) * Math.cos(upPitch)
    };
    // left = -(forward × up)
    double[] left = {
      -(forward[1] * up[2] - forward[2] * up[1]),
      -(forward[2] * up[0] - forward[0] * up[2]),
      -(forward[0] * up[1] - forward[1] * up[0])
    };
    double x = at.x() + left[0] * amounts[0] + up[0] * amounts[1] + forward[0] * amounts[2];
    double y = at.y() + left[1] * amounts[0] + up[1] * amounts[1] + forward[1] * amounts[2];
    double z = at.z() + left[2] * amounts[0] + up[2] * amounts[1] + forward[2] * amounts[2];
    // Rounding guards against 99.99999 for what is 100 on paper.
    return new Read.Block(Position.of(dimension, floor(x), floor(y), floor(z)), 3);
  }

  private static int floor(double value) {
    return (int) Math.floor(value + 1e-9);
  }

  private static Optional<Double> offset(String text) {
    return text.isEmpty() ? Optional.of(0.0) : number(text);
  }

  private static final java.util.regex.Pattern NUMBER =
      java.util.regex.Pattern.compile("-?(\\d+(\\.\\d*)?|\\.\\d+)");

  private static Optional<Double> number(String text) {
    return NUMBER.matcher(text).matches()
        ? Optional.of(Double.parseDouble(text))
        : Optional.empty();
  }
}
