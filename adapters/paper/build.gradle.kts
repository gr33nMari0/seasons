// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
description = "Seasons Adapter for Paper, packaged with the core as the Seasons plugin jar"

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

base.archivesName = "seasons-adapter-paper"

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":core"))
    implementation(project(":integrations"))
    implementation(libs.configurate.yaml)

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
    }
}
