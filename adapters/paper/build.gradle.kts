// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import java.util.UUID

description = "Seasons Adapter for Paper, packaged with the core as the Seasons plugin jar"

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.hangar.publish)
    alias(libs.plugins.minotaur)
}

base.archivesName = "seasons-adapter-paper"

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":core"))
    implementation(project(":integrations"))
    implementation(libs.configurate.yaml)
    implementation(libs.bstats.bukkit)

    testImplementation(libs.paper.api.mockbukkit)
    testImplementation(libs.mockbukkit)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

// The shaded jar is the only jar: build/libs holds Seasons-<version>.jar.
tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName = "Seasons"
    archiveClassifier = ""
    // Bundled libraries are relocated so they never clash with another plugin's copy.
    val shaded = "com.petepeterepeat.seasons.lib"
    relocate("org.spongepowered.configurate", "$shaded.configurate")
    relocate("io.leangen.geantyref", "$shaded.geantyref")
    relocate("net.kyori.option", "$shaded.option")
    relocate("com.google.gson", "$shaded.gson")
    relocate("org.yaml.snakeyaml", "$shaded.snakeyaml")
    // bStats insists on being relocated, so its classes never clash with another plugin's copy.
    relocate("org.bstats", "$shaded.bstats")
    // Gson's compile-time annotations are not needed at run time.
    dependencies { exclude(dependency("com.google.errorprone:error_prone_annotations")) }
    exclude("META-INF/maven/**", "META-INF/versions/*/module-info.class", "module-info.class")
    mergeServiceFiles()
}

tasks.assemble { dependsOn(tasks.shadowJar) }

tasks.runServer {
    minecraftVersion(providers.gradleProperty("minecraftVersions").get().split(",").first().trim())
    pluginJars.from(project(":web").tasks.named("shadowJar"))
    jvmArgs("-Dcom.mojang.eula.agree=true")
    // 26.3 turns the whitelist on for newly created server configs, which locks you out of
    // your own dev server. Seed run/server.properties with it off; later edits are kept.
    doFirst {
        val properties = runDirectory.get().asFile.resolve("server.properties")
        if (!properties.exists()) {
            properties.parentFile.mkdirs()
            properties.writeText("white-list=false\n")
        }
        // A dev server never reports to bStats, for any plugin: turned off before every boot, so
        // a run/ folder from before Seasons had metrics is turned off too. Its server id stays.
        val bStats = runDirectory.get().asFile.resolve("plugins/bStats/config.yml")
        val id = if (bStats.exists()) {
            Regex("^serverUuid: *(\\S+)$", RegexOption.MULTILINE).find(bStats.readText())?.groupValues?.get(1)
        } else {
            null
        }
        bStats.parentFile.mkdirs()
        bStats.writeText(
            "# Written by runServer before every boot: a dev server never reports to bStats.\n" +
                "enabled: false\n" +
                "serverUuid: ${id ?: UUID.randomUUID()}\n" +
                "logFailedRequests: false\n",
        )
    }
}

// Publishing a tagged release to Hangar and Modrinth, from release.yml (docs/adr/0006). A version
// with a hyphen, such as 1.1.0-beta.1, goes out as a beta. release.yml passes the jar it already
// attached to the GitHub Release with -PreleaseJar, so all three carry the same bytes.
val releaseVersion = project.version.toString()
val beta = releaseVersion.contains('-')
val releaseJar: Provider<RegularFile> =
    providers.gradleProperty("releaseJar").map { rootProject.layout.projectDirectory.file(it) }
        .orElse(tasks.shadowJar.flatMap { it.archiveFile })
val supportedVersions = providers.gradleProperty("minecraftVersions").get().split(",").map { it.trim() }

/** This version's section of CHANGELOG.md, without its heading. */
fun changelogFor(version: String): String {
    val lines = rootProject.file("CHANGELOG.md").readLines()
    val start = lines.indexOfFirst { it.startsWith("## [$version]") }
    if (start < 0) {
        return "See CHANGELOG.md for $version."
    }
    val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## [") } ?: lines.size
    return lines.subList(start + 1, end).joinToString("\n").trim()
}

hangarPublish {
    publications.register("plugin") {
        id = providers.gradleProperty("hangarProject")
        version = releaseVersion
        channel = if (beta) "Beta" else "Release"
        changelog = provider { changelogFor(releaseVersion) }
        apiKey = providers.environmentVariable("HANGAR_API_TOKEN")
        platforms {
            paper {
                jar = releaseJar
                platformVersions = supportedVersions
                dependencies {
                    hangar("BlueMap") { required = false }
                }
            }
        }
    }
}

modrinth {
    token = providers.environmentVariable("MODRINTH_TOKEN")
    projectId = providers.gradleProperty("modrinthProject")
    versionNumber = releaseVersion
    versionName = "Seasons $releaseVersion"
    versionType = if (beta) "beta" else "release"
    uploadFile.set(releaseJar)
    gameVersions = supportedVersions
    // Paper and the forks that run Paper plugins; Seasons uses Paper-only API, so not Spigot.
    loaders = listOf("paper", "purpur")
    detectLoaders = false
    // -PpublishDebug prints what would be uploaded, and uploads nothing.
    debugMode = providers.gradleProperty("publishDebug").map { true }.orElse(false)
    changelog = provider { changelogFor(releaseVersion) }
    dependencies {
        optional.project("bluemap")
    }
}
