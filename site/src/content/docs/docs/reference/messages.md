---
title: Messages
description: "Changing what players read: messages.yml, MiniMessage formatting and placeholders."
art: book
audience: owners
sidebar:
  order: 3
---

Every line Seasons shows players is in `plugins/Seasons/messages.yml`, and every line of the dashboard is in `plugins/SeasonsWeb/messages.yml`. Change any of them, then `/season reload` (Seasons Web reads its file at startup).

A file only needs the lines you change: anything missing keeps its default, so updates bring new lines without overwriting yours.

## Formatting

Lines use [MiniMessage](https://docs.advntr.dev/minimessage/format.html):

```yaml
hof:
  visit:
    arrived: "<gold>Welcome to the Hall of Fame.</gold> <gray>/hof leave takes you back.</gray>"
```

- Colours: `<red>`, `<gold>`, `<#e6b422>`, `<gradient:gold:yellow>…</gradient>`.
- Decorations: `<bold>`, `<italic>`, `<underlined>`.
- Placeholders such as `<player>`, `<season>` or `<until>` are filled in by Seasons, always as plain text: a player's name can never inject formatting.

## Help texts

`help.*` holds the description of every command and argument that `/season help`, `/hof help` and the [command reference](/docs/reference/commands/) show. Argument texts are plain words, never `<tags>`, since they're shown as plain text.

## Link text

Sign lines and floating text made with `/hof link sign` and `/hof link text` take MiniMessage colours and decorations too, limited to `links.styleTags`; click and hover tags are always stripped.

## The dashboard's text

Seasons Web's `messages.yml` holds the dashboard page's text under `dashboard.*`. These are plain text with `<name>` placeholders, not MiniMessage, because the page shows them as plain text.
