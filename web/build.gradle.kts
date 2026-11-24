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
    implementation(libs.configurate.yaml)

    testImplementation(project(":api"))
    // The /web command tree, walked for the public command reference (WebCommandReference).
    testImplementation(libs.paper.api)
    testImplementation(libs.gson)
    testImplementation(libs.slf4j.api)
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
    // Bundled libraries are relocated so they never clash with another plugin's copy, Seasons'
    // own included.
    val shaded = "com.petepeterepeat.seasons.web.lib"
    relocate("org.spongepowered.configurate", "$shaded.configurate")
    relocate("io.leangen.geantyref", "$shaded.geantyref")
    relocate("net.kyori.option", "$shaded.option")
    relocate("org.yaml.snakeyaml", "$shaded.snakeyaml")
    // bStats insists on being relocated, so its classes never clash with another plugin's copy.
    relocate("org.bstats", "$shaded.bstats")
    exclude("META-INF/maven/**", "META-INF/versions/*/module-info.class", "module-info.class")
    mergeServiceFiles()
}

tasks.assemble { dependsOn(tasks.shadowJar) }

// The dashboard's own modules, with Node's built-in test runner (docs/adr/0007).
val jsTest = tasks.register<Exec>("jsTest") {
    group = "verification"
    description = "Runs the dashboard's JavaScript unit tests with node --test."
    val tests = layout.projectDirectory.dir("src/test/js")
    inputs.dir(tests)
    inputs.dir(layout.projectDirectory.dir("src/main/resources/com/petepeterepeat/seasons/web/dashboard"))
    val stamp = layout.buildDirectory.file("js-test/passed")
    outputs.file(stamp)
    workingDir(tests)
    commandLine(providers.environmentVariable("NODE").getOrElse("node"), "--test", "--test-reporter=dot", "*.test.mjs")
    doLast { stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("passed\n") }
}

tasks.check { dependsOn(jsTest) }

// The public docs' reference for /web (M9): WebCommandReferenceTest fails until it matches.
tasks.register<JavaExec>("commandReference") {
    group = "documentation"
    description = "Writes site/src/data/commands/web.json from the /web command tree"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.petepeterepeat.seasons.web.WebCommandReference"
    args(rootProject.file("site/src/data/commands/web.json").path)
}
