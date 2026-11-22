// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
description = "Seasons Web: the optional read-only dashboard plugin, built on Seasons API"

plugins {
    alias(libs.plugins.shadow)
}

base.archivesName = "seasons-web"

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(project(":api"))
    implementation(libs.bstats.bukkit)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveBaseName = "SeasonsWeb"
    archiveClassifier = ""
    // bStats insists on being relocated, so its classes never clash with another plugin's copy.
    relocate("org.bstats", "com.petepeterepeat.seasons.web.lib.bstats")
    exclude("META-INF/maven/**")
}

tasks.assemble { dependsOn(tasks.shadowJar) }
