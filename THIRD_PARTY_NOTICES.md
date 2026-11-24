# Third-party notices

Seasons and Seasons Web are licensed under the PolyForm Noncommercial License 1.0.0 (see `LICENSE`). They bundle the libraries below, which keep their own licences. Bundled classes are relocated under `com.petepeterepeat.seasons.lib` (Seasons) and `com.petepeterepeat.seasons.web.lib` (Seasons Web).

| Library | Version | Licence | Bundled in |
| --- | --- | --- | --- |
| [Configurate](https://github.com/SpongePowered/Configurate) (`configurate-core`, `configurate-yaml`, which itself bundles SnakeYAML, and `configurate-gson`) | 4.2.0 | Apache-2.0 | Seasons |
| [Gson](https://github.com/google/gson) (for `configurate-gson`) | 2.15.0 | Apache-2.0 | Seasons |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) (editing `config.yml` in place, ADR 0005) | 2.7 | Apache-2.0 | Seasons |
| [geantyref](https://github.com/leangen/geantyref) (a Configurate dependency) | 1.3.16 | Apache-2.0 | Seasons |
| [option](https://github.com/KyoriPowered/option) (a Configurate dependency) | 1.1.0 | MIT | Seasons |
| [bStats](https://github.com/Bastian/bstats-metrics) (`bstats-bukkit`, `bstats-base`) | 3.2.1 | MIT | Seasons, Seasons Web |
| [Pixelify Sans](https://github.com/eifetx/Pixelify-Sans), [Inter](https://github.com/rsms/inter) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) typefaces (ADR 0009; licences in `design/fonts/`) | Fontsource 5.3.0 | OFL-1.1 | Seasons Web, the public site |

Build- and test-only tools (never bundled in a jar): Astro and Starlight (MIT, they build the public site, ADR 0008), starlight-links-validator (MIT) and @axe-core/playwright (MPL-2.0, ADR 0010), Playwright (Apache-2.0, ADR 0007), Mineflayer (MIT), ViaVersion and ViaBackwards (GPL-3.0, downloaded onto test servers only), JUnit, AssertJ, MockBukkit, Error Prone, NullAway, Spotless.
