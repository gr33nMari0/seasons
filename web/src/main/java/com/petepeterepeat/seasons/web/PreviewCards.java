// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.petepeterepeat.seasons.web;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Draws a 1200 × 630 link preview card in Seasons' design (ADR 0011): the night panel, the logo and
 * server name, a title in Pixelify Sans, a line of text, a picture (pixel art from design/art, or a
 * player's head), the dashboard's address and a grass strip. Java2D, headless, off the server
 * thread; the fonts are TrueType copies of the design's, bundled with Seasons Web.
 */
final class PreviewCards {
  static final int WIDTH = 1200;
  static final int HEIGHT = 630;

  private static final Color NIGHT = new Color(0x1d2333);
  private static final Color DEEP = new Color(0x141823);
  private static final Color LINE = new Color(0x3a4358);
  private static final Color SHADOW = new Color(0x0e121c);
  private static final Color INK = new Color(0xf6f3ec);
  private static final Color INK_2 = new Color(0xc9ced8);
  private static final Color INK_3 = new Color(0xa9b0bf);
  private static final Color GOLD = new Color(0xe6b422);
  private static final Color GOLD_LO = new Color(0xb88a10);
  private static final Color DIAMOND = new Color(0x3fc6c6);
  private static final Color[] GRASS = {
    new Color(0x5aa63a), new Color(0x3d7a26), new Color(0x6fbf4c)
  };
  private static final Color[] DIRT = {
    new Color(0x7a5230), new Color(0x5c3d22), new Color(0x8a6038)
  };

  private static final Pattern WORDS = Pattern.compile("\\s+");
  private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"");
  private static final Pattern RECT =
      Pattern.compile(
          "<rect x=\"([\\d.]+)\" y=\"([\\d.]+)\" width=\"([\\d.]+)\" height=\"([\\d.]+)\" fill=\"#([0-9a-fA-F]{6})\"");

  /** What a card says. */
  record Card(String section, String title, String description, Picture picture) {}

  /** A card's picture: pixel art by name, or a player's face. */
  sealed interface Picture permits Art, Face {}

  /** Pixel art from design/art, by name, such as {@code figure-diamond}. */
  record Art(String name) implements Picture {}

  /** A player's face, 8 × 8 pixels with the hat layer over it, as ARGB. */
  record Face(BufferedImage pixels) implements Picture {}

  private final Font pixel;
  private final Font body;
  private final Font mono;
  private final Map<String, Optional<BufferedImage>> art = new ConcurrentHashMap<>();

  PreviewCards() {
    this.pixel = font("PixelifySans.ttf");
    this.body = font("Inter.ttf");
    this.mono = font("JetBrainsMono.ttf");
  }

  private static Font font(String name) {
    try (InputStream in =
        Objects.requireNonNull(
            PreviewCards.class.getResourceAsStream("previews/" + name), name + " is bundled")) {
      return Font.createFont(Font.TRUETYPE_FONT, in);
    } catch (IOException | FontFormatException e) {
      throw new IllegalStateException("Can't read the preview font " + name, e);
    }
  }

  /** Pixel art from the bundled design, one pixel per unit of its viewBox. */
  Optional<BufferedImage> art(String name) {
    return art.computeIfAbsent(name, PreviewCards::readArt);
  }

  private static Optional<BufferedImage> readArt(String name) {
    if (!name.matches("[a-z0-9-]{1,40}")) {
      return Optional.empty();
    }
    try (InputStream in =
        PreviewCards.class.getResourceAsStream("dashboard/design/art/" + name + ".svg")) {
      if (in == null) {
        return Optional.empty();
      }
      String svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Matcher box = VIEW_BOX.matcher(svg);
      if (!box.find()) {
        return Optional.empty();
      }
      int w = Integer.parseInt(box.group(1));
      int h = Integer.parseInt(box.group(2));
      if (w > 400 || h > 400) {
        return Optional.empty();
      }
      BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
      Matcher r = RECT.matcher(svg);
      while (r.find()) {
        int x = (int) Math.floor(Double.parseDouble(r.group(1)));
        int y = (int) Math.floor(Double.parseDouble(r.group(2)));
        int rw = Math.max(1, (int) Math.floor(Double.parseDouble(r.group(3))));
        int rh = Math.max(1, (int) Math.floor(Double.parseDouble(r.group(4))));
        int rgb = 0xff000000 | Integer.parseInt(r.group(5), 16);
        for (int dy = 0; dy < rh; dy++) {
          for (int dx = 0; dx < rw; dx++) {
            if (x + dx < w && y + dy < h) {
              out.setRGB(x + dx, y + dy, rgb);
            }
          }
        }
      }
      return Optional.of(out);
    } catch (IOException | NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * A card as a PNG.
   *
   * @param server the server's name, beside the logo
   * @param address the dashboard's host, in the footer
   */
  Blob draw(String server, String address, Card card) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      g.setColor(NIGHT);
      g.fillRect(0, 0, WIDTH, HEIGHT);
      g.setColor(LINE);
      g.setStroke(new BasicStroke(8));
      g.drawRect(4, 4, WIDTH - 8, HEIGHT - 8);

      // The top row: the logo, the server's name, the section chip.
      art("logo").ifPresent(logo -> pixels(g, logo, 64, 44, 2));
      g.setColor(INK);
      g.setFont(pixel.deriveFont(Font.BOLD, 40f));
      String name = fit(g, server, 560);
      g.drawString(name, 148, 92);
      if (!card.section().isEmpty()) {
        g.setFont(mono.deriveFont(Font.BOLD, 24f));
        FontMetrics m = g.getFontMetrics();
        String section = fit(g, card.section(), 380);
        int w = m.stringWidth(section) + 32;
        int x = WIDTH - 64 - w;
        g.setColor(DEEP);
        g.fillRect(x, 52, w, 50);
        g.setColor(GOLD_LO);
        g.setStroke(new BasicStroke(3));
        g.drawRect(x, 52, w, 50);
        g.setColor(GOLD);
        g.drawString(section, x + 16, 86);
      }

      // The picture, on the right, with a hard shadow.
      int pictureRight = WIDTH - 64;
      int textRight = drawPicture(g, card.picture(), pictureRight);

      // The title and the line of text.
      g.setColor(INK);
      float size = 76f;
      List<String> title;
      while (true) {
        g.setFont(pixel.deriveFont(Font.BOLD, size));
        title = wrap(g, card.title(), textRight - 64, 2);
        if ((title.size() <= 2 && g.getFontMetrics().stringWidth(title.getLast()) <= textRight - 64)
            || size <= 44f) {
          break;
        }
        size -= 6f;
      }
      int y = 250;
      for (String line : title) {
        g.drawString(line, 64, y);
        y += (int) (size * 1.08f);
      }
      g.setColor(INK_2);
      g.setFont(body.deriveFont(30f));
      for (String line : wrap(g, card.description(), textRight - 64, 2)) {
        g.drawString(line, 64, y + 14);
        y += 42;
      }

      // The footer and the grass strip.
      g.setFont(mono.deriveFont(22f));
      g.setColor(INK_3);
      g.drawString(fit(g, address, 700), 64, HEIGHT - 66);
      g.setColor(DIAMOND);
      String tag = "Seasons Web";
      g.drawString(tag, WIDTH - 64 - g.getFontMetrics().stringWidth(tag), HEIGHT - 66);
      ground(g, HEIGHT - 48, 40);
    } finally {
      g.dispose();
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return Blob.of(out.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("Can't encode a preview", e);
    }
  }

  /** Draws the picture; returns where the text column must end. */
  private int drawPicture(Graphics2D g, Picture picture, int right) {
    BufferedImage img;
    int scale;
    switch (picture) {
      case Face face -> {
        img = face.pixels();
        scale = 28;
      }
      case Art a -> {
        Optional<BufferedImage> found = art(a.name());
        if (found.isEmpty()) {
          return right;
        }
        img = found.get();
        scale = Math.max(1, Math.min(240 / img.getWidth(), 240 / img.getHeight()));
      }
    }
    int w = img.getWidth() * scale;
    int h = img.getHeight() * scale;
    int x = right - w;
    int y = 140 + (300 - h) / 2;
    if (picture instanceof Face) {
      g.setColor(SHADOW);
      g.fillRect(x + 12, y + 12, w, h);
      g.setColor(LINE);
      g.fillRect(x - 6, y - 6, w + 12, h + 12);
    }
    pixels(g, img, x, y, scale);
    return x - 48;
  }

  private static void pixels(Graphics2D g, BufferedImage img, int x, int y, int scale) {
    for (int py = 0; py < img.getHeight(); py++) {
      for (int px = 0; px < img.getWidth(); px++) {
        int argb = img.getRGB(px, py);
        if ((argb >>> 24) < 128) {
          continue;
        }
        g.setColor(new Color(argb, false));
        g.fillRect(x + px * scale, y + py * scale, scale, scale);
      }
    }
  }

  /** A strip of grass over dirt, in 8-pixel blocks, the same every time. */
  private static void ground(Graphics2D g, int top, int height) {
    int block = 8;
    for (int bx = 0; bx * block < WIDTH; bx++) {
      for (int by = 0; by * block < height; by++) {
        int n = (bx * 73 + by * 151 + bx * by * 7) % 11;
        boolean grass = by < 2 || (by == 2 && n % 3 == 0);
        Color[] palette = grass ? GRASS : DIRT;
        g.setColor(palette[n % palette.length]);
        g.fillRect(bx * block, top + by * block, block, block);
      }
    }
  }

  /** Text cut to fit a width, with an ellipsis. */
  private static String fit(Graphics2D g, String text, int width) {
    FontMetrics m = g.getFontMetrics();
    if (m.stringWidth(text) <= width) {
      return text;
    }
    String t = text;
    while (!t.isEmpty() && m.stringWidth(t + "…") > width) {
      t = t.substring(0, t.length() - 1);
    }
    return t.strip() + "…";
  }

  /** Text wrapped into at most {@code lines} lines, the last cut to fit. */
  private static List<String> wrap(Graphics2D g, String text, int width, int lines) {
    FontMetrics m = g.getFontMetrics();
    List<String> out = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : WORDS.splitAsStream(text.strip()).toList()) {
      String next = line.isEmpty() ? word : line + " " + word;
      if (m.stringWidth(next) <= width || line.isEmpty()) {
        line.setLength(0);
        line.append(next);
      } else {
        out.add(line.toString());
        line.setLength(0);
        line.append(word);
      }
    }
    if (!line.isEmpty()) {
      out.add(line.toString());
    }
    if (out.isEmpty()) {
      out.add("");
    }
    if (out.size() > lines) {
      List<String> kept = new ArrayList<>(out.subList(0, lines));
      String rest = String.join(" ", out.subList(lines - 1, out.size()));
      kept.set(lines - 1, fit(g, rest, width));
      return kept;
    }
    out.set(out.size() - 1, fit(g, out.getLast(), width));
    return out;
  }

  /** A face from a 64 × 64 skin: the face at 8, 8 and the hat over it at 40, 8. */
  static Optional<BufferedImage> face(BufferedImage skin) {
    if (skin.getWidth() < 64 || skin.getHeight() < 32) {
      return Optional.empty();
    }
    BufferedImage out = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) {
        int face = skin.getRGB(8 + x, 8 + y) | 0xff000000;
        int hat = skin.getRGB(40 + x, 8 + y);
        out.setRGB(x, y, (hat >>> 24) >= 128 ? hat | 0xff000000 : face);
      }
    }
    return Optional.of(out);
  }
}
