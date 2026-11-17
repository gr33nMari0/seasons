# Third-party notices

Seasons and Seasons Web are licensed under the PolyForm Noncommercial License 1.0.0 (see `LICENSE`). They bundle the libraries below, which keep their own licences. Bundled classes are relocated under `com.petepeterepeat.seasons.lib`.

| Library | Version | Licence | Bundled in |
| --- | --- | --- | --- |
| [Configurate](https://github.com/SpongePowered/Configurate) (`configurate-core`, `configurate-yaml`, which itself bundles SnakeYAML, and `configurate-gson`) | 4.2.0 | Apache-2.0 | Seasons |
| [Gson](https://github.com/google/gson) (for `configurate-gson`) | 2.15.0 | Apache-2.0 | Seasons |
| [geantyref](https://github.com/leangen/geantyref) (a Configurate dependency) | 1.3.16 | Apache-2.0 | Seasons |
| [option](https://github.com/KyoriPowered/option) (a Configurate dependency) | 1.1.0 | MIT | Seasons |

Planned: [bStats](https://github.com/Bastian/bstats-metrics) (MIT), once each jar has a bStats plugin id.

Test-only tools (never bundled): Mineflayer (MIT), ViaVersion and ViaBackwards (GPL-3.0, downloaded onto test servers only), JUnit, AssertJ, MockBukkit, Error Prone, NullAway, Spotless.
